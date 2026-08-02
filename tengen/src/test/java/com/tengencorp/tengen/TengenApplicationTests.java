package com.tengencorp.tengen;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest
@TestPropertySource(properties = {
	"tengen.retention.enabled=false",
	"tengen.webhook.worker.enabled=false",
	"admin.password=integration-admin-password",
	"jwt.secret=integration-test-secret-with-more-than-32-bytes",
	"tengen.webhook.worker.signing-secret=integration-test-signing-secret-with-more-than-32-bytes"
})
class TengenApplicationTests {

	private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine")
		.withDatabaseName("test")
		.withUsername("tengen")
		.withPassword("tengen");

	@DynamicPropertySource
	static void registerDatabase(DynamicPropertyRegistry registry) {
		if (!POSTGRES.isRunning()) {
			POSTGRES.start();
		}
		registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
		registry.add("spring.datasource.username", POSTGRES::getUsername);
		registry.add("spring.datasource.password", POSTGRES::getPassword);
	}

	@AfterAll
	static void stopDatabase() {
		if (POSTGRES.isRunning()) {
			POSTGRES.stop();
		}
	}

	@Test
	void contextLoads() {
	}

}
