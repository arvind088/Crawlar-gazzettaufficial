package it.legislation.web;

public record LinkedDataRelation(
        String predicate,
        String predicateLabel,
        String displayLabel,
        boolean important,
        String resourceUri,
        String resourceLabel,
        String resourceLocalId
) {
}
