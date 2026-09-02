package it.legislation.web;

public record NormattivaRelationCandidate(
        String sourceUri,
        String targetUri,
        String relationType,
        String evidenceType,
        String evidenceText,
        String reviewStatus
) {
}
