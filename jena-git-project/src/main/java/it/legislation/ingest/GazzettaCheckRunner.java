package it.legislation.ingest;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import org.apache.jena.datatypes.xsd.XSDDatatype;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.Statement;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.riot.RDFFormat;
import org.apache.jena.vocabulary.OWL;
import org.apache.jena.vocabulary.RDF;

import it.legislation.mapping.AssessmentHistory;
import it.legislation.source.gazzetta.GazzettaActPage;
import it.legislation.source.gazzetta.HttpPageFetcher;
import it.legislation.source.gazzetta.PageFetcher;

/**
 * Checks every Normattiva act against its page on Gazzetta Ufficiale (R5).
 *
 * <p>Both sources identify an act by codice redazionale and GU publication
 * date, so the Gazzetta page of a Normattiva act is known without searching:
 * {@code https://www.gazzettaufficiale.it/eli/id/yyyy/mm/dd/CODICE/sg}. The
 * runner fetches that page (or reads it from the local cache), and compares
 * act type, number, date of the act and GU date. Only when all four agree is
 * the act's identity confirmed by both official sources; the Gazzetta data
 * that Normattiva does not give (GU issue, date of entry into force) is then
 * added.
 *
 * <p>Output: {@code data/rdf/gazzetta_check.ttl} (loaded into the store) and
 * {@code data/clean/gazzetta_check.tsv} (one line per act, for the thesis).
 *
 * <pre>
 * mvn -B compile exec:java "-Dexec.mainClass=it.legislation.ingest.GazzettaCheckRunner"
 * </pre>
 *
 * Options: {@code --in}, {@code --out}, {@code --report}, {@code --cache}
 * (default {@code data/raw/gazzetta}), {@code --pause-ms} (default 1000),
 * {@code --today yyyy-mm-dd} (date of the assessments, default today),
 * {@code --limit N} (check only the first N acts), {@code --offline} (use
 * cached pages only).
 */
public class GazzettaCheckRunner {

    public static final Path DEFAULT_IN = AknImportRunner.DEFAULT_OUT;
    public static final Path DEFAULT_OUT = Path.of("data", "rdf", "gazzetta_check.ttl");
    public static final Path DEFAULT_REPORT = Path.of("data", "clean", "gazzetta_check.tsv");
    public static final Path DEFAULT_CACHE = Path.of("data", "raw", "gazzetta");

    static final String ELI = "http://data.europa.eu/eli/ontology#";
    static final String ILG = "http://example.org/italian-legislation/ontology#";
    static final String RESOURCE_TYPE = "http://www.gazzettaufficiale.it/eli/tables/resource-type#";

    /** Outcome of one act. */
    public enum Status {
        /** Type, number, date of the act and GU date agree. */
        MATCH,
        /** The page exists but at least one of those fields differs. */
        MISMATCH,
        /** Gazzetta has no act under this identifier. */
        NOT_FOUND,
        /** The page could not be fetched (network); try again later. */
        ERROR
    }

    /** One act as imported from Normattiva. */
    public record NormattivaAct(String workUri, String codice, LocalDate publicationDate, String typeCode,
                                LocalDate documentDate, String number, String title, String gazzettaUri) {

        /** Page URL on the Gazzetta site (https). */
        public String pageUrl() {
            return gazzettaUri.replaceFirst("^http://", "https://");
        }

        /** File name used by the Gazzetta cache, e.g. {@code 2026_06_20_26G00123_sg.html}. */
        public String cacheName() {
            return String.format("%04d_%02d_%02d_%s_sg.html", publicationDate.getYear(),
                    publicationDate.getMonthValue(), publicationDate.getDayOfMonth(), codice);
        }
    }

    /** Result for one act. {@code differences} lists the fields that differ, as "field: Normattiva ≠ Gazzetta". */
    public record Check(NormattivaAct act, Status status, List<String> differences, boolean titleDiffers,
                        GazzettaActPage page, String error) {}

    /** What one run did. */
    public record Summary(List<Check> checks, Map<Status, Integer> counts, int fetched, int cached, boolean written) {}

    private final PageFetcher fetcher;
    private final Path cache;
    private final boolean offline;
    private final java.time.LocalDate today;

    public GazzettaCheckRunner(PageFetcher fetcher, Path cache, boolean offline) {
        this(fetcher, cache, offline, java.time.LocalDate.now());
    }

    /** @param today date written on the assessments of this run */
    public GazzettaCheckRunner(PageFetcher fetcher, Path cache, boolean offline, java.time.LocalDate today) {
        this.fetcher = fetcher;
        this.cache = cache;
        this.offline = offline;
        this.today = today;
    }

