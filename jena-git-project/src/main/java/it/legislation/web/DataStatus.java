package it.legislation.web;

import java.util.List;

public record DataStatus(
        long triples,
        List<String> loadedFiles,
        List<String> missingFiles
) {
}
