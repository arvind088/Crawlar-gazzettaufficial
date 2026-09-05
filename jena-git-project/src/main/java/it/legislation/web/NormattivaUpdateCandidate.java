package it.legislation.web;

public record NormattivaUpdateCandidate(
        String code,
        String title,
        String gazzettaDate,
        String actDate,
        String lastModifiedDate,
        String actName,
        String actNumber,
        String modifyingActs,
        String source,
        String fetchedAt
) {
}