    public static void main(String[] args) throws IOException {
        Map<String, String> options = new LinkedHashMap<>();
        boolean offline = false;
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--offline")) {
                offline = true;
            } else if (i + 1 < args.length) {
                options.put(args[i], args[++i]);
            }
        }
        Path in = Path.of(options.getOrDefault("--in", DEFAULT_IN.toString()));
        Path out = Path.of(options.getOrDefault("--out", DEFAULT_OUT.toString()));
        Path report = Path.of(options.getOrDefault("--report", DEFAULT_REPORT.toString()));
        Path cache = Path.of(options.getOrDefault("--cache", DEFAULT_CACHE.toString()));
        long pause = Long.parseLong(options.getOrDefault("--pause-ms", "1000"));
        int limit = Integer.parseInt(options.getOrDefault("--limit", String.valueOf(Integer.MAX_VALUE)));

        java.time.LocalDate today = java.time.LocalDate.parse(
                options.getOrDefault("--today", java.time.LocalDate.now().toString()));
        GazzettaCheckRunner runner = new GazzettaCheckRunner(new HttpPageFetcher(pause, 2), cache, offline, today);
        Summary summary = runner.run(in, out, report, limit, GazzettaCheckRunner::log);

        System.out.println();
        System.out.println("Acts checked:  " + summary.checks().size()
                + "  (pages fetched: " + summary.fetched() + ", from cache: " + summary.cached() + ")");
        summary.counts().forEach((status, n) -> System.out.println("  " + pad(status.name(), 10) + n));
        long titles = summary.checks().stream().filter(Check::titleDiffers).count();
        System.out.println("  title wording differs (not counted as mismatch): " + titles);
        summary.checks().stream().filter(c -> c.status() != Status.MATCH).forEach(c ->
                System.out.println("  " + pad(c.status().name(), 10) + c.act().codice() + "  " + c.act().pageUrl()
                        + (c.differences().isEmpty() ? "" : "  " + c.differences())
                        + (c.error() == null ? "" : "  " + c.error())));
        System.out.println(summary.written() ? "Wrote " + out + " and " + report : "No change: " + out + " is already up to date");
    }

    public Summary run(Path in, Path out, Path report, int limit, java.util.function.Consumer<String> progress)
            throws IOException {
        List<NormattivaAct> acts = readActs(in);
        if (acts.size() > limit) {
            acts = acts.subList(0, limit);
        }
        List<Check> checks = new ArrayList<>();
        int fetched = 0;
        int cached = 0;
        for (int i = 0; i < acts.size(); i++) {
            NormattivaAct act = acts.get(i);
            Path cacheFile = cache.resolve(act.cacheName());
            String html;
            if (Files.exists(cacheFile)) {
                html = Files.readString(cacheFile, StandardCharsets.UTF_8);
                cached++;
            } else if (offline) {
                checks.add(new Check(act, Status.ERROR, List.of(), false, null, "not in cache (offline run)"));
                continue;
            } else {
                PageFetcher.Page page;
                try {
                    page = fetcher.fetch(act.pageUrl());
                    fetched++;
                } catch (IOException e) {
                    checks.add(new Check(act, Status.ERROR, List.of(), false, null, e.getMessage()));
                    progress.accept((i + 1) + "/" + acts.size() + "  ERROR      " + act.codice() + "  " + e.getMessage());
                    continue;
                }
                if (page.status() == 404) {
                    checks.add(new Check(act, Status.NOT_FOUND, List.of(), false, null, "HTTP 404"));
                    continue;
                }
                if (page.status() != 200) {
                    checks.add(new Check(act, Status.ERROR, List.of(), false, null, "HTTP " + page.status()));
                    continue;
                }
                html = page.body();
                if (GazzettaActPage.parse(html, act.pageUrl()).isPresent()) {
                    Files.createDirectories(cache);
                    Files.writeString(cacheFile, html, StandardCharsets.UTF_8);
                }
            }
            Check check = compare(act, GazzettaActPage.parse(html, act.pageUrl()));
            checks.add(check);
            if ((i + 1) % 50 == 0 || check.status() != Status.MATCH) {
                progress.accept((i + 1) + "/" + acts.size() + "  " + pad(check.status().name(), 10) + act.codice()
                        + (check.differences().isEmpty() ? "" : "  " + check.differences()));
            }
        }

        Map<Status, Integer> counts = new EnumMap<>(Status.class);
        for (Status s : Status.values()) {
            counts.put(s, 0);
        }
        checks.forEach(c -> counts.merge(c.status(), 1, Integer::sum));

        boolean written = writeModelIfChanged(out, toRdf(checks, AssessmentHistory.read(out), today));
        written |= writeIfChanged(report, tsv(checks).getBytes(StandardCharsets.UTF_8));
        return new Summary(checks, counts, fetched, cached, written);
    }

    /** Compares one Normattiva act with what its Gazzetta page says. */
    static Check compare(NormattivaAct act, Optional<GazzettaActPage> maybePage) {
        if (maybePage.isEmpty()) {
            return new Check(act, Status.NOT_FOUND, List.of(), false, null, "page has no act");
        }
        GazzettaActPage page = maybePage.get();
        List<String> differences = new ArrayList<>();
        differ(differences, "codice", act.codice(), page.codice());
        differ(differences, "GU date", act.publicationDate(), page.publicationDate());
        differ(differences, "type", act.typeCode(), page.typeCode());
        differ(differences, "number", act.number(), page.number());
        differ(differences, "date of act", act.documentDate(), page.documentDate());
        boolean titleDiffers = !Objects.equals(normalizeTitle(act.title()), normalizeTitle(page.title()));
        return new Check(act, differences.isEmpty() ? Status.MATCH : Status.MISMATCH, differences, titleDiffers, page, null);
    }

    private static void differ(List<String> differences, String field, Object normattiva, Object gazzetta) {
        if (!Objects.equals(normattiva, gazzetta)) {
            differences.add(field + ": " + normattiva + " ≠ " + gazzetta);
        }
    }

    /** Title compared without case, white space, quotes and final full stop. */
    static String normalizeTitle(String title) {
        if (title == null) {
            return null;
        }
        return it.legislation.mapping.AknActReader.decodeEntities(title).toLowerCase(java.util.Locale.ROOT)
                .replaceAll("[«»\"'’`]", "")
                .replaceAll("[\\s\\u00a0\\u2000-\\u200b\\u202f]+", " ")
                .replaceAll(" ([,.;:])", "$1")
                .replaceAll("[ .]+$", "")
                .trim();
    }

    /** Reads the acts (ELI Works) from the Normattiva Turtle file. */
    static List<NormattivaAct> readActs(Path in) throws IOException {
        // Read from a stream, not a path string: on Windows the path has
        // back-slashes, which Jena rejects inside a file: IRI.
        Model model = ModelFactory.createDefaultModel();
        try (java.io.InputStream stream = Files.newInputStream(in)) {
            RDFDataMgr.read(model, stream, org.apache.jena.riot.Lang.TURTLE);
        }
        Property idLocal = model.createProperty(ELI, "id_local");
        Property datePublication = model.createProperty(ELI, "date_publication");
        Property dateDocument = model.createProperty(ELI, "date_document");
        Property typeDocument = model.createProperty(ELI, "type_document");
        Property number = model.createProperty(ELI, "number");
        Property title = model.createProperty(ELI, "title");
        Resource legalResource = model.createResource(ELI + "LegalResource");

        List<NormattivaAct> acts = new ArrayList<>();
        model.listSubjectsWithProperty(RDF.type, legalResource).forEachRemaining(work -> {
            String gazzetta = null;
            for (Statement s : work.listProperties(OWL.sameAs).toList()) {
                if (s.getObject().isURIResource() && s.getResource().getURI().contains("gazzettaufficiale.it/eli/id/")) {
                    gazzetta = s.getResource().getURI();
                }
            }
            String type = uri(work.getProperty(typeDocument) == null ? null : work.getProperty(typeDocument).getObject());
            if (gazzetta == null || work.getProperty(idLocal) == null || work.getProperty(datePublication) == null) {
                return;
            }
            acts.add(new NormattivaAct(work.getURI(),
                    work.getProperty(idLocal).getString(),
                    LocalDate.parse(work.getProperty(datePublication).getString()),
                    type == null ? null : type.substring(type.indexOf('#') + 1),
                    work.getProperty(dateDocument) == null ? null : LocalDate.parse(work.getProperty(dateDocument).getString()),
                    work.getProperty(number) == null ? null
                            : it.legislation.mapping.AknActReader.actNumber(work.getProperty(number).getString()),
                    work.getProperty(title) == null ? null : work.getProperty(title).getString(),
                    gazzetta));
        });
        acts.sort(Comparator.comparing(NormattivaAct::publicationDate).thenComparing(NormattivaAct::codice));
        return acts;
    }

    private static String uri(RDFNode node) {
        return node != null && node.isURIResource() ? node.asResource().getURI() : null;
    }

    /**
     * The check as RDF. The result of each act is a dated assessment ({@link AssessmentHistory}): a new one is
     * written only when the result changed since the latest earlier one, and nothing is removed. Facts that
     * only Gazzetta gives (GU issue, date of entry into force) are added to confirmed acts. Network errors
     * are not recorded: they say nothing about the act.
     */
    static Model toRdf(List<Check> checks, AssessmentHistory history, java.time.LocalDate day) {
        Model model = ModelFactory.createDefaultModel();
        model.add(history.previous());
        model.setNsPrefix("eli", ELI);
        model.setNsPrefix("ilg", ILG);
        model.setNsPrefix("owl", OWL.NS);
        model.setNsPrefix("xsd", XSDDatatype.XSD + "#");
        Property result = model.createProperty(ILG, "gazzettaCheck");
        Property difference = model.createProperty(ILG, "gazzettaDifference");
        Property confirmedBy = model.createProperty(ILG, "identityConfirmedBy");
        Property guNumber = model.createProperty(ILG, "guNumber");
        Property guIssue = model.createProperty(ILG, "guIssue");
        Property entryIntoForce = model.createProperty(ELI, "first_date_entry_in_force");

        for (Check check : checks) {
            if (check.status() == Status.ERROR) {
                continue;
            }
            Resource work = model.createResource(check.act().workUri());
            AssessmentHistory.Values values = new AssessmentHistory.Values()
                    .put(result, model.createLiteral(check.status().name()));
            check.differences().forEach(d -> values.put(difference, model.createLiteral(d)));
            String uri = check.act().workUri();
            String base = uri.contains("/eli/id/") ? uri.substring(0, uri.indexOf("/eli/id/")) : uri;
            history.record(model, work, "GazzettaCheck", base + "/check/gazzetta/" + check.act().codice(), day,
                    values.build());
            if (check.status() != Status.MATCH) {
                continue;
            }
            GazzettaActPage page = check.page();
            work.addProperty(confirmedBy, model.createResource(check.act().gazzettaUri()));
            if (page.guNumber() != null) {
                work.addProperty(guNumber, page.guNumber());
            }
            if (page.guIssueUri() != null) {
                work.addProperty(guIssue, model.createResource(page.guIssueUri()));
            }
            if (page.entryIntoForce() != null) {
                work.addLiteral(entryIntoForce,
                        model.createTypedLiteral(page.entryIntoForce().toString(), XSDDatatype.XSDdate));
            }
        }
        return model;
    }

    static String tsv(List<Check> checks) {
        StringBuilder sb = new StringBuilder(
                "codice\tgu_date\ttype\tnumber\tdate_of_act\tstatus\tdifferences\ttitle_differs\tgu_number\tentry_into_force\tgazzetta_url\n");
        for (Check c : checks) {
            NormattivaAct a = c.act();
            GazzettaActPage p = c.page();
            sb.append(a.codice()).append('\t')
                    .append(a.publicationDate()).append('\t')
                    .append(a.typeCode()).append('\t')
                    .append(a.number()).append('\t')
                    .append(a.documentDate()).append('\t')
                    .append(c.status()).append('\t')
                    .append(c.error() != null ? c.error() : String.join("; ", c.differences())).append('\t')
                    .append(c.titleDiffers() ? "yes" : "").append('\t')
                    .append(p == null || p.guNumber() == null ? "" : p.guNumber()).append('\t')
                    .append(p == null || p.entryIntoForce() == null ? "" : p.entryIntoForce()).append('\t')
                    .append(a.pageUrl()).append('\n');
        }
        return sb.toString();
    }

    private static byte[] turtle(Model model) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        RDFDataMgr.write(bytes, model, RDFFormat.TURTLE_PRETTY);
        return bytes.toByteArray();
    }

    /**
     * Writes the model unless the file already holds the same graph. Compared as RDF, not as bytes:
     * a graph read back from a file can be serialised in a different order than the one it was built in.
     */
    static boolean writeModelIfChanged(Path file, Model model) throws IOException {
        if (Files.exists(file)) {
            Model existing = ModelFactory.createDefaultModel();
            try (java.io.InputStream in = Files.newInputStream(file)) {
                RDFDataMgr.read(existing, in, org.apache.jena.riot.Lang.TURTLE);
            }
            if (existing.isIsomorphicWith(model)) {
                return false;
            }
        }
        return writeIfChanged(file, turtle(model));
    }

    private static boolean writeIfChanged(Path file, byte[] content) throws IOException {
        if (Files.exists(file) && java.util.Arrays.equals(Files.readAllBytes(file), content)) {
            return false;
        }
        if (file.getParent() != null) {
            Files.createDirectories(file.getParent());
        }
        Files.write(file, content);
        return true;
    }

    private static String pad(String s, int width) {
        return s.length() >= width ? s + " " : s + " ".repeat(width - s.length());
    }

    private static void log(String line) {
        System.out.println(java.time.LocalTime.now().withNano(0) + " " + line);
    }
}
