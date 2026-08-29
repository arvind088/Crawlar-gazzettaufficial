package it.legislation.web;

public record LinkedDataNode(
        String uri,
        String label,
        String localId,
        String type,
        String version,
        String language,
        String format
) {
}
