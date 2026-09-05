package it.legislation.web;

public record NormattivaImportResult(
        String state,
        String startedAt,
        String completedAt,
        int rowsWritten,
        String message,
        String inputPath,
        String outputPath
) {
}
