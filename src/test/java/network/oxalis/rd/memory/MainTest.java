package network.oxalis.rd.memory;

import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

class MainTest {

	private Main main;

	@BeforeClass
	void before() throws Exception {
		main = new Main();
		main.beforeClass();
	}

	@AfterClass
	void after() throws Exception {
		main.afterClass();
	}

	@Test
	void test() throws Exception {
		main.send();
	}

}
