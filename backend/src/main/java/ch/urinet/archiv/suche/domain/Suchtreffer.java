package ch.urinet.archiv.suche.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Ein sichtbares Dokument in der Trefferliste (Req 14.1, 14.4) mit den Feldern für Liste und Filter;
 * {@code vertraulichkeit} ist die wirksame (ohne bestätigte Art {@code PERSONAL}, Req 2.21). Die Finanzfelder sind
 * {@code null}, wenn das Dokument keine hat. {@code dateiname} ist der Name der empfangenen Datei, {@code null} ohne
 * Namen oder in der Variante ohne Dateinamen (Req 3.13, 14.9).
 */
public record Suchtreffer(UUID id, String art, String titel, LocalDate datum, Instant eingang, String status,
                          String vertraulichkeit, List<String> tags, Integer geschaeftsjahr, UUID partnerId,
                          String partnerName, String belegnummer, BigDecimal betrag, String waehrung, String richtung,
                          List<AuszugTeil> auszug, String dateiname) {

    public Suchtreffer {
        Objects.requireNonNull(id, "id");
        tags = tags == null ? List.of() : List.copyOf(tags);
        auszug = auszug == null ? List.of() : List.copyOf(auszug);
    }
}
