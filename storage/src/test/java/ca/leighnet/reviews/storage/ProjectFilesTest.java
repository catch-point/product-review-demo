package ca.leighnet.reviews.storage;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

class ProjectFilesTest {

	@Test
	void findsTheProjectsFilesWithoutTheWorkingDirectory() {
		assertThat(ProjectFiles.of("db/owner.sql").toString()).endsWith("db/owner.sql");
		assertThat(ProjectFiles.of("minio/init.sh").toString()).endsWith("minio/init.sh");
		assertThat(ProjectFiles.of("db/owner.sql")).isRegularFile();
		assertThat(ProjectFiles.of("minio/init.sh")).isRegularFile();
	}

	@Test
	void usesTheDirectoryItIsGiven(@TempDir Path directory) throws Exception {
		Path owner = Files.createDirectories(directory.resolve("db")).resolve("owner.sql");
		Files.writeString(owner, "-- a copy of the script\n");
		String given = System.getProperty(ProjectFiles.PROJECT_DIR_PROPERTY);
		System.setProperty(ProjectFiles.PROJECT_DIR_PROPERTY, directory.toString());
		try {
			assertThat(Files.isSameFile(ProjectFiles.of("db/owner.sql"), owner)).isTrue();
		}
		finally {
			if (given == null) {
				System.clearProperty(ProjectFiles.PROJECT_DIR_PROPERTY);
			}
			else {
				System.setProperty(ProjectFiles.PROJECT_DIR_PROPERTY, given);
			}
		}
	}

	@Test
	void saysWhereToLookWhenTheFileIsNotThere() {
		assertThatExceptionOfType(IllegalStateException.class)
			.isThrownBy(() -> ProjectFiles.of("db/no-such-script.sql"))
			.withMessageContaining("db/no-such-script.sql")
			.withMessageContaining(ProjectFiles.PROJECT_DIR_PROPERTY);
	}

}
