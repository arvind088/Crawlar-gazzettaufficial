package it.legislation.web;

public record NormattivaUpdateResult(
        String state,
        String startedAt,
        String finishedAt,
        int updatesRead,
        int relationRows,
        String message,
        DataStatus dataStatus
) {
}
