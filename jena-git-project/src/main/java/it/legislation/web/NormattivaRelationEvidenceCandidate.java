package it.legislation.web;

public record NormattivaRelationEvidenceCandidate(
        String code,
        String gazzettaDate,
        String candidateTitle,
        String detailTitle,
        String evidenceType,
        String evidenceText
) {
}
