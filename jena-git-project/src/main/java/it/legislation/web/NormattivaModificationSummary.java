package it.legislation.web;

public record NormattivaModificationSummary(
        String sourceUri,
        String sourceLocalId,
        String relationship,
        String targetUri,
        String targetLocalId
) {
}
