package ca.leighnet.reviews.admin;

import de.codecentric.boot.admin.server.config.EnableAdminServer;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Spring Boot Admin: a web UI over the storage service's actuator endpoints
 * (health, metrics, loggers, environment, thread dumps, and so on).
 */
@SpringBootApplication
@EnableAdminServer
public class AdminApplication {

	public static void main(String[] args) {
		SpringApplication.run(AdminApplication.class, args);
	}

}
