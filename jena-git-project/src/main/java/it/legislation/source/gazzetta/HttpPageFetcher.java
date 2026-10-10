package it.legislation.source.gazzetta;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Polite HTTP fetcher for gazzettaufficiale.it: one request at a time, a pause
 * between requests, a hard time limit per request and a few retries on
 * server errors and time-outs.
 */
public class HttpPageFetcher implements PageFetcher {

    private static final String USER_AGENT =
            "Mozilla/5.0 (compatible; italian-legislation-linked-data/1.0; master's thesis research)";

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private final long pauseMillis;
    private final int retries;
    private long lastRequest;

    public HttpPageFetcher(long pauseMillis, int retries) {
        this.pauseMillis = pauseMillis;
        this.retries = retries;
    }

    @Override
    public Page fetch(String url) throws IOException {
        IOException last = null;
        for (int attempt = 0; attempt <= retries; attempt++) {
            pace(attempt);
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(45))
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "text/html")
                    .GET()
                    .build();
            try {
                HttpResponse<String> response = client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                        .get(60, TimeUnit.SECONDS);
                if (response.statusCode() >= 500 || response.statusCode() == 429) {
                    last = new IOException("HTTP " + response.statusCode() + " for " + url);
                    continue;
                }
                return new Page(response.statusCode(), response.body());
            } catch (TimeoutException e) {
                last = new IOException("No answer within 60 s: " + url);
            } catch (ExecutionException e) {
                last = new IOException(e.getCause() == null ? e.toString() : e.getCause().toString());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted", e);
            }
        }
        throw last;
    }

    private void pace(int attempt) throws IOException {
        long wait = pauseMillis * (attempt + 1) - (System.currentTimeMillis() - lastRequest);
        if (wait > 0) {
            try {
                Thread.sleep(wait);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted", e);
            }
        }
        lastRequest = System.currentTimeMillis();
    }
}
