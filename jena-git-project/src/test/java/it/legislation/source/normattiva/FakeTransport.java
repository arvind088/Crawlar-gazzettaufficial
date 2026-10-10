package it.legislation.source.normattiva;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;

/** Replays queued responses and records every call, so tests never touch the network. */
class FakeTransport implements NormattivaTransport {

    record Call(String method, String path, String body) {}

    final List<Call> calls = new ArrayList<>();
    private final Deque<Response> responses = new ArrayDeque<>();

    FakeTransport reply(int status, String body) {
        return reply(status, Map.of(), body);
    }

    FakeTransport reply(int status, Map<String, String> headers, String body) {
        responses.add(new Response(status, headers, body.getBytes(StandardCharsets.UTF_8)));
        return this;
    }

    FakeTransport replyBytes(int status, byte[] body) {
        responses.add(new Response(status, Map.of(), body));
        return this;
    }

    @Override
    public Response get(String path) {
        return next("GET", path, null);
    }

    @Override
    public Response post(String path, String jsonBody) {
        return next("POST", path, jsonBody);
    }

    @Override
    public Response put(String path, String jsonBody) {
        return next("PUT", path, jsonBody);
    }

    private Response next(String method, String path, String body) {
        calls.add(new Call(method, path, body));
        if (responses.isEmpty()) {
            throw new IllegalStateException("No response queued for " + method + " " + path);
        }
        return responses.removeFirst();
    }
}
