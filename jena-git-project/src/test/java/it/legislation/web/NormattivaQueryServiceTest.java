package it.legislation.web;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NormattivaQueryServiceTest {

    @TempDir
    Path tempDir;

    private NormattivaQueryService service;

    @AfterEach
    void closeService() {
        if (service != null) {
            service.closeForTests();
        }
    }

    @Test
    void listsModificationRelationshipsFromTurtle() throws Exception {
        Path turtle = tempDir.resolve("normattiva_modifications.ttl");
        Files.writeString(turtle, """
                @prefix eli: <http://data.europa.eu/eli/ontology#> .
                @prefix ilg: <http://example.org/italian-legislation/ontology#> .

                <http://www.gazzettaufficiale.it/eli/id/2025/04/29/25G00068/sg>
                  ilg:modifies <http://www.gazzettaufficiale.it/eli/id/2025/02/28/25G00030/sg> ;
                  eli:commences <http://www.gazzettaufficiale.it/eli/id/2025/02/28/25G00030/sg> .

                <http://www.gazzettaufficiale.it/eli/id/2025/04/16/25A02362/sg>
                  ilg:modifies <http://www.gazzettaufficiale.it/eli/id/2025/03/26/25G00044/sg> .
                """, StandardCharsets.UTF_8);

        service = new NormattivaQueryService(List.of(turtle));

        List<NormattivaModificationSummary> rows = service.listModifications(10);

        assertEquals(2, rows.size());
        assertEquals("25A02362", rows.get(0).sourceLocalId());
        assertEquals("modifies", rows.get(0).relationship());
        assertEquals("25G00044", rows.get(0).targetLocalId());
        assertEquals("25G00068", rows.get(1).sourceLocalId());
        assertEquals("conversion", rows.get(1).relationship());
        assertEquals("25G00030", rows.get(1).targetLocalId());
    }

}
