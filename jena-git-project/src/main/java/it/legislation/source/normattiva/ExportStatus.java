package it.legislation.source.normattiva;

/**
 * State of an asynchronous export as reported by {@code ricerca-asincrona/check-status}.
 *
 * @param code     Normattiva "stato": 0 to confirm, 1 waiting, 2 processing, 3 completed,
 *                 4 failed, 5 overloaded, 6 confirmed with delay
 * @param location download location from the {@code x-ipzs-location} header, when present
 * @param message  error or status description, when present
 */
public record ExportStatus(int code, String location, String message) {

    public static final int COMPLETED = 3;
    public static final int FAILED = 4;
    public static final int OVERLOADED = 5;

    public boolean isDone() {
        return code == COMPLETED || code == FAILED || code == OVERLOADED;
    }

    public boolean isCompleted() {
        return code == COMPLETED;
    }
}
