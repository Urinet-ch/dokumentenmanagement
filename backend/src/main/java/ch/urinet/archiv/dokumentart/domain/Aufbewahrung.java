package ch.urinet.archiv.dokumentart.domain;

import ch.urinet.archiv.gemeinsam.domain.FachFehler;
import ch.urinet.archiv.gemeinsam.domain.Fehlercode;

import java.time.Instant;
import java.time.LocalDate;
import java.time.MonthDay;
import java.time.ZoneId;
import java.util.Objects;

/**
 * Berechnung von {@code aufbewahren_bis} (Req 9.3, 9.7, 9.8; design.md «Dokumentart und Aufbewahrung»). Reine
 * Domain-Logik; {@code bis = null} heisst unbefristet.
 */
public record Aufbewahrung(LocalDate bis, boolean fristAbgelaufen) {

    /** Zeitzone, in der das Eingangsdatum gilt (tech.md, Regel 10). */
    public static final ZoneId ZUERICH = ZoneId.of("Europe/Zurich");
    static final int GESCHAEFTSJAHR_MIN = 1900;
    static final int GESCHAEFTSJAHR_MAX = 2999;

    /** Ergebnis der Neuberechnung einer bestehenden Frist (Req 9.7). */
    public enum Neuberechnung {
        /** neue Frist ist gleich */
        GLEICH,
        /** neue Frist ist später oder unbefristet: übernehmen */
        VERLAENGERN,
        /** neue Frist wäre früher: bisherige Frist belassen und Konflikt protokollieren */
        KONFLIKT
    }

    /**
     * Geschäftsjahr, in das das Belegdatum fällt (ohne Belegdatum: das Eingangsdatum in Europe/Zurich), bezeichnet nach
     * dem Kalenderjahr seines Endes. GJ-Ende {@code 06-30}: Beleg 15.08.2026 → 2027.
     */
    public static int geschaeftsjahrVorschlagen(LocalDate belegdatum, Instant eingang, MonthDay gjEnde) {
        Objects.requireNonNull(gjEnde, "gjEnde");
        if (belegdatum == null && eingang == null) {
            throw new FachFehler(Fehlercode.ANFRAGE_UNGUELTIG);
        }
        LocalDate datum = belegdatum != null ? belegdatum : LocalDate.ofInstant(eingang, ZUERICH);
        return datum.isAfter(gjEnde.atYear(datum.getYear())) ? datum.getYear() + 1 : datum.getYear();
    }

    /**
     * Berechnet die Frist für ein Dokument.
     *
     * @param geschaeftsjahr bestätigtes Geschäftsjahr; {@code null} → Vorschlag aus Belegdatum bzw. Eingang
     * @param gueltigBis     {@code gueltig_bis} des Vertrags (nur für {@link AufbewahrungAb#GUELTIG_BIS})
     * @param heute          heutiges Datum in Europe/Zurich, für {@code fristAbgelaufen} (Req 9.8)
     */
    public static Aufbewahrung berechne(Aufbewahrungsregel regel, LocalDate belegdatum, Instant eingang,
                                        Integer geschaeftsjahr, LocalDate gueltigBis, MonthDay gjEnde,
                                        LocalDate heute) {
        Objects.requireNonNull(regel, "regel");
        Objects.requireNonNull(gjEnde, "gjEnde");
        Objects.requireNonNull(heute, "heute");
        if (regel.unbefristet()) {
            return new Aufbewahrung(null, false);
        }
        LocalDate bis = switch (regel.ab()) {
            case GESCHAEFTSJAHR -> {
                int gj = geschaeftsjahr != null ? geschaeftsjahr
                        : geschaeftsjahrVorschlagen(belegdatum, eingang, gjEnde);
                if (gj < GESCHAEFTSJAHR_MIN || gj > GESCHAEFTSJAHR_MAX) {
                    throw new FachFehler(Fehlercode.ANFRAGE_UNGUELTIG);
                }
                yield gjEnde.atYear(gj + regel.jahre());
            }
            // plusYears setzt den 29.02. im Nicht-Schaltjahr auf den 28.02. (Art. 77 Abs. 3 OR)
            case GUELTIG_BIS -> gueltigBis == null ? null : gueltigBis.plusYears(regel.jahre());
        };
        return new Aufbewahrung(bis, bis != null && bis.isBefore(heute));
    }

    /** Vergleicht eine neu berechnete Frist mit der bestehenden (Req 9.7); {@code null} heisst unbefristet. */
    public static Neuberechnung entscheiden(LocalDate bisher, LocalDate neu) {
        if (Objects.equals(bisher, neu)) {
            return Neuberechnung.GLEICH;
        }
        if (bisher == null) {
            return Neuberechnung.KONFLIKT;
        }
        return neu == null || neu.isAfter(bisher) ? Neuberechnung.VERLAENGERN : Neuberechnung.KONFLIKT;
    }
}
