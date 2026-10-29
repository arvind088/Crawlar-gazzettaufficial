package it.legislation.web;

import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import it.legislation.ingest.DailyUpdateRunner;
import it.legislation.ingest.IngestionWatermark;

/**
 * How recent the data is: the day the last successful daily update ran up to.
 *
 * <p>Read from the daily update's watermark ({@code data/registry/ingestion_watermarks.tsv}),
 * so the page says how fresh the legislation is, not just that the database answers.
 */
@Component
public class DataFreshness {

    private static final Pattern WITHOUT_TEXT = Pattern.compile("(\\d+) without text");

    private final IngestionWatermark watermark;

    public DataFreshness() {
        this(IngestionWatermark.DEFAULT_PATH);
    }

    public DataFreshness(Path watermarkFile) {
        this.watermark = new IngestionWatermark(watermarkFile);
    }

    /** The last day the daily update covered, or empty when it has never succeeded. */
    public Optional<LocalDate> updatedTo() {
        try {
            return watermark.lastSuccessfulEnd(DailyUpdateRunner.SOURCE)
                    .map(end -> end.atZoneSameInstant(ZoneOffset.UTC).toLocalDate());
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }

    /**
     * How many acts Normattiva lists but sends without text (an empty export), as the
     * last successful daily update counted them. They are asked for again at every run.
     */
    public OptionalInt withoutText() {
        try {
            return watermark.lastSuccessNote(DailyUpdateRunner.SOURCE)
                    .map(WITHOUT_TEXT::matcher).filter(Matcher::find)
                    .map(m -> OptionalInt.of(Integer.parseInt(m.group(1)))).orElse(OptionalInt.empty());
        } catch (IOException | RuntimeException e) {
            return OptionalInt.empty();
        }
    }
}
