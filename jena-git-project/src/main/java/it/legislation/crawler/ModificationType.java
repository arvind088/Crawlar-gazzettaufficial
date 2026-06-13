package it.legislation.crawler;

public enum ModificationType {
    ABROGATION,
    AMENDMENT,
    CONVERSION,
    REFERENCE,
    UNKNOWN;

    public static ModificationType classify(String text) {
        if (text == null || text.isBlank()) {
            return UNKNOWN;
        }

        String lower = text.toLowerCase();
        if (lower.contains("conversione")) {
            return CONVERSION;
        }
        if (lower.contains("abrog")) {
            return ABROGATION;
        }
        if (lower.contains("modifica")
                || lower.contains("modificazioni")
                || lower.contains("introduzione")
                || lower.contains("sostituzione")
                || lower.contains("integrazione")) {
            return AMENDMENT;
        }
        if (lower.contains("relativo")) {
            return REFERENCE;
        }
        return UNKNOWN;
    }
}
