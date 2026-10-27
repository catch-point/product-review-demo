package ca.leighnet.reviews.storage;

import org.springframework.boot.SpringApplication;

/**
 * Runs the service against throwaway PostgreSQL and MinIO containers:
 * {@code ./mvnw spring-boot:test-run}, or VS Code's "Storage: Debug
 * (Testcontainers)". Development tokens are signed with JWT_SECRET when it is
 * set (the repository's .env, which the VS Code launch reads), so
 * scripts/dev-token.sh and the seed scripts work against it; otherwise with a
 * fixed test secret.
 */
public class TestStorageApplication {

	private static final String TEST_JWT_SECRET = "a-test-jwt-secret-for-the-storage-application";

	public static void main(String[] args) {
		boolean fromEnv = !System.getenv().getOrDefault("JWT_SECRET", "").isBlank();
		SpringApplication.from(StorageApplication::main)
			.with(TestcontainersConfiguration.class)
			.run(fromEnv ? new String[0] : new String[] { "--reviews.security.jwt-secret=" + TEST_JWT_SECRET });
	}

}
