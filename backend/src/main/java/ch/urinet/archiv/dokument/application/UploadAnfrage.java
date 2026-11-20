package ch.urinet.archiv.dokument.application;

import ch.urinet.archiv.sicherheit.domain.Benutzer;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Eingabe des Uploads (design.md «Upload»): je Seite das Original als temporäre Datei des Servers mit dem Namen aus dem
 * Upload (nur zur Anzeige), von der Handy-App dazu die Arbeitsfassung (Req 4.7).
 */
public record UploadAnfrage(List<Seite> seiten, boolean zusammenfassen, Optional<String> hinweis, Erfasser erfasser,
                            String art, Map<String, List<String>> klassifikationen) {

    public UploadAnfrage(List<Seite> seiten, boolean zusammenfassen, Optional<String> hinweis, Erfasser erfasser) {
        this(seiten, zusammenfassen, hinweis, erfasser, null, Map.of());
    }

    public UploadAnfrage {
        seiten = List.copyOf(seiten);
        Objects.requireNonNull(hinweis, "hinweis");
        Objects.requireNonNull(erfasser, "erfasser");
        art = art == null || art.isBlank() ? null : art.strip();
        Map<String, List<String>> kopie = new LinkedHashMap<>();
        if (klassifikationen != null) {
            klassifikationen.forEach((code, werte) -> kopie.put(code, List.copyOf(werte)));
        }
        klassifikationen = Map.copyOf(kopie);
    }

    public record Seite(Path original, String name, Optional<Path> arbeitsfassung) {
        public Seite {
            Objects.requireNonNull(original, "original");
            Objects.requireNonNull(arbeitsfassung, "arbeitsfassung");
        }
    }

    /**
     * Wer erfasst: {@code id} geht nach {@code erstellt_von} (Benutzer-ID oder {@code token:<id>}); {@code benutzer}
     * fehlt bei API-Tokens, die keine Archiv-Rolle haben und darum nie eine {@code vorhandenId} erhalten (Req 2.3, 3.4).
     */
    public record Erfasser(String id, Optional<Benutzer> benutzer, String quelle) {
        public Erfasser {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(benutzer, "benutzer");
            Objects.requireNonNull(quelle, "quelle");
        }
    }
}
