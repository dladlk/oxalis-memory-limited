package network.oxalis.rd.memory;

import org.slf4j.Logger;

public class TestUtil {

	public static void logMaxMemory(Logger logger) {
		String javaVersion = System.getProperty("java.version");
		logger.info("**** RUNNING TEST WITH {} MB max memory on java {} ******", Runtime.getRuntime().maxMemory() / 1024.0 / 1024.0, javaVersion);
	}

}
