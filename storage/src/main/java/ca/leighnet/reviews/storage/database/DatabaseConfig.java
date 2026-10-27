package ca.leighnet.reviews.storage.database;

import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The service's database role, set up after every migration (ServiceRole):
 * the role and password the service connects with, spring.datasource.*.
 * Migrations connect as the schema's owner instead, spring.flyway.user.
 */
@Configuration(proxyBeanMethods = false)
public class DatabaseConfig {

	@Bean
	ServiceRole serviceRole(JdbcConnectionDetails connection) {
		return new ServiceRole(connection.getUsername(), connection.getPassword());
	}

}
