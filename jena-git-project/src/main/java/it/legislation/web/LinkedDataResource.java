package it.legislation.web;

import java.util.List;

public record LinkedDataResource(
        String uri,
        String localId,
        String title,
        List<LinkedDataNode> expressions,
        List<LinkedDataNode> manifestations,
        List<LinkedDataRelation> outgoingRelations,
        List<LinkedDataRelation> incomingRelations
) {
}
