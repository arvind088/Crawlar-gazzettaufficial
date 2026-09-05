package it.legislation.web;

public record NormattivaDetailCandidate(
        String code,
        String gazzettaDate,
        String candidateTitle,
        String candidateActName,
        String candidateActNumber,
        String detailTitle,
        String detailSubtitle,
        String actType,
        String actTypeCode,
        String actDate,
        String actNumber,
        String publicationDate,
        String forceStartDate,
        String forceEndDate,
        String textInForce,
        String articleHtml,
        String source,
        String fetchedAt
) {
}
