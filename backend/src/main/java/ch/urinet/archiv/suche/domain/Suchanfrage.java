package ch.urinet.archiv.suche.domain;

import ch.urinet.archiv.gemeinsam.domain.FachFehler;
import ch.urinet.archiv.gemeinsam.domain.Fehlercode;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Suchbegriff, Filter, Sortierung und Seite einer Suche (Req 14.1, 14.2, 14.7; Aufgabe 4.5). Alle Filter sind
 * optional und werden mit «und» verknüpft; mehrere Tags müssen alle am Dokument stehen. Die Grenzen halten Länge und
 * Kosten der Abfrage klein; jede Verletzung ist 400 {@code ANFRAGE_UNGUELTIG} ohne Wert in Antwort oder Log (Req 16.7).
 *
 * @param begriff        Suchbegriff für {@code websearch_to_tsquery}; leer gilt als keiner
 * @param art            Code der Dokumentart
 * @param partner        ID des Partners ({@code dokument.partner_id})
 * @param datumVon       Belegdatum ab (einschliesslich)
 * @param datumBis       Belegdatum bis (einschliesslich)
 * @param betragVon      Betrag ab (einschliesslich, ohne Umrechnung der Währung)
 * @param betragBis      Betrag bis (einschliesslich)
 * @param richtung       Richtung des Finanzbelegs
 * @param tags           Tags, die alle am Dokument stehen
 * @param status         Status des Dokuments; ohne Angabe alle ausser {@code VERWORFEN}
 * @param geschaeftsjahr Geschäftsjahr
 * @param belegnummer    exakte Belegnummer
 * @param sortierung     ohne Angabe {@code RELEVANZ} mit Suchbegriff, sonst {@code DATUM}
 * @param seite          ab 0
 * @param groesse        1–{@value #GROESSE_MAX}
 */
public record Suchanfrage(String begriff, String art, UUID partner, LocalDate datumVon, LocalDate datumBis,
                          BigDecimal betragVon, BigDecimal betragBis, Richtung richtung, List<String> tags,
                          String status, Integer geschaeftsjahr, String belegnummer, Sortierung sortierung, int seite,
                          int groesse) {

    public static final int BEGRIFF_MAX = 200;
    public static final int TAGS_MAX = 10;
    public static final int TAG_LAENGE_MAX = 100;
    public static final int BELEGNUMMER_MAX = 50;
    public static final int GROESSE_STANDARD = 20;
    public static final int GROESSE_MAX = 100;
    public static final int SEITE_MAX = 9_999;
    /** Grösster Betrag in {@code NUMERIC(12,2)}. */
    private static final BigDecimal BETRAG_MAX = new BigDecimal("9999999999.99");
    private static final Pattern ART = Pattern.compile("^[A-Z][A-Z0-9_]{1,39}$");
    private static final Pattern STATUS = Pattern.compile("^[A-Z_]{1,30}$");

    public Suchanfrage {
        begriff = begriff == null || begriff.isBlank() ? null : begriff.strip();
        tags = tags == null ? List.of() : List.copyOf(tags);
        pruefen(begriff == null || (begriff.length() <= BEGRIFF_MAX && ohneSteuerzeichen(begriff)));
        pruefen(art == null || ART.matcher(art).matches());
        pruefen(datumVon == null || datumBis == null || !datumVon.isAfter(datumBis));
        pruefen(betrag(betragVon) && betrag(betragBis));
        pruefen(betragVon == null || betragBis == null || betragVon.compareTo(betragBis) <= 0);
        pruefen(tags.size() <= TAGS_MAX && tags.stream().allMatch(
                t -> t != null && !t.isBlank() && t.length() <= TAG_LAENGE_MAX && ohneSteuerzeichen(t)));
        pruefen(status == null || STATUS.matcher(status).matches());
        pruefen(geschaeftsjahr == null || (geschaeftsjahr >= 1000 && geschaeftsjahr <= 9999));
        pruefen(belegnummer == null || (!belegnummer.isBlank() && belegnummer.length() <= BELEGNUMMER_MAX
                && ohneSteuerzeichen(belegnummer)));
        pruefen(seite >= 0 && seite <= SEITE_MAX && groesse >= 1 && groesse <= GROESSE_MAX);
    }

    /** Sortierung nach Vorgabe; ohne Suchbegriff gibt es keinen Rang, dann gilt {@code DATUM}. */
    public Sortierung wirksameSortierung() {
        if (begriff == null) {
            return Sortierung.DATUM;
        }
        return sortierung == null ? Sortierung.RELEVANZ : sortierung;
    }

    private static boolean betrag(BigDecimal wert) {
        return wert == null || (wert.stripTrailingZeros().scale() <= 2 && wert.abs().compareTo(BETRAG_MAX) <= 0);
    }

    private static boolean ohneSteuerzeichen(String text) {
        return text.chars().noneMatch(Character::isISOControl);
    }

    private static void pruefen(boolean bedingung) {
        if (!bedingung) {
            throw new FachFehler(Fehlercode.ANFRAGE_UNGUELTIG);
        }
    }
}
