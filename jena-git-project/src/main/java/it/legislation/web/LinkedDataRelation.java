package it.legislation.web;

public record LinkedDataRelation(
        String predicate,
        String predicateLabel,
        String resourceUri,
        String resourceLabel,
        String resourceLocalId
) {
}
