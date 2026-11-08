package ch.urinet.archiv.spike.ki;

import dev.langchain4j.model.output.structured.Description;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Variante B: Datum als ISO-Text, Sicherheit mit festen Feldern statt Map. */
public record MetadatenVorschlagIso(
        String art,
        String titel,
        @Description("Belegdatum als ISO-Datum JJJJ-MM-TT") String datum,
        String partnerName,
        String partnerUid,
        String partnerIban,
        String partnerEmail,
        String belegnummer,
        MetadatenVorschlag.Richtung richtung,
        BigDecimal betrag,
        String waehrung,
        @Description("Zahlungsfrist als ISO-Datum JJJJ-MM-TT, nur bei Rechnungen") String faelligkeit,
        String qrReferenz,
        MetadatenVorschlag.Vertraulichkeit vertraulichkeit,
        List<String> tags,
        Sicherheit sicherheit) {

    @Description("Sicherheit je Feld zwischen 0 und 1")
    public record Sicherheit(Double art, Double datum, Double partnerName, Double belegnummer, Double betrag) {
    }

    MetadatenVorschlag alsVorschlag() {
        Map<String, Double> s = new LinkedHashMap<>();
        if (sicherheit != null) {
            s.put("art", sicherheit.art());
            s.put("datum", sicherheit.datum());
            s.put("partnerName", sicherheit.partnerName());
            s.put("belegnummer", sicherheit.belegnummer());
            s.put("betrag", sicherheit.betrag());
        }
        return new MetadatenVorschlag(art, titel, datum(datum), partnerName, partnerUid, partnerIban, partnerEmail,
                belegnummer, richtung, betrag, waehrung, datum(faelligkeit), qrReferenz, vertraulichkeit, tags, s);
    }

    private static LocalDate datum(String iso) {
        try {
            return iso == null || iso.isBlank() ? null : LocalDate.parse(iso);
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
