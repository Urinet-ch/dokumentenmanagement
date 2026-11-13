package ch.urinet.archiv.dokument.application;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Neues Dokument aus dem Upload (design.md «Upload»); Status {@code NEU}, ohne Art.
 *
 * @param dateiname bereinigter Name der empfangenen Datei ({@code DateiName.speicherbar()}, Req 3.9, 11.12)
 */
public record NeuesDokument(UUID id, Instant eingang, String quelle, String erstelltVon, Optional<String> hinweis,
                            Optional<String> dateiname) {

    public NeuesDokument {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(eingang, "eingang");
        Objects.requireNonNull(quelle, "quelle");
        Objects.requireNonNull(erstelltVon, "erstelltVon");
        Objects.requireNonNull(hinweis, "hinweis");
        Objects.requireNonNull(dateiname, "dateiname");
    }
}
