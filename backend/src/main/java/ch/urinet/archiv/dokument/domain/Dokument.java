package ch.urinet.archiv.dokument.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Metadaten eines Dokuments, wie sie ein Benutzer lesen darf, der es sieht (Req 2.3). {@code vertraulichkeit} ist die
 * gespeicherte Vertraulichkeit; ohne bestätigte Art wirkt {@code PERSONAL} ({@link #wirksameVertraulichkeit()},
 * Req 2.21). {@code zusatz} sind die freien Zusatzfelder als JSON-Objekt (Req 9.5). Für den Eingangskorb (Req 8.1,
 * 8.7) kommen Fehlertext, PDF/A-Kennzeichen, Seitenzahl, Partner sowie Vorschlag und Sicherheiten der KI als JSON
 * hinzu (Aufgabe 3.6).
 */
public record Dokument(UUID id, String art, String titel, LocalDate datum, Instant eingang, String quelle,
                       List<String> tags, String vertraulichkeit, String personBenutzer, String erstelltVon,
                       DokumentStatus status, Instant bestaetigt, Integer geschaeftsjahr, LocalDate aufbewahrenBis,
                       LocalDate sperreBis, boolean fristAbgelaufen, String zusatz, int version, String fehler,
                       boolean pdfaFehlt, Integer seiten, UUID partnerId, String partnerName, String kiVorschlag,
                       String kiSicherheit, String projekt) {

    public static final String PERSONAL = "PERSONAL";

    public Dokument {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(status, "status");
        tags = tags == null ? List.of() : List.copyOf(tags);
    }

    public boolean bestaetigtesDokument() {
        return bestaetigt != null;
    }

    public String wirksameVertraulichkeit() {
        return bestaetigtesDokument() ? vertraulichkeit : PERSONAL;
    }
}
