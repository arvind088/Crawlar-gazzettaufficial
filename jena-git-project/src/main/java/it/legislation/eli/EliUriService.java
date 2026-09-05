package it.legislation.eli;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Translates between the ELI identifiers this platform publishes on its own
 * domain and the source identifiers that are stored in the triple store.
 *
 * <p>CONTEXT.md constraint 3 requires resources to carry ELI-pattern URIs on our
 * own domain rather than only on the source's domain:
 *
 * <pre>
 * https://{our-domain}/eli/id/{year}/{month}/{day}/{natural-id}/{type}
 * </pre>
 *
 * <p>Constraint 2 requires every identifier to be an {@code http(s)} URI, so no
 * {@code urn:} value is ever minted or accepted here.
 *
 * <p>The mapping is deliberately reversible. A request for one of our paths can
 * be resolved against data that still carries source URIs, which means the
 * resolution route works before the stored data has been migrated, and keeps
 * working afterwards.
 */
public final class EliUriService {

    /**
     * Matches the ELI identifier part of a URI, on any host:
     * {@code .../eli/id/{year}/{month}/{day}/{natural-id}/{type}} plus any
     * further segments used for expressions and manifestations.
     */
    private static final Pattern ELI_ID_PATTERN = Pattern.compile(
            "/eli/id/(\\d{4})/(\\d{2})/(\\d{2})/([^/?#]+)/([^/?#]+)((?:/[^?#]*)?)"
    );

    private static final String GAZZETTA_BASE = "http://www.gazzettaufficiale.it";
    private static final String DEFAULT_BASE_URI = "https://osservatorio-eli.example.it";

    private final String baseUri;

    public EliUriService() {
        this(DEFAULT_BASE_URI);
    }

    public EliUriService(String baseUri) {
        this.baseUri = normalizeBase(baseUri);
    }

    public String baseUri() {
        return baseUri;
    }

    /**
     * Builds the ELI path for the given components. Always starts with a slash
     * and never ends with one.
     */
    public String path(String year, String month, String day, String naturalId, String type, String tail) {
        StringBuilder builder = new StringBuilder("/eli/id/")
                .append(year).append('/')
                .append(month).append('/')
                .append(day).append('/')
                .append(naturalId).append('/')
                .append(type);
        if (tail != null && !tail.isBlank()) {
            String cleaned = tail.startsWith("/") ? tail : "/" + tail;
            builder.append(stripTrailingSlashes(cleaned));
        }
        return builder.toString();
    }

    /**
     * Extracts the ELI path from any URI that contains one, whatever its host.
     * Returns empty for a URI that carries no ELI identifier.
     */
    public Optional<String> pathOf(String uri) {
        if (uri == null || uri.isBlank()) {
            return Optional.empty();
        }
        Matcher matcher = ELI_ID_PATTERN.matcher(uri);
        if (!matcher.find()) {
            return Optional.empty();
        }
        return Optional.of(path(
                matcher.group(1),
                matcher.group(2),
                matcher.group(3),
                matcher.group(4),
                matcher.group(5),
                matcher.group(6)
        ));
    }

    /**
     * Mints the identifier this platform publishes for a resource. A URI that
     * carries an ELI identifier is re-hosted on our domain; anything else is
     * returned unchanged, so vocabulary terms and external references are never
     * rewritten.
     */
    public String mint(String uri) {
        return pathOf(uri).map(path -> baseUri + path).orElse(uri);
    }

    /** True when the URI is already published on our domain. */
    public boolean isOurs(String uri) {
        return uri != null && uri.startsWith(baseUri + "/eli/");
    }

    /** True when the URI carries an ELI identifier we are able to re-host. */
    public boolean isEliIdentifier(String uri) {
        return pathOf(uri).isPresent();
    }

    /** Our own URI for a given ELI path. */
    public String uriForPath(String path) {
        if (path == null || path.isBlank()) {
            return baseUri;
        }
        String cleaned = path.startsWith("/") ? path : "/" + path;
        return baseUri + stripTrailingSlashes(cleaned);
    }

    /**
     * The Gazzetta Ufficiale URI for the same ELI identifier. Used as the
     * {@code owl:sameAs} target so that a resource minted on our domain still
     * states which source record it describes.
     */
    public Optional<String> gazzettaUriForPath(String path) {
        if (path == null || path.isBlank()) {
            return Optional.empty();
        }
        String cleaned = path.startsWith("/") ? path : "/" + path;
        if (!ELI_ID_PATTERN.matcher(cleaned).find()) {
            return Optional.empty();
        }
        return Optional.of(GAZZETTA_BASE + stripTrailingSlashes(cleaned));
    }

    /**
     * Every identifier a stored resource might carry for the same act, most
     * specific first. Lets the resolver find a resource whether the data has
     * been migrated to our domain or still uses the source URI.
     */
    public java.util.List<String> candidateUris(String path) {
        java.util.List<String> candidates = new java.util.ArrayList<>();
        String ours = uriForPath(path);
        candidates.add(ours);
        gazzettaUriForPath(path).ifPresent(gazzetta -> {
            candidates.add(gazzetta);
            candidates.add(gazzetta.replaceFirst("^http://", "https://"));
            candidates.add(gazzetta.replaceFirst("^http://www\\.", "http://"));
        });
        return java.util.List.copyOf(new java.util.LinkedHashSet<>(candidates));
    }

    /** The local (redactional) identifier inside an ELI path, e.g. {@code 005G0104}. */
    public Optional<String> localIdOf(String uri) {
        if (uri == null) {
            return Optional.empty();
        }
        Matcher matcher = ELI_ID_PATTERN.matcher(uri);
        if (!matcher.find()) {
            return Optional.empty();
        }
        return Optional.of(matcher.group(4));
    }

    /** The publication date encoded in an ELI path, as {@code yyyy-MM-dd}. */
    public Optional<String> publicationDateOf(String uri) {
        if (uri == null) {
            return Optional.empty();
        }
        Matcher matcher = ELI_ID_PATTERN.matcher(uri);
        if (!matcher.find()) {
            return Optional.empty();
        }
        return Optional.of(matcher.group(1) + "-" + matcher.group(2) + "-" + matcher.group(3));
    }

    private static String normalizeBase(String value) {
        if (value == null || value.isBlank()) {
            return DEFAULT_BASE_URI;
        }
        String trimmed = value.trim();
        String lower = trimmed.toLowerCase(Locale.ROOT);
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            // Constraint 2: identifiers are always http(s), never urn:.
            trimmed = "https://" + trimmed;
        }
        return stripTrailingSlashes(trimmed);
    }

    private static String stripTrailingSlashes(String value) {
        int end = value.length();
        while (end > 1 && value.charAt(end - 1) == '/') {
            end--;
        }
        return value.substring(0, end);
    }
}
