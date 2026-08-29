package it.legislation.web;

public record NormattivaDetailFetchResult(
        String state,
        String startedAt,
        String finishedAt,
        int candidatesRead,
        int detailsWritten,
        String message,
        String detailsPath
) {
}
