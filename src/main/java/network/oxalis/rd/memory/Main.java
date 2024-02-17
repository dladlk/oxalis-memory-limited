package network.oxalis.rd.memory;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.Security;
import java.security.cert.X509Certificate;
import java.util.EnumSet;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import javax.crypto.Cipher;
import javax.servlet.DispatcherType;

import org.apache.commons.io.IOUtils;
import org.apache.xml.security.algorithms.JCEMapper;
import org.apache.xml.security.utils.EncryptionConstants;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.servlet.DefaultServlet;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.bridge.SLF4JBridgeHandler;

import com.google.inject.AbstractModule;
import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.Key;
import com.google.inject.name.Names;
import com.google.inject.servlet.GuiceFilter;
import com.google.inject.servlet.GuiceServletContextListener;
import com.google.inject.util.Modules;

import io.opentracing.Tracer;
import io.opentracing.noop.NoopTracerFactory;
import lombok.extern.slf4j.Slf4j;
import network.oxalis.api.outbound.MessageSender;
import network.oxalis.api.outbound.TransmissionRequest;
import network.oxalis.api.outbound.TransmissionResponse;
import network.oxalis.api.tag.Tag;
import network.oxalis.as4.util.PeppolConfiguration;
import network.oxalis.commons.guice.GuiceModuleLoader;
import network.oxalis.vefa.peppol.common.model.DocumentTypeIdentifier;
import network.oxalis.vefa.peppol.common.model.Endpoint;
import network.oxalis.vefa.peppol.common.model.Header;
import network.oxalis.vefa.peppol.common.model.ParticipantIdentifier;
import network.oxalis.vefa.peppol.common.model.ProcessIdentifier;
import network.oxalis.vefa.peppol.common.model.TransportProfile;

@Slf4j
public class Main {

	private static final Logger logResult = LoggerFactory.getLogger("network.oxalis.rd.Result");

	protected Injector injector;
	protected Server server;

	private static boolean INCREASE_ATTACHMENT = true;
	private static boolean LOG_INSTALLED_MODULES = false;
	// Default threshould of org.apache.cxf.io.CachedOutputStream in Oxalis is 128KB of encrypted zipped attachment - so having attachment more than 150 KB size tests file-based processing
	private static final boolean CONFIGURE_JUL_LOGGING = true; // If you need to debug CXF java.util.logging(JUL) logging level, works with slf4j-to-jul
	private static final boolean PRINT_RESPONSE = false;

	private double increaseAttachmentSizeMB = 1;
	private final byte[] original;
	private MessageSender messageSender;

	private double maxMemory;

	private long zipFileSize;

	public Main() throws Exception {
		this.original = resourceToByteArray("/sbd-test-file.xml");
	}

	public static void main(String[] args) throws Exception {
		Security.setProperty("jdk.security.provider.preferred", "AES/GCM/NoPadding:BC");
		if (CONFIGURE_JUL_LOGGING) {
			SLF4JBridgeHandler.removeHandlersForRootLogger();
			SLF4JBridgeHandler.install();
		}
		Main main = new Main();
		main.beforeClass();
		boolean failed = false;
		long duration = 0;
		try {
			if (args.length > 0) {
				for (int i = 0; i < args.length; i++) {
					main.increaseAttachmentSizeMB = Double.valueOf(args[i]);
					duration = main.send();
				}
			} else {
				try {
					duration = 0;
					duration = main.send();
				} catch (Throwable e) {
					log.error(e.getMessage());
					failed = true;
				}

			}
		} finally {
			main.afterClass();
		}
		logResult.info("{}\t{}\t{}\t{}\t{}", main.increaseAttachmentSizeMB, main.zipFileSize, main.maxMemory, failed ? "ERROR" : "OK", duration);
		if (failed) {
			System.exit(1);
		}
	}

	private byte[] resourceToByteArray(String resource) throws Exception {
		try (InputStream is = this.getClass().getResourceAsStream(resource)) {
			return is.readAllBytes();
		}
	}

	public void beforeClass() throws Exception {
		maxMemory = TestUtil.logMaxMemory(log);

		this.injector = this.buildInjector();

		int serverPort = 8080;
		this.server = new Server(serverPort);
		ServletContextHandler handler = new ServletContextHandler(this.server, "/");
		handler.addFilter(GuiceFilter.class, "/*", EnumSet.allOf(DispatcherType.class));
		handler.addEventListener(new GuiceServletContextListener() {
			protected Injector getInjector() {
				return Main.this.injector;
			}
		});
		handler.addServlet(DefaultServlet.class, "/");
		this.server.start();

		messageSender = injector.getInstance(Key.get(MessageSender.class, Names.named("oxalis-as4")));
	}

	public void afterClass() throws Exception {
		if (this.server != null) {
			this.server.stop();
		}
	}

	private String getCipherImplementation() {
		String jceAlgorithm = JCEMapper.translateURItoJCEID(EncryptionConstants.ALGO_ID_BLOCKCIPHER_AES128_GCM);
		try {
			Cipher cipher = Cipher.getInstance(jceAlgorithm);
			return cipher.getProvider().getClass().getName();
		} catch (Exception e) {
			return e.getMessage();
		}
	}

