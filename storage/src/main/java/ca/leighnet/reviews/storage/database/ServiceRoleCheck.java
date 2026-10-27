package ca.leighnet.reviews.storage.database;

import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Refuses to start when the service is connected as more than its own role
 * ({@link ServiceRole}): as a superuser, or a role that may create roles or
 * databases, replicate or bypass row security; or as the journal's owner, or
 * a member of it, which could alter the schema and disable the journal's
 * guards. ServiceRole sets the role up when migrations run; this checks the
 * connection the service got, including where it does not migrate itself
 * (in Docker, storage-migrate does). It runs once every bean is ready, after
 * the migrations, and before the service takes a request.
 */
@Component
public class ServiceRoleCheck implements SmartInitializingSingleton {

	private final JdbcClient jdbc;

	public ServiceRoleCheck(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public void afterSingletonsInstantiated() {
		check();
	}

	public void check() {
		var role = jdbc.sql("""
				SELECT current_user AS name,
				       concat_ws(', ',
				         CASE WHEN r.rolsuper THEN 'SUPERUSER' END,
				         CASE WHEN r.rolcreaterole THEN 'CREATEROLE' END,
				         CASE WHEN r.rolcreatedb THEN 'CREATEDB' END,
				         CASE WHEN r.rolreplication THEN 'REPLICATION' END,
				         CASE WHEN r.rolbypassrls THEN 'BYPASSRLS' END) AS privileges,
				       j.tableowner AS journal_owner,
				       coalesce(pg_has_role(current_user, j.tableowner, 'MEMBER'), false) AS owns_journal
				FROM pg_roles r
				LEFT JOIN pg_tables j ON j.schemaname = current_schema() AND j.tablename = 'journal'
				WHERE r.rolname = current_user
				""").query((rs, row) -> new Role(rs.getString("name"), rs.getString("privileges"),
				rs.getString("journal_owner"), rs.getBoolean("owns_journal"))).single();
		// A superuser is a member of every role: say what it is first.
		if (role.ownsJournal() && !role.privileges().contains("SUPERUSER")) {
			throw new IllegalStateException("The service is connected to the database as " + role.name()
					+ ", which owns the schema's journal (or is a member of " + role.journalOwner()
					+ ") and so could change the schema or skip the journal; connect as the service's own role"
					+ " (spring.datasource.username)");
		}
		if (!role.privileges().isEmpty()) {
			throw new IllegalStateException("The service is connected to the database as " + role.name()
					+ ", which has " + role.privileges() + "; connect as the service's own role"
					+ " (spring.datasource.username), which has none of SUPERUSER, CREATEROLE, CREATEDB,"
					+ " REPLICATION or BYPASSRLS");
		}
	}

	private record Role(String name, String privileges, String journalOwner, boolean ownsJournal) {
	}

}
