package ca.leighnet.reviews.storage.database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;

import org.flywaydb.core.api.callback.Callback;
import org.flywaydb.core.api.callback.Context;
import org.flywaydb.core.api.callback.Event;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * After every migration, makes the service's database role what the service
 * may do and no more. Migrations run as the role that owns the schema (it
 * creates and alters the tables, and owns the journal); the service connects
 * as this one, which can log in and read and write the rows, and nothing else:
 * <ul>
 * <li>it cannot create, alter or drop anything, or disable a trigger, so every
 * change it makes is journaled;</li>
 * <li>it may read the journal but not write it: journal_write() inserts as
 * the journal's owner;</li>
 * <li>it cannot touch Flyway's history;</li>
 * <li>it is not a superuser and cannot create roles or databases.</li>
 * </ul>
 * The owner is not a superuser either (storage/db/owner.sql): it may create
 * this role and set its password, but not its SUPERUSER, CREATEDB,
 * REPLICATION or BYPASSRLS attributes, so those are checked, not set. The
 * role is created if it is missing, and its password set to the configured
 * one on every run, so changing it in .env and migrating again rotates it.
 * The grants are made afresh each time, so tables added by later migrations
 * are covered without migrations having to name the role. Migrating fails
 * when no service role is configured, or when it is the migrating role
 * itself. The service checks the role it is connected as too, wherever it
 * runs ({@link ServiceRoleCheck}).
 */
public class ServiceRole implements Callback {

	private static final Logger log = LoggerFactory.getLogger(ServiceRole.class);

	private final String username;

	private final String password;

	public ServiceRole(String username, String password) {
		this.username = username;
		this.password = password;
	}

	@Override
	public boolean supports(Event event, Context context) {
		return event == Event.AFTER_MIGRATE;
	}

	@Override
	public boolean canHandleInTransaction(Event event, Context context) {
		return true;
	}

	@Override
	public void handle(Event event, Context context) {
		try {
			apply(context.getConnection(), context.getConfiguration().getTable());
		}
		catch (SQLException ex) {
			throw new IllegalStateException("Could not set up the service's database role " + username, ex);
		}
	}

	@Override
	public String getCallbackName() {
		return "service role";
	}

	private void apply(Connection connection, String historyTable) throws SQLException {
		// Either would leave the service with the schema owner's rights: to
		// change the schema, and to disable or rewrite the journal.
		if (username == null || username.isBlank()) {
			throw new IllegalStateException("No service database role is configured (spring.datasource.username):"
					+ " the service must connect as a role of its own, not as the schema's owner");
		}
		String migrator = query(connection, "SELECT current_user");
		if (username.equals(migrator)) {
			throw new IllegalStateException("The service would connect as " + username
					+ ", the role that runs the migrations and owns the schema; give it a role of its own"
					+ " (spring.datasource.username)");
		}
		if (password == null || password.isEmpty()) {
			throw new IllegalStateException("The service database role " + username + " needs a password");
		}
		boolean exists = query(connection, "SELECT rolname FROM pg_roles WHERE rolname = ?", username) != null;
		if (!exists) {
			execute(connection, "SELECT format('CREATE ROLE %I', ?)", username);
			log.info("Created the service database role {}", username);
		}
		execute(connection, "SELECT format('ALTER ROLE %I LOGIN NOCREATEROLE NOINHERIT PASSWORD %L', ?, ?)", username,
				password);
		String privileged = query(connection, """
				SELECT rolname FROM pg_roles
				WHERE rolname = ? AND (rolsuper OR rolcreatedb OR rolreplication OR rolbypassrls)
				""", username);
		if (privileged != null) {
			throw new IllegalStateException("The service database role " + username
					+ " is a superuser, or may create databases, replicate or bypass row security; "
					+ "a superuser must take that away (ALTER ROLE)");
		}
		String schema = query(connection, "SELECT current_schema()");
		for (String grant : new String[] {
				// Start from nothing, then grant only what the service uses.
				"REVOKE ALL ON ALL TABLES IN SCHEMA %1$I FROM %2$I",
				"REVOKE ALL ON ALL SEQUENCES IN SCHEMA %1$I FROM %2$I",
				"REVOKE CREATE ON SCHEMA %1$I FROM %2$I",
				"GRANT USAGE ON SCHEMA %1$I TO %2$I",
				"GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA %1$I TO %2$I",
				"GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA %1$I TO %2$I",
				"REVOKE INSERT, UPDATE, DELETE ON %1$I.journal FROM %2$I",
				"REVOKE ALL ON %1$I.%3$I FROM %2$I" }) {
			execute(connection, "SELECT format('" + grant + "', ?, ?, ?)", schema, username, historyTable);
		}
		execute(connection, "SELECT format('GRANT CONNECT ON DATABASE %I TO %I', current_database(), ?)", username);
	}

	/** Runs the statement that {@code sql} (a format() with its arguments) produces, quoting them safely. */
	private static void execute(Connection connection, String sql, String... arguments) throws SQLException {
		String statement = query(connection, sql, arguments);
		try (Statement s = connection.createStatement()) {
			s.execute(statement);
		}
	}

	private static String query(Connection connection, String sql, String... arguments) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement(sql)) {
			for (int i = 0; i < arguments.length; i++) {
				statement.setString(i + 1, arguments[i]);
			}
			try (var result = statement.executeQuery()) {
				return result.next() ? result.getString(1) : null;
			}
		}
	}

}
