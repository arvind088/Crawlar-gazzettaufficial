package it.legislation.model;

import java.time.LocalDate;
import java.util.List;

/**
 * One version of one act, as read from a Normattiva Akoma Ntoso file.
 *
 * <p>Identity comes from the Gazzetta Ufficiale publication (GU date and
 * codice redazionale), which Normattiva embeds in its ELI alias; that is the
 * same key Gazzetta uses, so both sources meet on one Work.
 *
 * @param codiceRedazionale Gazzetta identifier, for example {@code 20G00045}
 * @param gazzettaDate      date of the Gazzetta Ufficiale issue
 * @param gazzettaNumber    number of the Gazzetta Ufficiale issue
 * @param actType           act type as Normattiva writes it, for example {@code DECRETO LEGISLATIVO}
 * @param documentDate      date of the act
 * @param number            number of the act
 * @param title             title of the act
 * @param urnNir            Normattiva URN; kept as an alias, never used as an identifier
 * @param original          true for the original text, false for a later (consolidated) version
 * @param versionDate       date from which this text is in force
 * @param sourceFile        file the data came from, relative to the raw store
 * @param sha256            checksum of that file
 */
public record AknExpression(
        String codiceRedazionale,
        LocalDate gazzettaDate,
        String gazzettaNumber,
        String actType,
        LocalDate documentDate,
        String number,
        String title,
        String urnNir,
        boolean original,
        LocalDate versionDate,
        String sourceFile,
        String sha256) {

    /** A file that could not be read into an {@link AknExpression}, with the reasons. */
    public record Rejected(String sourceFile, List<String> problems) {}
}
