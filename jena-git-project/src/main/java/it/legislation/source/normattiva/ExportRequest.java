package it.legislation.source.normattiva;

import java.util.Objects;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Asks the Normattiva asynchronous export for acts of one type and year, optionally
 * narrowed to one month, day and number.
 *
 * <ul>
 *   <li>one act: {@link #akn(Mode, String, int, int, int, int)}, for example DECRETO LEGISLATIVO 2003-06-30 n. 196</li>
 *   <li>a slice of the corpus: {@link #slice(Mode, String, int, Integer)}, for example all DECRETO-LEGGE of 2024,
 *       or of March 2024</li>
 * </ul>
 *
 * @param format  export format, for example {@code AKN}
 * @param mode    which versions: {@link Mode#ORIGINAL}, {@link Mode#CURRENT} or {@link Mode#ALL_VERSIONS}
 * @param actType Normattiva "denominazioneAtto", for example {@code LEGGE}, {@code DECRETO-LEGGE},
 *                {@code DECRETO LEGISLATIVO}
 * @param year    year of the act (annoProvvedimento)
 * @param month   month of the act, or {@code null} for the whole year
 * @param day     day of the act, or {@code null}
 * @param number  number of the act, or {@code null}
 */
public record ExportRequest(String format, Mode mode, String actType, int year,
                            Integer month, Integer day, Integer number) {

    /** Normattiva "richiestaExport" letters. */
    public enum Mode {
        ORIGINAL("O"), CURRENT("V"), ALL_VERSIONS("M");

        private final String code;

        Mode(String code) {
            this.code = code;
        }

        public String code() {
            return code;
        }
    }

    public ExportRequest {
        Objects.requireNonNull(format, "format");
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(actType, "actType");
        if (year < 1800
                || (month != null && (month < 1 || month > 12))
                || (day != null && (day < 1 || day > 31))
                || (number != null && number < 1)) {
            throw new IllegalArgumentException("Invalid act coordinates: " + year + "-" + month + "-" + day + " n. " + number);
        }
        if (day != null && month == null) {
            throw new IllegalArgumentException("A day needs a month");
        }
    }

    /** One act, identified by type, date and number. */
    public static ExportRequest akn(Mode mode, String actType, int year, int month, int day, int number) {
        return new ExportRequest("AKN", mode, actType, year, month, day, number);
    }

    /** All acts of one type in one year, or in one month of that year when {@code month} is not null. */
    public static ExportRequest slice(Mode mode, String actType, int year, Integer month) {
        return new ExportRequest("AKN", mode, actType, year, month, null, null);
    }

    /** The search criteria shared by the export and by {@code ricerca/avanzata} (used to count first). */
    public ObjectNode criteria(ObjectMapper json) {
        ObjectNode params = json.createObjectNode();
        params.put("denominazioneAtto", actType);
        params.put("annoProvvedimento", year);
        if (month != null) {
            params.put("meseProvvedimento", month);
        }
        if (day != null) {
            params.put("giornoProvvedimento", day);
        }
        if (number != null) {
            params.put("numeroProvvedimento", number);
        }
        return params;
    }

    /** Body for {@code POST ricerca-asincrona/nuova-ricerca}. */
    public String toJson(ObjectMapper json) {
        ObjectNode body = json.createObjectNode();
        body.put("formato", format);
        body.put("tipoRicerca", "A");
        body.put("richiestaExport", mode.code());
        body.put("modalita", "C");
        ObjectNode params = criteria(json);
        params.put("limitaAnniVigenza", false);
        body.set("parametriRicerca", params);
        return body.toString();
    }

    /**
     * Short stable name for files: {@code DECRETO_LEGISLATIVO_2003-06-30_196_M_AKN} for one act,
     * {@code DECRETO_LEGGE_2024_M_AKN} or {@code DECRETO_LEGGE_2024-03_M_AKN} for a slice.
     */
    public String fileStem() {
        String type = actType.replaceAll("[^A-Za-z0-9]+", "_");
        StringBuilder stem = new StringBuilder(type).append('_').append(String.format("%04d", year));
        if (month != null) {
            stem.append(String.format("-%02d", month));
        }
        if (day != null) {
            stem.append(String.format("-%02d", day));
        }
        if (number != null) {
            stem.append('_').append(number);
        }
        return stem.append('_').append(mode.code()).append('_').append(format).toString();
    }
}
