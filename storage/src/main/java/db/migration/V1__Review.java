package db.migration;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/**
 * Applies schema.sql verbatim. The script is its own transaction (BEGIN ...
 * COMMIT), so Flyway runs it outside one: the schema is committed before
 * Flyway records the migration. The checksum is the script's, so editing an
 * applied schema.sql fails validation instead of being silently ignored;
 * later changes belong in db/migration/V2__... and onward.
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
