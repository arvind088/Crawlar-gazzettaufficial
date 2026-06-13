package it.legislation.crawler;

import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;

public final class CleanLegalActRecord {

    private final String eliUri;
    private final String title;
    private final LocalDate publicationDate;
    private final LocalDate documentDate;
    private final String documentTypeUri;
    private final String localId;
    private final String realizedByUri;
    private final String embodiedByUri;
    private final String versionUri;
    private final String formatUri;
    private final String languageUri;
    private final String publisherUri;
    private final String authorityLabel;
    private final String reference;
    private final String note;
    private final String referenceGu;
    private final String pdfGuUrl;
    private final String sourceUrl;

    private CleanLegalActRecord(Builder builder) {
        this.eliUri = requireValue(builder.eliUri, "eliUri");
        this.title = cleanText(builder.title);
        this.publicationDate = builder.publicationDate;
        this.documentDate = builder.documentDate;
        this.documentTypeUri = cleanText(builder.documentTypeUri);
        this.localId = cleanText(builder.localId);
        this.realizedByUri = cleanText(builder.realizedByUri);
        this.embodiedByUri = cleanText(builder.embodiedByUri);
        this.versionUri = cleanText(builder.versionUri);
        this.formatUri = cleanText(builder.formatUri);
        this.languageUri = cleanText(builder.languageUri);
        this.publisherUri = cleanText(builder.publisherUri);
        this.authorityLabel = cleanText(builder.authorityLabel);
        this.reference = cleanText(builder.reference);
        this.note = cleanText(builder.note);
        this.referenceGu = cleanText(builder.referenceGu);
        this.pdfGuUrl = cleanText(builder.pdfGuUrl);
        this.sourceUrl = cleanText(builder.sourceUrl);
    }

    public static Builder builder(String eliUri) {
        return new Builder(eliUri);
    }

    public String getEliUri() {
        return eliUri;
    }

    public Optional<String> getTitle() {
        return Optional.ofNullable(title);
    }

    public Optional<LocalDate> getPublicationDate() {
        return Optional.ofNullable(publicationDate);
    }

    public Optional<LocalDate> getDocumentDate() {
        return Optional.ofNullable(documentDate);
    }

    public Optional<String> getDocumentTypeUri() {
        return Optional.ofNullable(documentTypeUri);
    }

    public Optional<String> getLocalId() {
        return Optional.ofNullable(localId);
    }

    public Optional<String> getRealizedByUri() {
        return Optional.ofNullable(realizedByUri);
    }

    public Optional<String> getEmbodiedByUri() {
        return Optional.ofNullable(embodiedByUri);
    }

    public Optional<String> getVersionUri() {
        return Optional.ofNullable(versionUri);
    }

    public Optional<String> getFormatUri() {
        return Optional.ofNullable(formatUri);
    }

    public Optional<String> getLanguageUri() {
        return Optional.ofNullable(languageUri);
    }

    public Optional<String> getPublisherUri() {
        return Optional.ofNullable(publisherUri);
    }

    public Optional<String> getAuthorityLabel() {
        return Optional.ofNullable(authorityLabel);
    }

    public Optional<String> getReference() {
        return Optional.ofNullable(reference);
    }

    public Optional<String> getNote() {
        return Optional.ofNullable(note);
    }

    public Optional<String> getReferenceGu() {
        return Optional.ofNullable(referenceGu);
    }

    public Optional<String> getPdfGuUrl() {
        return Optional.ofNullable(pdfGuUrl);
    }

    public Optional<String> getSourceUrl() {
        return Optional.ofNullable(sourceUrl);
    }

    public static boolean hasValue(String value) {
        if (value == null) {
            return false;
        }
        String trimmed = value.trim();
        return !trimmed.isEmpty() && !"NOT FOUND".equalsIgnoreCase(trimmed);
    }

    private static String cleanText(String value) {
        if (!hasValue(value)) {
            return null;
        }
        return value.trim().replaceAll("\\s+", " ");
    }

    private static String requireValue(String value, String fieldName) {
        String cleaned = cleanText(value);
        if (cleaned == null) {
            throw new IllegalArgumentException(fieldName + " is required");
        }
        return cleaned;
    }

    public static final class Builder {
        private final String eliUri;
        private String title;
        private LocalDate publicationDate;
        private LocalDate documentDate;
        private String documentTypeUri;
        private String localId;
        private String realizedByUri;
        private String embodiedByUri;
        private String versionUri;
        private String formatUri;
        private String languageUri;
        private String publisherUri;
        private String authorityLabel;
        private String reference;
        private String note;
        private String referenceGu;
        private String pdfGuUrl;
        private String sourceUrl;

        private Builder(String eliUri) {
            this.eliUri = eliUri;
        }

        public Builder title(String title) {
            this.title = title;
            return this;
        }

        public Builder publicationDate(LocalDate publicationDate) {
            this.publicationDate = publicationDate;
            return this;
        }

        public Builder documentDate(LocalDate documentDate) {
            this.documentDate = documentDate;
            return this;
        }

        public Builder documentTypeUri(String documentTypeUri) {
            this.documentTypeUri = documentTypeUri;
            return this;
        }

        public Builder localId(String localId) {
            this.localId = localId;
            return this;
        }

        public Builder realizedByUri(String realizedByUri) {
            this.realizedByUri = realizedByUri;
            return this;
        }

        public Builder embodiedByUri(String embodiedByUri) {
            this.embodiedByUri = embodiedByUri;
            return this;
        }

        public Builder versionUri(String versionUri) {
            this.versionUri = versionUri;
            return this;
        }

        public Builder formatUri(String formatUri) {
            this.formatUri = formatUri;
            return this;
        }

        public Builder languageUri(String languageUri) {
            this.languageUri = languageUri;
            return this;
        }

        public Builder publisherUri(String publisherUri) {
            this.publisherUri = publisherUri;
            return this;
        }

        public Builder authorityLabel(String authorityLabel) {
            this.authorityLabel = authorityLabel;
            return this;
        }

        public Builder reference(String reference) {
            this.reference = reference;
            return this;
        }

        public Builder note(String note) {
            this.note = note;
            return this;
        }

        public Builder referenceGu(String referenceGu) {
            this.referenceGu = referenceGu;
            return this;
        }

        public Builder pdfGuUrl(String pdfGuUrl) {
            this.pdfGuUrl = pdfGuUrl;
            return this;
        }

        public Builder sourceUrl(String sourceUrl) {
            this.sourceUrl = sourceUrl;
            return this;
        }

        public CleanLegalActRecord build() {
            return new CleanLegalActRecord(this);
        }
    }

    @Override
    public String toString() {
        return "CleanLegalActRecord{" +
                "eliUri='" + eliUri + '\'' +
                ", localId='" + Objects.toString(localId, "") + '\'' +
                '}';
    }
}
