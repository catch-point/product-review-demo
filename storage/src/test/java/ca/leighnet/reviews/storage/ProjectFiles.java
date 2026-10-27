package ca.leighnet.reviews.storage;

import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The storage project's own files (db/owner.sql, minio/init.sh), which the
 * containers are given. They are found without relying on the working
 * directory, so the tests run the same from Maven, from CI and from an IDE
 * with the repository root open: the test classes' own location is a few
 * levels below the project's directory, which is walked up to look for the
 * file. The reviews.test.projectDir property wins over that, and the working
 * directory is the last try.
 */
final class ProjectFiles {

	/** The project's directory, for where the test classes' location does not lead to it. */
	static final String PROJECT_DIR_PROPERTY = "reviews.test.projectDir";

	private ProjectFiles() {
	}

	/** The project's file with the given path, relative to the project's directory. */
	static Path of(String relative) {
		for (Path start : starts()) {
			for (Path directory = start; directory != null; directory = directory.getParent()) {
				Path candidate = directory.resolve(relative);
				if (Files.isRegularFile(candidate)) {
					return candidate.toAbsolutePath();
				}
			}
		}
		throw new IllegalStateException("Cannot find " + relative + " in the storage project. "
				+ "Set -D" + PROJECT_DIR_PROPERTY + " to the project's directory.");
	}

	/** Where to look for the project: the property, the test classes' location, the working directory. */
	private static List<Path> starts() {
		List<Path> starts = new ArrayList<>();
		String given = System.getProperty(PROJECT_DIR_PROPERTY);
		if (given != null && !given.isBlank()) {
			starts.add(Path.of(given).toAbsolutePath());
		}
		Path classes = classesDirectory();
		if (classes != null) {
			starts.add(classes);
		}
		starts.add(Path.of("").toAbsolutePath());
		return starts;
	}

	/** Where the test classes were loaded from: the project's target/test-classes. */
	private static Path classesDirectory() {
		try {
			var source = ProjectFiles.class.getProtectionDomain().getCodeSource();
			if (source == null) {
				return null;
			}
			return Path.of(source.getLocation().toURI());
		}
		catch (URISyntaxException | IllegalArgumentException ex) {
			return null;
		}
	}

}