	public Injector buildInjector() {
		return Guice.createInjector(
				Modules.override(new GuiceModuleLoader() {
					@Override
					protected void configure() {
						getModules().forEach(e -> {
							log.debug("Installing {}", e.getClass().getName());
							binder().install(e);
						});
						if (LOG_INSTALLED_MODULES) {
							int size = getModules().size();
							String installedModules = getModules()
									.stream()
									.map(m -> m.getClass().getName())
									.sorted()
									.collect(Collectors.joining("\n\t", "\t", ""));
							log.info("Installed {} modules: \n{}", size, installedModules);
						}
					}
				}).with(new AbstractModule() {
					@Override
					protected void configure() {
						bind(Key.get(Tracer.class)).toProvider(NoopTracerFactory::create);
					}
				}));
	}

	public long send() throws Exception {
		String cipherImplementation = getCipherImplementation();
		log.info("cipher.aes128_gcm.provider.impl: {}", cipherImplementation);
		if (!"org.bouncycastle.jce.provider.BouncyCastleProvider".equals(cipherImplementation)) {
			log.error("ATTENTION: Cipher is not configured to BouncyCastle, decryption performance or memory usage is degraded in SunJCE!");
		}
		X509Certificate serverCertificate = injector.getInstance(X509Certificate.class);
		log.info("Send an invoice with {} mb attachment", this.increaseAttachmentSizeMB);
		File payloadFile = new BigTestDocumentBuilder(original)
				.increaseAttachment(INCREASE_ATTACHMENT, increaseAttachmentSizeMB)
				.build();
		File zipFile = new File(payloadFile.getAbsolutePath() + ".zip");
		if (zipFile.exists()) {
			this.zipFileSize = zipFile.length();
		} else {
			this.zipFileSize = createZipFile(zipFile, payloadFile);
		}

		String serverUrl = "http://localhost:8080/as4";
		return send(this.messageSender, serverUrl, serverCertificate, payloadFile);
	}

	private long createZipFile(File zipFile, File payloadFile) throws IOException {
		try (ZipOutputStream zipOut = new ZipOutputStream(Files.newOutputStream(zipFile.toPath()));
				FileInputStream fis = new FileInputStream(payloadFile)) {
			ZipEntry zipEntry = new ZipEntry(payloadFile.getName());
			zipOut.putNextEntry(zipEntry);
			IOUtils.copy(fis, zipOut);
		}
		return zipFile.length();
	}

	protected long send(MessageSender messageSender, String serverUrl, X509Certificate serverCertificate, File payloadFile) throws Exception {
		final File finalPayloadFile = payloadFile;
		try {
			long payloadFileSizeKB = payloadFile.length() / 1024;
			log.info("Start sending {} KB file to URL {} with certificate: {}", BigTestDocumentBuilder.df(payloadFileSizeKB), serverUrl, serverCertificate.getSubjectX500Principal());
			long startSend = System.currentTimeMillis();

			Endpoint endpoint = Endpoint.of(TransportProfile.AS4, URI.create(serverUrl), serverCertificate);

			SendTransmissionRequest sendTransmissionRequest = new SendTransmissionRequest(endpoint, finalPayloadFile);

			TransmissionResponse response = messageSender.send(sendTransmissionRequest);

			long duration = System.currentTimeMillis() - startSend;
			log.info("Received response in {} ms", duration);

			if (PRINT_RESPONSE) {
				String receipt = new String(response.getReceipts().get(0).getValue(), StandardCharsets.UTF_8);
				log.info(receipt);
			}

			return duration;

		} finally {
			if (finalPayloadFile != null && finalPayloadFile.exists()) {
				finalPayloadFile.delete();
				if (finalPayloadFile.exists()) {
					finalPayloadFile.deleteOnExit();
				}
			}
		}
	}

	public static class SendTransmissionRequest implements TransmissionRequest {

		private File finalPayloadFile;
		private Endpoint endpoint;

		public SendTransmissionRequest(Endpoint endpoint, File finalPayloadFile) {
			this.endpoint = endpoint;
			this.finalPayloadFile = finalPayloadFile;

		}

		@Override
		public Endpoint getEndpoint() {
			return endpoint;
		}

		@Override
		public Header getHeader() {
			return Header.newInstance()
					.sender(ParticipantIdentifier.of("0007:5567125082"))
					.receiver(ParticipantIdentifier.of("0007:4455454480"))
					.documentType(DocumentTypeIdentifier.of("urn:oasis:names:specification:ubl:schema:xsd:Invoice-2::Invoice##urn:www.cenbii.eu:transaction:biicoretrdm010:ver1.0:#urn:www.peppol.eu:bis:peppol4a:ver1.0::2.0"))
					.process(ProcessIdentifier.of("urn:www.cenbii.eu:profile:bii04:ver1.0"));
		}

		@Override
		public InputStream getPayload() {
			try {
				return new BufferedInputStream(Files.newInputStream(finalPayloadFile.toPath()));
			} catch (Exception e) {
				throw new RuntimeException("Failed to read file " + finalPayloadFile, e);
			}
		}

		@Override
		public Tag getTag() {
			return new PeppolConfiguration();
		}
	}

}
