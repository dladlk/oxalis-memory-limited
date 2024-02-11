package network.oxalis.rd.memory;

import java.io.File;

import org.slf4j.Logger;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class TestUtil {

	public static void logMaxMemory(Logger logger) {
		logger.info("**** RUNNING TEST WITH {} MB max memory ******", Runtime.getRuntime().maxMemory() / 1024.0 / 1024.0);
	}
	
	public static File getBigTestFile() {
		String bigFilePath = "../oxalis-as4/src/test/resources/sbd-test-file-10mb.xml";
		File bigFile = new File(bigFilePath);
		if (!bigFile.exists()) {
			log.warn("Cannot find for testZipBombDetectedFix a file " + bigFilePath + ", skip test");
			return null;
		}
		return bigFile;
	}
	
}
