package it.legislation.web;

public record NormattivaEvidenceScanResult(
        String state,
        String startedAt,
        String completedAt,
        int detailsRead,
        int evidenceRows,
        String message,
        String evidencePath
) {
}
