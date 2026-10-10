package it.legislation.source.normattiva;

import java.io.IOException;

/** The Normattiva API answered, but not with what was expected. Keeps status and a body excerpt visible. */
public class NormattivaApiException extends IOException {

    private final int status;

    public NormattivaApiException(String operation, int status, String body) {
        super(operation + " failed with HTTP " + status + ": " + excerpt(body));
        this.status = status;
    }

    public NormattivaApiException(String message) {
        super(message);
        this.status = -1;
    }

    public int status() {
        return status;
    }

    private static String excerpt(String body) {
        if (body == null) {
            return "";
        }
        String flat = body.replaceAll("\\s+", " ").trim();
        return flat.length() > 300 ? flat.substring(0, 300) + "..." : flat;
    }
}
