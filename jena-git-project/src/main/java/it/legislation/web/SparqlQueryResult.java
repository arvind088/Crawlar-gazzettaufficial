package it.legislation.web;

import java.util.List;
import java.util.Map;

public record SparqlQueryResult(
        List<String> columns,
        List<Map<String, String>> rows,
        String error
) {
    public static SparqlQueryResult error(String message) {
        return new SparqlQueryResult(List.of(), List.of(), message);
    }
}
