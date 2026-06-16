package it.legislation.web;

public record LegalActSummary(
        String uri,
        String title,
        String publicationDate,
        String documentDate,
        String type,
        String localId,
        String source
) {
}
