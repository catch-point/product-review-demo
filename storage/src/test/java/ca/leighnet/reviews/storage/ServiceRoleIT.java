package ca.leighnet.reviews.storage;

import java.sql.DriverManager;

import ca.leighnet.reviews.storage.database.ServiceRole;
import ca.leighnet.reviews.storage.database.ServiceRoleCheck;
import org.flywaydb.core.api.callback.Context;
import org.flywaydb.core.api.callback.Event;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;

/**
 * The service's database role (database.ServiceRole) reads and writes rows and
 * can do nothing else: it cannot change the schema, write or skip the journal,
 * or read Flyway's history. The tests run as the service does. Neither the
 * service nor a migration accepts the schema's owner (or a superuser) in its
 * place.
 */
@SpringBootTest(properties = "reviews.security.jwt-secret=test-secret-0123456789abcdef0123456789abcdef")
@Import(TestcontainersConfiguration.class)
class ServiceRoleIT {

	@Autowired
	private PostgreSQLContainer postgres;

	@Autowired
	private JdbcClient jdbc;

	@Autowired
	private TransactionTemplate transactions;

	@Test
	void theServiceConnectsAsItsOwnRole() {
		assertThat(jdbc.sql("SELECT current_user").query(String.class).single())
			.isEqualTo(TestcontainersConfiguration.SERVICE_USER);
		assertThat(jdbc.sql("""
				SELECT rolsuper OR rolcreaterole OR rolcreatedb OR rolreplication OR rolbypassrls
				FROM pg_roles WHERE rolname = current_user
				""").query(Boolean.class).single()).isFalse();
	}

	@Test
	void theSchemaIsOwnedByARoleThatIsNotASuperuser() {
		assertThat(jdbc.sql("""
				SELECT DISTINCT r.rolname, r.rolsuper FROM pg_class c JOIN pg_roles r ON r.oid = c.relowner
				WHERE c.relnamespace = 'public'::regnamespace
				""").query((rs, n) -> rs.getString(1) + " " + rs.getBoolean(2)).list())
			.containsExactly(TestcontainersConfiguration.OWNER_USER + " false");
	}

	@Test
	void theServiceReadsAndWritesRows() {
		assertThat(jdbc.sql("SELECT count(*) FROM category").query(Long.class).single()).isPositive();
		assertThat(jdbc.sql("SELECT count(*) FROM journal").query(Long.class).single()).isNotNegative();
	}

	@Test
	void theServiceCannotChangeTheSchema() {
		assertThatThrownBy(() -> jdbc.sql("CREATE TABLE intruder (id int)").update())
			.isInstanceOf(DataAccessException.class)
			.rootCause().hasMessageContaining("permission denied");
		assertThatThrownBy(() -> jdbc.sql("ALTER TABLE product DISABLE TRIGGER ALL").update())
			.isInstanceOf(DataAccessException.class)
			.rootCause().hasMessageContaining("must be owner");
		assertThatThrownBy(() -> jdbc.sql("DROP TABLE review").update())
			.isInstanceOf(DataAccessException.class)
			.rootCause().hasMessageContaining("must be owner");
	}

	@Test
	void theServiceCannotWriteTheJournal() {
		for (String sql : new String[] { "UPDATE journal SET actor = 'someone else'", "DELETE FROM journal",
				"TRUNCATE journal", """
						INSERT INTO journal (op, table_name, pk, new_row)
						VALUES ('INSERT', 'person', '{}', '{}')
						""" }) {
			assertThatThrownBy(() -> jdbc.sql(sql).update()).as(sql)
				.isInstanceOf(DataAccessException.class)
				.rootCause().hasMessageContaining("permission denied");
		}
	}

	@Test
	void theServiceCannotSkipTheJournal() {
		transactions.executeWithoutResult(status -> {
			jdbc.sql("SET LOCAL app.skip_journal = 'on'").update();
			jdbc.sql("SET LOCAL app.actor = 'test:skip'").update();
			jdbc.sql("""
					INSERT INTO category (id, term, name)
					VALUES ('https://example.com/categories/unjournaled', 'Unjournaled', 'Unjournaled category')
					""").update();
		});
		assertThat(jdbc.sql("SELECT count(*) FROM journal WHERE actor = 'test:skip' AND table_name = 'category'")
			.query(Long.class)
			.single()).isEqualTo(1);
	}

	@Test
	void theServiceCannotReadTheMigrationHistory() {
		assertThatThrownBy(() -> jdbc.sql("SELECT count(*) FROM flyway_schema_history").query(Long.class).single())
			.isInstanceOf(DataAccessException.class)
			.rootCause().hasMessageContaining("permission denied");
	}

	@Test
	void theServiceRefusesToRunAsTheSchemasOwnerOrASuperuser() {
		new ServiceRoleCheck(jdbc).check();
		assertThatIllegalStateException()
			.isThrownBy(() -> new ServiceRoleCheck(
					client(TestcontainersConfiguration.OWNER_USER, TestcontainersConfiguration.OWNER_PASSWORD))
				.check())
			.withMessageContaining("owns the schema's journal");
		assertThatIllegalStateException()
			.isThrownBy(() -> new ServiceRoleCheck(client(postgres.getUsername(), postgres.getPassword())).check())
			.withMessageContaining("which has SUPERUSER");
	}

	@Test
	void migratingRefusesToMakeTheOwnerTheServicesRole() throws Exception {
		try (var owner = DriverManager.getConnection(postgres.getJdbcUrl(), TestcontainersConfiguration.OWNER_USER,
				TestcontainersConfiguration.OWNER_PASSWORD)) {
			Context context = mock(Context.class, RETURNS_DEEP_STUBS);
			given(context.getConnection()).willReturn(owner);
			given(context.getConfiguration().getTable()).willReturn("flyway_schema_history");

			assertThatIllegalStateException()
				.isThrownBy(() -> new ServiceRole(TestcontainersConfiguration.OWNER_USER, "a-password")
					.handle(Event.AFTER_MIGRATE, context))
				.withMessageContaining("runs the migrations and owns the schema");
			assertThatIllegalStateException()
				.isThrownBy(() -> new ServiceRole(" ", "a-password").handle(Event.AFTER_MIGRATE, context))
				.withMessageContaining("No service database role");
		}
	}

	private JdbcClient client(String username, String password) {
		return JdbcClient.create(new DriverManagerDataSource(postgres.getJdbcUrl(), username, password));
	}

}
