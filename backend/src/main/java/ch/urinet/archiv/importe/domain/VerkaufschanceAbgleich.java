package ch.urinet.archiv.importe.domain;

import ch.urinet.archiv.importe.domain.KontaktAbgleich.Befund;
import ch.urinet.archiv.importe.domain.ZeilenPruefung.Ergebnis;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Abgleich einer Zeile des Verkaufschancen-Imports (Aufgabe 4.3; Req 13.9): Der Kontakt kommt über die Kontakt-ID aus
 * 21.CRM ({@code kontakt_crm_id} → {@code kontakt.crm_id}), eine bestehende Verkaufschance über ihre {@code crm_id}.
 * Ohne Kontakt-ID, ohne passenden Kontakt oder mit ungültigem Betrag bzw. Datum ist die Zeile «fehlerhaft»; ohne
 * {@code crm_id} ist sie immer neu. Rein: Kontakt und Bestand liefert der Aufrufer. Meldungen nennen nie Werte.
 */
public final class VerkaufschanceAbgleich {

    public static final String KONTAKT_FEHLT = "Kontakt-ID aus 21.CRM fehlt";
    public static final String KONTAKT_UNBEKANNT = "Kontakt aus 21.CRM nicht gefunden";
    public static final String BETRAG_UNGUELTIG = "Betrag ungültig";
    public static final String DATUM_UNGUELTIG = "Datum ungültig";

    /** Höchstens 10 Stellen vor und 2 nach dem Dezimalpunkt bzw. -komma ({@code NUMERIC(12,2)}, tech.md Regel 9). */
    private static final Pattern BETRAG = Pattern.compile("^-?\\d{1,10}(?:[.,]\\d{1,2})?$");
    /** {@code TT.MM.JJ} bzw. {@code TT.MM.JJJJ}, optional mit Uhrzeit (21.CRM, design.md «Profil 21.CRM»). */
    private static final Pattern DATUM_CH =
            Pattern.compile("^(\\d{1,2})\\.(\\d{1,2})\\.(\\d{4}|\\d{2})(?:\\s+\\d{1,2}:\\d{2}(?::\\d{2})?)?$");
    private static final Pattern DATUM_ISO = Pattern.compile("^(\\d{4})-(\\d{2})-(\\d{2})$");

    private VerkaufschanceAbgleich() {
    }

    /** Werte einer Zeile aus {@link Zuordnung#werte} (leere Werte fehlen dort); ungültiger Betrag bzw. Datum → Fehler. */
    public static Gelesen lesen(Map<String, String> felder) {
        String betragText = felder.get(ImportFeld.BETRAG);
        Optional<BigDecimal> betrag = betragText == null ? Optional.empty() : betrag(betragText);
        if (betragText != null && betrag.isEmpty()) {
            return new Gelesen(null, BETRAG_UNGUELTIG);
        }
        String datumText = felder.get(ImportFeld.DATUM);
        Optional<LocalDate> datum = datumText == null ? Optional.empty() : datum(datumText);
        if (datumText != null && datum.isEmpty()) {
            return new Gelesen(null, DATUM_UNGUELTIG);
        }
        return new Gelesen(new Werte(felder.get(ImportFeld.CRM_ID), felder.get(ImportFeld.KONTAKT_CRM_ID),
                felder.get(ImportFeld.TITEL), felder.get(ImportFeld.PHASE), betrag.orElse(null), datum.orElse(null)),
                null);
    }

    /** Betrag mit Apostroph als Tausendertrennzeichen ({@code 1'234.50}), Punkt oder Komma als Dezimaltrenner. */
    static Optional<BigDecimal> betrag(String text) {
        String bereinigt = text.strip().replaceAll("['’\\s\\u00a0\\u202f]", "");
        if (!BETRAG.matcher(bereinigt).matches()) {
            return Optional.empty();
        }
        return Optional.of(new BigDecimal(bereinigt.replace(',', '.')).setScale(2, RoundingMode.UNNECESSARY));
    }

