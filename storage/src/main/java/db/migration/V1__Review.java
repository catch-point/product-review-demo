package db.migration;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/**
 * Applies schema.sql verbatim. The script opens its own transaction (BEGIN)
 * but does not end it, so Flyway runs it outside one and this commits it
 * straight after: the schema is in place before Flyway records the migration,
 * rather than whenever the connection's open transaction happened to end. The
 * checksum is the script's, so editing an applied schema.sql fails validation
 * instead of being silently ignored; later changes belong in V2__... migrations
 * (and adding the missing COMMIT to the script would be such an edit).
 */
public class V1__Review extends BaseJavaMigration {

	private static final String SCRIPT = "/db/schema.sql";

	@Override
	public boolean canExecuteInTransaction() {
		return false;
	}

	@Override
	public Integer getChecksum() {
		CRC32 crc = new CRC32();
		crc.update(script().getBytes(StandardCharsets.UTF_8));
		return (int) crc.getValue();
	}

	@Override
	public void migrate(Context context) throws Exception {
		try (var statement = context.getConnection().createStatement()) {
			statement.execute(script());
			statement.execute("COMMIT");
		}
	}

	private static String script() {
		try (InputStream in = V1__Review.class.getResourceAsStream(SCRIPT)) {
			if (in == null) {
				throw new IllegalStateException(SCRIPT + " is not on the classpath");
			}
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

}
