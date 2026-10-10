package it.legislation.source;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;

/**
 * Keeps every downloaded source file exactly as received, with its SHA-256,
 * and appends one line per file to {@code manifest.tsv}. This is the
 * provenance trail: any RDF built later can point back to the exact bytes.
 */
public class RawStore {

    public static final String MANIFEST = "manifest.tsv";
    private static final String HEADER = "fetched_at\tfile\tsha256\tbytes\tstatus\tsource\n";

    private final Path root;

    public RawStore(Path root) {
        this.root = root;
    }

    /** Result of {@link #save}: {@code status} is NEW, CHANGED or UNCHANGED. */
    public record SavedFile(Path path, String sha256, long bytes, String status) {}

    public SavedFile save(String relativeName, byte[] content, String source) throws IOException {
        Path target = root.resolve(relativeName).normalize();
        if (!target.startsWith(root.normalize())) {
            throw new IOException("Refusing to write outside the raw store: " + relativeName);
        }
        Files.createDirectories(target.getParent());
        String sha = sha256(content);
        String status;
        if (Files.exists(target)) {
            status = sha.equals(sha256(Files.readAllBytes(target))) ? "UNCHANGED" : "CHANGED";
        } else {
            status = "NEW";
        }
        if (!"UNCHANGED".equals(status)) {
            Files.write(target, content);
        }
        appendManifest(root.relativize(target).toString().replace('\\', '/'), sha, content.length, status, source);
        return new SavedFile(target, sha, content.length, status);
    }

    public static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 not available", exception);
        }
    }

    private void appendManifest(String file, String sha, long bytes, String status, String source) throws IOException {
        Path manifest = root.resolve(MANIFEST);
        Files.createDirectories(root);
        if (!Files.exists(manifest)) {
            Files.writeString(manifest, HEADER, StandardCharsets.UTF_8);
        }
        String line = String.join("\t",
                OffsetDateTime.now(ZoneOffset.UTC).withNano(0).toString(),
                file, sha, Long.toString(bytes), status, clean(source)) + "\n";
        Files.writeString(manifest, line, StandardCharsets.UTF_8, StandardOpenOption.APPEND);
    }

    private static String clean(String value) {
        return value == null ? "" : value.replaceAll("[\\t\\r\\n]+", " ");
    }
}
