package it.legislation.web;

public record RdfSourceFile(
        String status,
        String fileName,
        String description,
        String lastModified,
        long sizeBytes,
        String downloadUrl
) {
}
