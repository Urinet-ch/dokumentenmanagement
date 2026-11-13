package ch.urinet.archiv.ki.adapter.out.langchain4j;

import dev.langchain4j.model.output.structured.Description;

import java.math.BigDecimal;
import java.util.List;

/**
 * Antwortschema der Erfassung, 1:1 aus dem Golden-Lauf ({@code spikes/golden-lauf}, {@code MetadatenVorschlag}; design.md
 * «KI»): Reihenfolge, Namen, Beschreibungen und Aufzählungen bestimmen das JSON-Schema und damit die Kennung der
 * Prompt-Fassung ({@code ErfassungPromptTest}). Sicherheiten als {@link BigDecimal} statt {@code Double} (tech.md Regel 9,
 * ArchUnit), im Schema ebenfalls {@code number}.
 */
public record MetadatenVorschlag(
        @Description("Code der Dokumentart aus der Liste") ArtCode art,
        String titel,
        @Description("Belegdatum als ISO-Datum JJJJ-MM-TT") String datum,
        @Description("Gegenpartei aus Sicht des Mandanten, nie der Mandant selbst") String partnerName,
        String partnerUid,
        String partnerIban,
        String partnerEmail,
        String belegnummer,
        Richtung richtung,
        BigDecimal betrag,
        String waehrung,
        @Description("Zahlungsfrist als ISO-Datum JJJJ-MM-TT") String faelligkeit,
        String qrReferenz,
        Vertraulichkeit vertraulichkeit,
        List<String> tags,
        Sicherheit sicherheit) {

    public enum Richtung { EINNAHME, AUSGABE }

    /** Codes der Seed-Tabelle, fremde Arten zuerst (Prompt v6); als Enum im Schema, damit das Modell keine fremden Werte liefert. */
    public enum ArtCode {
        LIEFERANTENBELEG, QUITTUNG, VERTRAG, VERSICHERUNG, BEHOERDE, BANK, PROJEKTDOKUMENT, KORRESPONDENZ,
        AUSGANGSRECHNUNG, OFFERTE, LOHNABRECHNUNG
    }

    public enum Vertraulichkeit { NORMAL, INTERN, PERSONAL }

    @Description("Sicherheit je Feld zwischen 0 und 1")
    public record Sicherheit(BigDecimal art, BigDecimal titel, BigDecimal datum, BigDecimal partnerName,
                             BigDecimal belegnummer, BigDecimal richtung, BigDecimal betrag, BigDecimal waehrung,
                             BigDecimal faelligkeit, BigDecimal vertraulichkeit) {
    }
}
