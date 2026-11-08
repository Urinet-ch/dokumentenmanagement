package ch.urinet.archiv.spike.ki;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** Antwortform wie in design.md (Abschnitt KI), um das JSON-Schema realistisch zu prüfen. */
public record MetadatenVorschlag(
        String art,
        String titel,
        LocalDate datum,
        String partnerName,
        String partnerUid,
        String partnerIban,
        String partnerEmail,
        String belegnummer,
        Richtung richtung,
        BigDecimal betrag,
        String waehrung,
        LocalDate faelligkeit,
        String qrReferenz,
        Vertraulichkeit vertraulichkeit,
        List<String> tags,
        Map<String, Double> sicherheit) {

    public enum Richtung { EINGANG, AUSGANG }

    public enum Vertraulichkeit { NORMAL, INTERN, PERSONAL }
}
