package ca.leighnet.reviews.storage;

import org.springframework.boot.SpringApplication;

/**
 * Runs the service against a throwaway PostgreSQL container:
 * {@code ./mvnw spring-boot:test-run}.
 */
public class TestStorageApplication {

	public static void main(String[] args) {
		SpringApplication.from(StorageApplication::main)
			.with(TestcontainersConfiguration.class)
			.run("--reviews.security.jwt-secret=a-test-jwt-secret-for-the-storage-application");
	}

}
