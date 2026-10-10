package it.legislation.source.gazzetta;

import java.io.IOException;

/** Fetches one web page. Separated from the HTTP client so the check can be tested offline. */
public interface PageFetcher {

    /** HTTP status and body of one fetch. */
    record Page(int status, String body) {}

    Page fetch(String url) throws IOException;
}
