package network.oxalis.rd.memory;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.text.DecimalFormat;
import java.util.Random;

import org.apache.cxf.common.util.Base64OutputStream;
import org.bouncycastle.util.encoders.Base64;

import com.google.common.primitives.Bytes;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class BigTestDocumentBuilder {

	private byte[] original;
	private boolean increaseAttachment = false;
	private double increaseAttachmentExpectedResultSizeMB = -1;
	private boolean increaseAttachmentKeepGenerated = true;

	public BigTestDocumentBuilder(byte[] original) {
		this.original = original;
	}

	public BigTestDocumentBuilder increaseAttachment(boolean increaseAttachment, double increaseAttachmentExpectedResultSizeMB) {
		this.increaseAttachment = increaseAttachment;
		this.increaseAttachmentExpectedResultSizeMB = increaseAttachmentExpectedResultSizeMB;
		return this;
	}

	public File build() throws Exception {
		byte[] template = original;
		File resultFile = null;
		if (increaseAttachment) {
			resultFile = increasePayloadAttachmentToFile(template, increaseAttachmentExpectedResultSizeMB);
		} else {
			File payloadFile = File.createTempFile(this.getClass().getName(), ".xml");
			Files.write(payloadFile.toPath(), template);
			resultFile = payloadFile;
		}
		return resultFile;
	}

	private File increasePayloadAttachmentToFile(byte[] original, double expectedResultMBSize) {
		long lengthKB = (long) (expectedResultMBSize * 0.75 * 1024.0); // 75 MB binary gives 100 MB Base64
		long length = lengthKB * 1024;
		String fileSuffix = df(expectedResultMBSize);
		log.debug("Start to generate {} bytes of random attachment to reach expected final {} MB size", df(length), fileSuffix);
		int bufferSize = 8192 * 100;
		long startWrite = System.currentTimeMillis();
		File file = increasePayloadAttachmentToFile(original, length, bufferSize, fileSuffix);
		log.debug("Generated {} additional bytes to get {} MB of payload in {} ms", df(length), df(expectedResultMBSize), df(System.currentTimeMillis() - startWrite));
		return file;
	}

	private File increasePayloadAttachmentToFile(byte[] original, long length, int bufferSize, String fileSuffix) {
		String markStr = "BASE64VALUE";
		byte[] attachmentMark = Base64.encode(markStr.getBytes(StandardCharsets.UTF_8));
		int start = Bytes.indexOf(original, attachmentMark);
		if (start < 0) {
			throw new RuntimeException("Cannot find base64 encoded mark " + markStr + " in provided array");
		}
		try {
			Path preparedPath = Paths.get(System.getProperty("java.io.tmpdir"), "NemHandel_eDelivery_test_file_size_" + fileSuffix + "_MB.xml");
			File preparedFile = preparedPath.toFile();
			File file = File.createTempFile(Main.class.getName(), ".xml");

			if (this.increaseAttachmentKeepGenerated && preparedFile.exists() && preparedFile.length() > length) {
				long startCopy = System.currentTimeMillis();
				Files.copy(preparedPath, file.toPath(), StandardCopyOption.REPLACE_EXISTING);
				log.info("Found already existing prepared file {}, copied as {} in {} ms", preparedPath.toString(), file.getName(), System.currentTimeMillis() - startCopy);
				return file;
			}

			try (OutputStream fos = new BufferedOutputStream(Files.newOutputStream(file.toPath()), bufferSize)) {
				fos.write(original, 0, start);
				fos.flush();
				try (OutputStream base4Encoder = new Base64OutputStream(fos, false) {
					@Override
					public void close() throws IOException {
					}

				}) {
					Random r = new Random();
					byte[] buffer = new byte[bufferSize];
					long count = 0;
					while (count < length) {
						if (length - count < bufferSize) {
							buffer = new byte[(int) (length - count)];
						}
						r.nextBytes(buffer);

						base4Encoder.write(buffer, 0, buffer.length);
						count += buffer.length;
					}
				}
				fos.write(original, start + attachmentMark.length, original.length - start - attachmentMark.length);
			}

			if (this.increaseAttachmentKeepGenerated) {
				long startCopy = System.currentTimeMillis();
				Files.copy(file.toPath(), preparedPath, StandardCopyOption.REPLACE_EXISTING);
				log.info("Copied generated file {} as {} for future tests in {} ms", file.getName(), preparedPath.toString(), System.currentTimeMillis() - startCopy);
				return file;
			}

			return file;
		} catch (Exception e) {
			throw new RuntimeException("Failed to increase payload attachment", e);
		}
	}

	public static String df(long d) {
		return String.format("%,d", d).replace(',', '.');
	}

	public static String df(double d) {
		return DF.format(d).replace(',', '.');
	}

	private static final DecimalFormat DF = new DecimalFormat("0.##");

}
