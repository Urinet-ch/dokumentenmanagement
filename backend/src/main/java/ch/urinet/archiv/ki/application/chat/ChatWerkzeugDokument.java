package ch.urinet.archiv.ki.application.chat;

import java.util.Objects;
import java.util.UUID;

/** Werkzeugname und Dokument-ID eines gespeicherten Gesprächsergebnisses, ohne Parameter oder Inhalte. */
public record ChatWerkzeugDokument(String werkzeug, UUID dokumentId) {

    public ChatWerkzeugDokument {
        Objects.requireNonNull(werkzeug, "werkzeug");
        Objects.requireNonNull(dokumentId, "dokumentId");
    }
}
