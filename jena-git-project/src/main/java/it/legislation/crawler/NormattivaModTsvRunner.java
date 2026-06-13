package it.legislation.crawler;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.apache.jena.rdf.model.Model;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.riot.RDFFormat;

public class NormattivaModTsvRunner {

    private static final Path DEFAULT_INPUT = Path.of("..", "context", "TSV_MOD_2025-aggiornamenti.tsv");
    private static final Path DEFAULT_OUTPUT = Path.of("data", "rdf", "normattiva_modifications.ttl");

    public static void main(String[] args) throws IOException {
        Path input = args.length > 0 ? Path.of(args[0]) : DEFAULT_INPUT;
        Path output = args.length > 1 ? Path.of(args[1]) : DEFAULT_OUTPUT;

        List<CleanModificationRecord> records = new TsvModParser().parse(input);
        Model model = new ModificationRdfModelBuilder().build(records);

        Path parent = output.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        try (OutputStream outputStream = Files.newOutputStream(output)) {
            RDFDataMgr.write(outputStream, model, RDFFormat.TURTLE_PRETTY);
        }

        System.out.println("Read Normattiva modification rows: " + records.size());
        System.out.println("Generated triples: " + model.size());
        System.out.println("Wrote RDF to: " + output.toAbsolutePath().normalize());
    }
}
