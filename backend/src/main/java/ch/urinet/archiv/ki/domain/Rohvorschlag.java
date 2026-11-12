package ch.urinet.archiv.ki.domain;

import java.math.BigDecimal;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Antwort des Sprachmodells nach dem Antwortschema, noch ohne Nachbearbeitung (design.md «KI»). Aufzählungen stehen
 * als Text ({@code art}, {@code richtung}, {@code vertraulichkeit}); Datumsangaben roh, wie sie das Modell liefert.
 * Jeder Wert kann fehlen; eine fehlende Sicherheit gilt als 0.
 */
public record Rohvorschlag(String art, String titel, String datum, String partnerName, String partnerUid,
                           String partnerIban, String partnerEmail, String belegnummer, String richtung,
                           BigDecimal betrag, String waehrung, String faelligkeit, String qrReferenz,
                           String vertraulichkeit, List<String> tags, Map<Feld, BigDecimal> sicherheit) {

    public Rohvorschlag {
        tags = tags == null ? List.of() : tags.stream().filter(t -> t != null && !t.isBlank()).map(String::strip)
                .toList();
        Map<Feld, BigDecimal> kopie = new EnumMap<>(Feld.class);
        if (sicherheit != null) {
            sicherheit.forEach((feld, wert) -> {
                if (wert != null) {
                    kopie.put(feld, wert);
                }
            });
        }
        sicherheit = kopie;
    }
}
