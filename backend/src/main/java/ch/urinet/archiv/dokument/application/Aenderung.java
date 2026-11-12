package ch.urinet.archiv.dokument.application;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Änderung der Metadaten eines Dokuments (A6, Aufgabe 3.5): Nur die Felder in {@code gesetzt} ändern sich, ein gesetztes
 * Feld mit {@code null} leert es. {@code zusatz} ist das JSON-Objekt der Zusatzfelder, {@code zusatzFelder} nennt die
 * Zusatzfelder mit Wert darin (für die Pflichtfelder {@code zusatz.*}). {@code grund} ist kein Feld des Dokuments, sondern
 * der Grund der Änderung (Req 10.1).
 */
public record Aenderung(Set<String> gesetzt, String art, String titel, LocalDate datum, UUID partnerId, String projekt,
                        List<String> tags, String vertraulichkeit, String personBenutzer, Integer geschaeftsjahr,
                        String belegnummer, String richtung, BigDecimal betrag, String waehrung, LocalDate faelligkeit,
                        String qrReferenz, String konto, LocalDate gueltigAb, LocalDate gueltigBis,
                        String kuendigungsfrist, LocalDate naechsteFrist, String zusatz, Set<String> zusatzFelder,
                        String grund) {

    public static final String ART = "art";
    public static final String TITEL = "titel";
    public static final String DATUM = "datum";
    public static final String PARTNER = "partnerId";
    public static final String PROJEKT = "projekt";
    public static final String TAGS = "tags";
    public static final String VERTRAULICHKEIT = "vertraulichkeit";
    public static final String PERSON = "personBenutzer";
    public static final String GESCHAEFTSJAHR = "geschaeftsjahr";
    public static final String WAEHRUNG = "waehrung";
    public static final String GUELTIG_BIS = "gueltigBis";
    public static final String ZUSATZ = "zusatz";

    /** Felder der Gruppe {@code finanz} (Zahlstatus und AbaNinja-Felder gehören nie dazu, Regel 8). */
    public static final Set<String> FINANZ = Set.of("belegnummer", "richtung", "betrag", WAEHRUNG, "faelligkeit",
            "qrReferenz", "konto");
    public static final Set<String> VERTRAG = Set.of("gueltigAb", GUELTIG_BIS, "kuendigungsfrist", "naechsteFrist");
    /** Felder, die nur mit Recht A8 geändert werden dürfen (Req 2.14, 2.23). */
    public static final Set<String> NUR_A8 = Set.of(VERTRAULICHKEIT, PERSON);
    public static final Set<String> FELDER = Set.of(ART, TITEL, DATUM, PARTNER, PROJEKT, TAGS, VERTRAULICHKEIT, PERSON,
            GESCHAEFTSJAHR, "belegnummer", "richtung", "betrag", WAEHRUNG, "faelligkeit", "qrReferenz", "konto",
            "gueltigAb", GUELTIG_BIS, "kuendigungsfrist", "naechsteFrist", ZUSATZ);

    public Aenderung {
        gesetzt = Set.copyOf(gesetzt);
        if (!FELDER.containsAll(gesetzt)) {
            throw new IllegalArgumentException("Unbekanntes Feld in der Änderung");
        }
        zusatzFelder = zusatzFelder == null ? Set.of() : Set.copyOf(zusatzFelder);
    }

    public boolean hat(String feld) {
        return gesetzt.contains(feld);
    }

    public boolean hatEines(Set<String> felder) {
        return gesetzt.stream().anyMatch(felder::contains);
    }
}
