package ch.urinet.archiv.ki.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * KI-Vorschlag nach der Nachbearbeitung (Req 7.2, 7.5, 7.11, 7.12): Werte für den Eingangskorb und eine Sicherheit
 * zwischen 0 und 1 je {@link Feld}. {@code mandantIstAussteller} und {@code umstufung} dienen nur der Nachvollziehbarkeit
 * (Log mit IDs, Tests), nicht der Anzeige.
 */
public record Vorschlag(String art, String titel, LocalDate datum, String partnerName, String partnerUid,
                        String partnerIban, String partnerEmail, String belegnummer, String richtung, BigDecimal betrag,
                        String waehrung, LocalDate faelligkeit, String qrReferenz, String vertraulichkeit,
                        List<String> tags, Map<Feld, BigDecimal> sicherheit, boolean mandantIstAussteller,
                        String umstufung, Map<String, Object> dynamischeWerte,
                        Map<String, BigDecimal> dynamischeSicherheit) {

    public Vorschlag {
        tags = List.copyOf(tags);
        Map<Feld, BigDecimal> kopie = new EnumMap<>(Feld.class);
        kopie.putAll(sicherheit);
        sicherheit = java.util.Collections.unmodifiableMap(kopie);
        dynamischeWerte = dynamischeWerte == null ? Map.of() : Map.copyOf(dynamischeWerte);
        dynamischeSicherheit = dynamischeSicherheit == null ? Map.of() : Map.copyOf(dynamischeSicherheit);
    }

    public Vorschlag(String art, String titel, LocalDate datum, String partnerName, String partnerUid,
                     String partnerIban, String partnerEmail, String belegnummer, String richtung, BigDecimal betrag,
                     String waehrung, LocalDate faelligkeit, String qrReferenz, String vertraulichkeit,
                     List<String> tags, Map<Feld, BigDecimal> sicherheit, boolean mandantIstAussteller,
                     String umstufung) {
        this(art, titel, datum, partnerName, partnerUid, partnerIban, partnerEmail, belegnummer, richtung, betrag,
                waehrung, faelligkeit, qrReferenz, vertraulichkeit, tags, sicherheit, mandantIstAussteller,
                umstufung, Map.of(), Map.of());
    }

    public BigDecimal sicherheit(Feld feld) {
        return sicherheit.getOrDefault(feld, BigDecimal.ZERO);
    }
}
