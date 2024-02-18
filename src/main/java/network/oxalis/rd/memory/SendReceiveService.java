package network.oxalis.rd.memory;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.cert.X509Certificate;
import java.util.EnumSet;
import java.util.stream.Collectors;

import javax.servlet.DispatcherType;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.servlet.DefaultServlet;
import org.eclipse.jetty.servlet.ServletContextHandler;

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
public class SendReceiveService {

	private static final boolean PRINT_RESPONSE = false;
	private static boolean LOG_INSTALLED_MODULES = false;

	private Injector injector;
	private Server server;
	private MessageSender messageSender;

	public SendReceiveService() throws Exception {
		this.injector = this.buildInjector();

		int serverPort = 8080;
		this.server = new Server(serverPort);
		ServletContextHandler handler = new ServletContextHandler(this.server, "/");
		handler.addFilter(GuiceFilter.class, "/*", EnumSet.allOf(DispatcherType.class));
		handler.addEventListener(new GuiceServletContextListener() {
			protected Injector getInjector() {
				return SendReceiveService.this.injector;
			}
		});
		handler.addServlet(DefaultServlet.class, "/");
		this.server.start();

		messageSender = injector.getInstance(Key.get(MessageSender.class, Names.named("oxalis-as4")));
	}

	public void stop() throws Exception {
		if (this.server != null) {
			this.server.stop();
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

	protected long send(File payloadFile) throws Exception {
		X509Certificate serverCertificate = injector.getInstance(X509Certificate.class);
		String serverUrl = "http://localhost:8080/as4";
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
