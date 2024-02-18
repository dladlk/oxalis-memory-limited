package network.oxalis.rd.memory;

import java.io.File;
import java.io.InputStream;
import java.security.Security;

import javax.crypto.Cipher;

import org.apache.xml.security.algorithms.JCEMapper;
import org.apache.xml.security.utils.EncryptionConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.bridge.SLF4JBridgeHandler;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class Main {

	private static final Logger logResult = LoggerFactory.getLogger("network.oxalis.rd.Result");

	private static final boolean CONFIGURE_JUL_LOGGING = true; // If you need to debug CXF java.util.logging(JUL) logging level, works with slf4j-to-jul

	private double increaseAttachmentSizeMB = 1;
	private final byte[] original;

	private double maxMemory;

	private long zipFileSize;

	private BigTestDocumentBuilder documentBuilder;

	private SendReceiveService service;

	public Main() throws Exception {
		this.original = resourceToByteArray("/sbd-test-file.xml");
		this.documentBuilder = new BigTestDocumentBuilder(original);
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
				long minDuration = Long.MAX_VALUE;
				for (int i = 0; i < args.length; i++) {
					main.increaseAttachmentSizeMB = Double.valueOf(args[i]);
					duration = main.send();
					minDuration = Math.min(minDuration, duration);
					logResult.info("\t{}\t{}\t{}\t{}", (int) main.increaseAttachmentSizeMB, String.format("%.2f", main.zipFileSize / 1024.0 / 1024.0), (int) main.maxMemory, duration);
				}
				logResult.info("\t{}\t{}\t{}\t{}\t{}", (int) main.increaseAttachmentSizeMB, String.format("%.2f", main.zipFileSize / 1024.0 / 1024.0), (int) main.maxMemory, minDuration, "MINIMAL AMONG " + args.length);
			} else {
				try {
					duration = 0;
					duration = main.send();
					logResult.info("\t{}\t{}\t{}\t{}", (int) main.increaseAttachmentSizeMB, String.format("%.2f", main.zipFileSize / 1024.0 / 1024.0), (int) main.maxMemory, duration);
				} catch (Throwable e) {
					log.error(e.getMessage());
					failed = true;
				}

			}
		} finally {
			main.afterClass();
		}
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
		service = new SendReceiveService();
	}

	public void afterClass() throws Exception {
		service.stop();
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

	public long send() throws Exception {
		String cipherImplementation = getCipherImplementation();
		log.info("cipher.aes128_gcm.provider.impl: {}", cipherImplementation);
		if (!"org.bouncycastle.jce.provider.BouncyCastleProvider".equals(cipherImplementation)) {
			log.error("ATTENTION: Cipher is not configured to BouncyCastle, decryption performance or memory usage is degraded in SunJCE!");
		}
		log.info("Send an invoice with {} mb attachment", this.increaseAttachmentSizeMB);
		File payloadFile = documentBuilder
				.increaseAttachment(true, increaseAttachmentSizeMB)
				.build();
		this.zipFileSize = documentBuilder.getZipFileSize();

		return service.send(payloadFile);
	}

}
