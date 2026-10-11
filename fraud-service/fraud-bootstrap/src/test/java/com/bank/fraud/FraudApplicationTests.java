package com.bank.fraud;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;

import javax.sql.DataSource;

@SpringBootTest
class FraudApplicationTests {

	@Autowired
	private Environment environment;

	@Autowired
	private ApplicationContext context;

	@Test
	void contextLoads() {
		assertThat(context).isNotNull();
	}

	@Test
	void serverPortIs8081() {
		assertThat(environment.getProperty("server.port")).isEqualTo("8081");
	}

	@Test
	void applicationNameIsFraud() {
		assertThat(environment.getProperty("spring.application.name")).isEqualTo("fraud");
	}

	@Test
	void noDataSourceBeanIsPresent() {
		assertThat(context.getBeanNamesForType(DataSource.class)).isEmpty();
	}

}