    /** Datum {@code TT.MM.JJ(JJ)} (zweistellige Jahre ab 2000), optional mit Uhrzeit, oder {@code JJJJ-MM-TT}. */
    static Optional<LocalDate> datum(String text) {
        String t = text.strip();
        try {
            Matcher ch = DATUM_CH.matcher(t);
            if (ch.matches()) {
                int jahr = Integer.parseInt(ch.group(3));
                return Optional.of(LocalDate.of(ch.group(3).length() == 2 ? 2000 + jahr : jahr,
                        Integer.parseInt(ch.group(2)), Integer.parseInt(ch.group(1))));
            }
            Matcher iso = DATUM_ISO.matcher(t);
            if (iso.matches()) {
                return Optional.of(LocalDate.of(Integer.parseInt(iso.group(1)), Integer.parseInt(iso.group(2)),
                        Integer.parseInt(iso.group(3))));
            }
        } catch (DateTimeException e) {
            return Optional.empty();
        }
        return Optional.empty();
    }

    /**
     * Befund einer gelesenen Zeile: {@code kontaktId} ist der Kontakt mit der Kontakt-ID aus 21.CRM (fehlt, wenn es
     * keinen gibt), {@code bestand} die Verkaufschance mit derselben {@code crm_id} (fehlt, wenn es keine gibt).
     * Verglichen werden nur die gefüllten Werte der Zeile; der Befund trägt den Kontakt der Zeile.
     */
    public static Befund pruefen(Gelesen gelesen, UUID kontaktId, Bestand bestand) {
        if (gelesen.fehler() != null) {
            return fehler(gelesen.fehler());
        }
        Werte w = gelesen.werte();
        if (w.kontaktCrmId() == null) {
            return fehler(KONTAKT_FEHLT);
        }
        if (kontaktId == null) {
            return fehler(KONTAKT_UNBEKANNT);
        }
        if (bestand == null || w.crmId() == null) {
            return new Befund(Ergebnis.NEU, null, kontaktId, List.of());
        }
        boolean geaendert = !kontaktId.equals(bestand.kontaktId())
                || anders(w.titel(), bestand.titel()) || anders(w.phase(), bestand.phase())
                || (w.betrag() != null && (bestand.betrag() == null || w.betrag().compareTo(bestand.betrag()) != 0))
                || anders(w.datum(), bestand.datum());
        return new Befund(geaendert ? Ergebnis.GEAENDERT : Ergebnis.UNVERAENDERT, null, kontaktId, List.of());
    }

    /** Fingerabdruck der {@code crm_id}: Zeilen derselben Datei mit derselben {@code crm_id} sind doppelt. */
    public static long[] fingerabdruecke(Werte werte) {
        return werte == null || werte.crmId() == null ? new long[0]
                : new long[]{KontaktAbgleich.fingerabdruck("verkaufschance\u0000" + werte.crmId())};
    }

    private static boolean anders(Object neu, Object alt) {
        return neu != null && !neu.equals(alt);
    }

    private static Befund fehler(String meldung) {
        return new Befund(Ergebnis.FEHLER, meldung, null, List.of());
    }

    /** Gelesene Werte; leere Felder sind {@code null}. */
    public record Werte(String crmId, String kontaktCrmId, String titel, String phase, BigDecimal betrag,
                        LocalDate datum) {
    }

    /** Werte oder der Grund, warum die Zeile «fehlerhaft» ist. */
    public record Gelesen(Werte werte, String fehler) {
        public Gelesen {
            if ((werte == null) == (fehler == null)) {
                throw new IllegalArgumentException("Entweder Werte oder Fehler");
            }
        }
    }

    /** Gespeicherte Verkaufschance mit derselben {@code crm_id}. */
    public record Bestand(UUID id, UUID kontaktId, String titel, String phase, BigDecimal betrag, LocalDate datum) {
        public Bestand {
            Objects.requireNonNull(id, "id");
        }
    }
}
