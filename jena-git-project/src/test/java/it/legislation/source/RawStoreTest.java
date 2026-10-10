package it.legislation.source;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RawStoreTest {

    @TempDir
    Path root;

    @Test
    void savesBytesWithChecksumAndManifest() throws IOException {
        RawStore store = new RawStore(root);
        byte[] content = "<akomaNtoso/>".getBytes(StandardCharsets.UTF_8);

        RawStore.SavedFile first = store.save("act/v1.xml", content, "export test");
        RawStore.SavedFile again = store.save("act/v1.xml", content, "export test");
        RawStore.SavedFile changed = store.save("act/v1.xml", "<akomaNtoso>x</akomaNtoso>".getBytes(StandardCharsets.UTF_8), "export test");

        assertEquals("NEW", first.status());
        assertEquals("UNCHANGED", again.status());
        assertEquals("CHANGED", changed.status());
        assertEquals(RawStore.sha256(content), first.sha256());
        assertEquals(64, first.sha256().length());
        List<String> manifest = Files.readAllLines(root.resolve(RawStore.MANIFEST));
        assertEquals(4, manifest.size());
        assertTrue(manifest.get(1).contains("act/v1.xml"));
    }

    @Test
    void refusesPathsOutsideTheStore() {
        RawStore store = new RawStore(root);
        try {
            store.save("../escape.txt", new byte[] {1}, "test");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("outside"));
            return;
        }
        throw new AssertionError("Expected refusal");
    }
}
