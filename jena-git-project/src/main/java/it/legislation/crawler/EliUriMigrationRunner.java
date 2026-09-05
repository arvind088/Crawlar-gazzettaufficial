package it.legislation.crawler;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.Statement;
import org.apache.jena.rdf.model.StmtIterator;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.riot.RDFFormat;

import it.legislation.eli.EliUriService;

/**
 * Rewrites already-collected Turtle so that resources are identified on our own
 * domain (CONTEXT.md constraint 3), keeping an {@code owl:sameAs} link to the
 * Gazzetta Ufficiale identifier they were crawled from.
 *
 * <p>This exists so the constraint can be met without re-crawling. Only ELI
 * identifiers are rewritten: vocabulary terms, language authorities and source
 * links keep their own URIs.
 *
 * <pre>
 * mvn -B "-Dexec.mainClass=it.legislation.crawler.EliUriMigrationRunner" exec:java
 * mvn -B "-Dexec.mainClass=it.legislation.crawler.EliUriMigrationRunner" \
 *     "-Dexec.args=--dry-run" exec:java
 * </pre>
 *
 * <p>Each rewritten file is backed up next to the original as {@code *.ttl.bak}
 * before it is replaced.
 */
public final class EliUriMigrationRunner {

    private static final String OWL_SAME_AS = "http://www.w3.org/2002/07/owl#sameAs";

    private static final List<Path> DEFAULT_INPUTS = List.of(
            Path.of("data", "rdf", "gazzetta_metadata_delta.ttl"),
            Path.of("data", "rdf", "normattiva_modifications.ttl"),
            Path.of("data", "rdf", "normattiva_modifications_auto.ttl"),
            Path.of("data", "rdf", "normattiva_multiversion_sample.ttl")
    );

    private EliUriMigrationRunner() {
    }

    public static void main(String[] args) throws IOException {
        boolean dryRun = List.of(args).contains("--dry-run");
        EliUriService uriService = new EliUriService(
                System.getenv().getOrDefault("LEGAL_ELI_BASE_URI", "https://osservatorio-eli.example.it"));

        List<Path> inputs = new ArrayList<>();
        for (String argument : args) {
            if (!argument.startsWith("--")) {
                inputs.add(Path.of(argument));
            }
        }
        if (inputs.isEmpty()) {
            inputs.addAll(DEFAULT_INPUTS);
        }

        System.out.println("Base URI: " + uriService.baseUri());
        for (Path input : inputs) {
            if (!Files.exists(input)) {
                System.out.println("Skipped (missing): " + input);
                continue;
            }
            Result result = migrateFile(input, uriService, dryRun);
            System.out.println((dryRun ? "Would rewrite " : "Rewrote ")
                    + input
                    + " - subjects re-hosted: " + result.rewrittenResources()
                    + ", sameAs links added: " + result.sameAsAdded()
                    + ", triples: " + result.triples());
        }
        if (dryRun) {
            System.out.println("Dry run only. No file was changed.");
        }
    }

    static Result migrateFile(Path input, EliUriService uriService, boolean dryRun) throws IOException {
        Model original = ModelFactory.createDefaultModel();
        try (InputStream inputStream = Files.newInputStream(input)) {
            RDFDataMgr.read(original, inputStream, Lang.TURTLE);
        }

        Result result = migrate(original, uriService);
        if (dryRun) {
            return result;
        }

        Path backup = input.resolveSibling(input.getFileName() + ".bak");
        Files.copy(input, backup, StandardCopyOption.REPLACE_EXISTING);
        try (OutputStream outputStream = Files.newOutputStream(input)) {
            RDFDataMgr.write(outputStream, result.model(), RDFFormat.TURTLE_PRETTY);
        }
        return result;
    }

    /**
     * Produces a model in which every ELI identifier is re-hosted on our domain.
     * Subjects that moved gain an {@code owl:sameAs} statement pointing at the
     * identifier they had before.
     */
    static Result migrate(Model original, EliUriService uriService) {
        Model migrated = ModelFactory.createDefaultModel();
        migrated.setNsPrefixes(original.getNsPrefixMap());
        migrated.setNsPrefix("owl", "http://www.w3.org/2002/07/owl#");

        Property sameAs = migrated.createProperty(OWL_SAME_AS);
        java.util.Set<String> movedSubjects = new java.util.LinkedHashSet<>();
        int sameAsAdded = 0;

        StmtIterator statements = original.listStatements();
        while (statements.hasNext()) {
            Statement statement = statements.nextStatement();

            Resource subject = statement.getSubject();
            String subjectUri = subject.isURIResource() ? subject.getURI() : null;
            Resource newSubject = subjectUri == null
                    ? subject
                    : migrated.createResource(uriService.mint(subjectUri));
            if (subjectUri != null && !newSubject.getURI().equals(subjectUri)) {
                movedSubjects.add(subjectUri);
            }

            RDFNode object = statement.getObject();
            RDFNode newObject = object;
            if (object.isURIResource()) {
                String objectUri = object.asResource().getURI();
                String mintedObject = uriService.mint(objectUri);
                if (!mintedObject.equals(objectUri)) {
                    newObject = migrated.createResource(mintedObject);
                }
            }

            migrated.add(newSubject, statement.getPredicate(), newObject);
        }

        for (String movedSubject : movedSubjects) {
            Resource minted = migrated.createResource(uriService.mint(movedSubject));
            Resource sourceResource = migrated.createResource(movedSubject);
            if (!migrated.contains(minted, sameAs, sourceResource)) {
                migrated.add(minted, sameAs, sourceResource);
                sameAsAdded++;
            }
        }

        return new Result(migrated, movedSubjects.size(), sameAsAdded, migrated.size());
    }

    record Result(Model model, int rewrittenResources, int sameAsAdded, long triples) {
    }
}
