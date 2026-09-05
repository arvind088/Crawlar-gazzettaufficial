package it.legislation.web;

public record NormattivaRelationCandidateRunResult(
        String state,
        String startedAt,
        String completedAt,
        int evidenceRowsRead,
        int candidatesWritten,
        String message,
        String relationCandidatesPath
) {
}
