package it.legislation.source.normattiva;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;

/**
 * The HTTP calls the Normattiva OpenData client needs. Kept as an interface so
 * that tests can replace the network with recorded responses.
 */
public interface NormattivaTransport {

    Response get(String path) throws IOException;

    Response post(String path, String jsonBody) throws IOException;

    Response put(String path, String jsonBody) throws IOException;

    /** A raw HTTP response: status, headers (lower-case names) and body bytes. */
    record Response(int status, Map<String, String> headers, byte[] body) {

        public String text() {
            return body == null ? "" : new String(body, StandardCharsets.UTF_8);
        }

        public Optional<String> header(String name) {
            return Optional.ofNullable(headers.get(name.toLowerCase()));
        }

        public boolean isSuccess() {
            return status >= 200 && status < 300;
        }
    }
}
