package ch.urinet.archiv.spike.golden;

import dev.langchain4j.model.output.structured.Description;

import java.math.BigDecimal;
import java.util.List;

/** Antwortformat nach design.md «KI» (Datumsfelder als ISO-Text, Sicherheit als Record; Spike 0.3). */
public record MetadatenVorschlag(
        @Description("Name im Briefkopf (Absender), nicht der Empfänger im Adressfeld") String absender,
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

    /** Codes aus der Tabelle in design.md, fremde Arten zuerst (Prompt v5); als Enum im Schema, damit das Modell keine fremden Werte liefert. */
    public enum ArtCode {
        LIEFERANTENBELEG, QUITTUNG, VERTRAG, VERSICHERUNG, BEHOERDE, BANK, PROJEKTDOKUMENT, KORRESPONDENZ,
        AUSGANGSRECHNUNG, OFFERTE, LOHNABRECHNUNG
    }

    public enum Vertraulichkeit { NORMAL, INTERN, PERSONAL }

    @Description("Sicherheit je Feld zwischen 0 und 1")
    public record Sicherheit(Double art, Double titel, Double datum, Double partnerName, Double belegnummer,
                             Double richtung, Double betrag, Double waehrung, Double faelligkeit,
                             Double vertraulichkeit) {
    }
}
