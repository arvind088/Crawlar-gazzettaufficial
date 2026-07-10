package it.legislation.crawler;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NormattivaGazzettaMetadataBackfillRunnerTest {

    @TempDir
    Path tempDir;

    @Test
    void extractsOnlyGazzettaEliUrisFromNormattivaRelationships() throws Exception {
        Path turtle = tempDir.resolve("normattiva_modifications.ttl");
        Files.writeString(turtle, """
                @prefix eli: <http://data.europa.eu/eli/ontology#> .
                @prefix ilg: <http://example.org/italian-legislation/ontology#> .

                <http://www.gazzettaufficiale.it/eli/id/2025/03/01/25G00028/sg>
                  a eli:LegalResource ;
                  ilg:modifies <http://www.gazzettaufficiale.it/eli/id/2025/01/16/25G00006/sg> .

                <https://www.normattiva.it/uri-res/N2Ls?urn:nir:stato:legge:2026-05-13;79>
                  ilg:modifies <https://www.normattiva.it/uri-res/N2Ls?urn:nir:stato:decreto.legge:2026-03-18;33> .
                """, StandardCharsets.UTF_8);

        List<String> uris = NormattivaGazzettaMetadataBackfillRunner.readGazzettaUris(List.of(turtle));

        assertEquals(List.of(
                "http://www.gazzettaufficiale.it/eli/id/2025/03/01/25G00028/sg",
                "http://www.gazzettaufficiale.it/eli/id/2025/01/16/25G00006/sg"
        ), uris);
    }
}
