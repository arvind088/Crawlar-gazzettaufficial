package it.legislation.crawler;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TsvModParserTest {

    @TempDir
    Path tempDir;

    @Test
    void parsesDeduplicatesAndClassifiesModificationRows() throws Exception {
        Path tsv = tempDir.resolve("mods.tsv");
        Files.writeString(tsv, String.join(System.lineSeparator(),
                "eliSubject\teliObject\tmodtext",
                "http://example.org/a\thttp://example.org/b\tha disposto l'abrogazione dell'intero provvedimento.",
                "http://example.org/a\thttp://example.org/b\tha disposto l'abrogazione dell'intero provvedimento.",
                "http://example.org/c\thttp://example.org/d\tdi conversione ha disposto la modifica dell'art. 1.",
                "http://example.org/e\thttp://example.org/f\trelativo alla rubrica."
        ), StandardCharsets.UTF_8);

        List<CleanModificationRecord> records = new TsvModParser().parse(tsv);

        assertEquals(3, records.size());
        assertEquals(ModificationType.ABROGATION, records.get(0).getModificationType());
        assertEquals(ModificationType.CONVERSION, records.get(1).getModificationType());
        assertEquals(ModificationType.REFERENCE, records.get(2).getModificationType());
    }
}
