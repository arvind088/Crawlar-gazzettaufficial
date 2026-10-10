package it.legislation.store;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Directories for TDB2 stores used by tests.
 *
 * <p>TDB2 memory-maps its index files, and on Windows a mapped file cannot be
 * deleted until the JVM releases the mapping, which does not reliably happen at
 * {@code close()}. Stores therefore cannot be deleted by the test that made
 * them. Instead, the first call in a test run deletes the stores left by
 * earlier runs (their JVM has ended, so the files are free). Without this,
 * every {@code mvn test} left several gigabytes under {@code target/test-stores}.
 */
public final class TestStores {

    private static final Path ROOT = Path.of("target", "test-stores");
    private static boolean cleaned;

    private TestStores() {
    }

    /** A new, empty store directory under {@code target/test-stores}. */
    public static synchronized Path newDirectory() throws IOException {
        if (!cleaned) {
            deleteLeftovers();
            cleaned = true;
        }
        Path directory = ROOT.resolve(UUID.randomUUID().toString());
        Files.createDirectories(directory);
        return directory;
    }

    private static void deleteLeftovers() {
        if (!Files.isDirectory(ROOT)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(ROOT)) {
            walk.sorted(Comparator.reverseOrder())
                    .filter(p -> !p.equals(ROOT))
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException ignored) {
                            // Still mapped by another JVM (e.g. a running app); left for next time.
                        }
                    });
        } catch (IOException ignored) {
            // Cleaning is best effort; tests do not depend on it.
        }
    }
}
