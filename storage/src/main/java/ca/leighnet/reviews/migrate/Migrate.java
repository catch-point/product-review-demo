package ca.leighnet.reviews.migrate;

import ca.leighnet.reviews.storage.database.DatabaseConfig;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.context.annotation.Import;

/**
 * Migrates the database and sets up the service's role, then exits: the
 * storage-migrate container, which runs before the service with the schema
 * owner's password (spring.flyway.user and spring.flyway.password). The
 * service itself then runs with spring.flyway.enabled=false, so it never
 * holds that password. Nothing but the data source and Flyway is started:
 * this package is outside the service's, so its component scan never finds it.
 *
 * <pre>java -cp 'BOOT-INF/classes:BOOT-INF/lib/*' ca.leighnet.reviews.migrate.Migrate</pre>
 */
@SpringBootConfiguration
@ImportAutoConfiguration({ DataSourceAutoConfiguration.class, FlywayAutoConfiguration.class })
@Import(DatabaseConfig.class)
public class Migrate {

	public static void main(String[] args) {
		SpringApplication application = new SpringApplication(Migrate.class);
		application.setWebApplicationType(WebApplicationType.NONE);
		System.exit(SpringApplication.exit(application.run(args)));
	}

}
