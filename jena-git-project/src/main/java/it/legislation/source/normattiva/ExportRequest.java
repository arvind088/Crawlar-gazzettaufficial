package it.legislation.source.normattiva;

import java.util.Objects;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Asks the Normattiva asynchronous export for one act, identified by type,
 * date of the act and number (for example DECRETO LEGISLATIVO, 2003-06-30, 196).
 *
 * @param format    export format, for example {@code AKN} or {@code JSON}
 * @param mode      which versions: {@link Mode#ORIGINAL}, {@link Mode#CURRENT} or {@link Mode#ALL_VERSIONS}
 * @param actType   Normattiva "denominazioneAtto", for example {@code LEGGE}, {@code DECRETO-LEGGE},
 *                  {@code DECRETO LEGISLATIVO}
 */
public record ExportRequest(String format, Mode mode, String actType, int year, int month, int day, int number) {

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
        if (year < 1800 || month < 1 || month > 12 || day < 1 || day > 31 || number < 1) {
            throw new IllegalArgumentException("Invalid act coordinates: " + year + "-" + month + "-" + day + " n. " + number);
        }
    }

    public static ExportRequest akn(Mode mode, String actType, int year, int month, int day, int number) {
        return new ExportRequest("AKN", mode, actType, year, month, day, number);
    }

    /** Body for {@code POST ricerca-asincrona/nuova-ricerca}. */
    public String toJson(ObjectMapper json) {
        ObjectNode body = json.createObjectNode();
        body.put("formato", format);
        body.put("tipoRicerca", "A");
        body.put("richiestaExport", mode.code());
        body.put("modalita", "C");
        ObjectNode params = body.putObject("parametriRicerca");
        params.put("denominazioneAtto", actType);
        params.put("annoProvvedimento", year);
        params.put("meseProvvedimento", month);
        params.put("giornoProvvedimento", day);
        params.put("numeroProvvedimento", number);
        params.put("limitaAnniVigenza", false);
        return body.toString();
    }

    /** Short stable name for files, for example {@code DECRETO_LEGISLATIVO_2003-06-30_196_M_AKN}. */
    public String fileStem() {
        return String.format("%s_%04d-%02d-%02d_%d_%s_%s",
                actType.replaceAll("[^A-Za-z0-9]+", "_"), year, month, day, number, mode.code(), format);
    }
}
