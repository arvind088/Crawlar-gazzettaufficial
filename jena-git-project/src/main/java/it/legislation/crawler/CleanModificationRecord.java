package it.legislation.crawler;

import java.util.Objects;

public final class CleanModificationRecord {

    private final String subjectUri;
    private final String objectUri;
    private final String modificationText;
    private final ModificationType modificationType;

    private CleanModificationRecord(String subjectUri, String objectUri, String modificationText) {
        this.subjectUri = requireUri(subjectUri, "subjectUri");
        this.objectUri = requireUri(objectUri, "objectUri");
        this.modificationText = cleanText(modificationText);
        this.modificationType = ModificationType.classify(this.modificationText);
    }

    public static CleanModificationRecord of(String subjectUri, String objectUri, String modificationText) {
        return new CleanModificationRecord(subjectUri, objectUri, modificationText);
    }

    public String getSubjectUri() {
        return subjectUri;
    }

    public String getObjectUri() {
        return objectUri;
    }

    public String getModificationText() {
        return modificationText;
    }

    public ModificationType getModificationType() {
        return modificationType;
    }

    private static String requireUri(String value, String fieldName) {
        String cleaned = cleanText(value);
        if (cleaned == null || !(cleaned.startsWith("http://") || cleaned.startsWith("https://"))) {
            throw new IllegalArgumentException(fieldName + " must be an absolute HTTP URI");
        }
        return cleaned;
    }

    private static String cleanText(String value) {
        if (value == null) {
            return null;
        }
        String cleaned = value.trim().replaceAll("\\s+", " ");
        return cleaned.isEmpty() ? null : cleaned;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof CleanModificationRecord that)) {
            return false;
        }
        return Objects.equals(subjectUri, that.subjectUri)
                && Objects.equals(objectUri, that.objectUri)
                && Objects.equals(modificationText, that.modificationText);
    }

    @Override
    public int hashCode() {
        return Objects.hash(subjectUri, objectUri, modificationText);
    }
}
