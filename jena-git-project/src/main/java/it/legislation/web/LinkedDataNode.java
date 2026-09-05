package it.legislation.web;

public record LinkedDataNode(
        String uri,
        String label,
        String localId,
        String type,
        String version,
        String language,
        String format,
        /** ELI in-force value for this node, or null when not recorded (FR-4.5). */
        String inForce
) {
}
