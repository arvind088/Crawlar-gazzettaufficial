package it.legislation.source.normattiva;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@link NormattivaTransport} over {@code java.net.http}.
 *
 * <p>Sends a clear User-Agent, waits between calls so the public service is
 * not flooded, and retries a few times on transient errors (429 and 5xx).
 */
public class HttpNormattivaTransport implements NormattivaTransport {

    public static final String PRODUCTION_BASE =
            "https://api.normattiva.it/t/normattiva.api/bff-opendata/v1/api/v1";
    public static final String USER_AGENT =
            "ItalianLegislationLinkedData/0.1 (thesis project; +https://github.com/arvind088/Crawlar-gazzettaufficial)";

    private final String baseUrl;
    private final HttpClient client;
    private final Duration minInterval;
    private final int retries;
    private long lastCallNanos;

    public HttpNormattivaTransport() {
        this(PRODUCTION_BASE, Duration.ofMillis(500), 2);
    }

    public HttpNormattivaTransport(String baseUrl, Duration minInterval, int retries) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.minInterval = minInterval;
        this.retries = retries;
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    @Override
    public Response get(String path) throws IOException {
        return send(builder(path).GET().build());
    }

    @Override
    public Response post(String path, String jsonBody) throws IOException {
        return send(builder(path)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build());
    }

    @Override
    public Response put(String path, String jsonBody) throws IOException {
        return send(builder(path)
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build());
    }

    String url(String path) {
        if (path.startsWith("http://") || path.startsWith("https://")) {
            return path;
        }
        return baseUrl + "/" + (path.startsWith("/") ? path.substring(1) : path);
    }

    private HttpRequest.Builder builder(String path) {
        return HttpRequest.newBuilder(URI.create(url(path)))
                .timeout(Duration.ofSeconds(60))
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json, application/zip, */*")
                .header("Accept-Language", "it-IT,it;q=0.9");
    }

    private Response send(HttpRequest request) throws IOException {
        IOException last = null;
        for (int attempt = 0; attempt <= retries; attempt++) {
            pace();
            try {
                HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
                Map<String, String> headers = new LinkedHashMap<>();
                response.headers().map().forEach((name, values) -> {
                    if (!values.isEmpty()) {
                        headers.put(name.toLowerCase(), values.get(0));
                    }
                });
                Response result = new Response(response.statusCode(), headers, response.body());
                if ((result.status() == 429 || result.status() >= 500) && attempt < retries) {
                    sleep(Duration.ofSeconds(2L * (attempt + 1)));
                    continue;
                }
                return result;
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while calling " + request.uri(), exception);
            } catch (IOException exception) {
                last = exception;
                sleep(Duration.ofSeconds(2L * (attempt + 1)));
            }
        }
        throw last != null ? last : new IOException("Request failed: " + request.uri());
    }

    private synchronized void pace() {
        long now = System.nanoTime();
        long wait = lastCallNanos + minInterval.toNanos() - now;
        if (lastCallNanos != 0 && wait > 0) {
            sleep(Duration.ofNanos(wait));
        }
        lastCallNanos = System.nanoTime();
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(Math.max(0, duration.toMillis()));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }
}
