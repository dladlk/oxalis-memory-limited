package network.oxalis.rd.memory;

import org.slf4j.Logger;

public class TestUtil {

	public static double logMaxMemory(Logger logger) {
		String javaVersion = System.getProperty("java.version");
		double maxMemory = Runtime.getRuntime().maxMemory() / 1024.0 / 1024.0;
		logger.info("**** RUNNING TEST WITH {} MB max memory on java {} ******", maxMemory, javaVersion);
		return maxMemory;
	}

}
