package ch.urinet.archiv.ki.domain.chat;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Gespeicherter Werkzeugaufruf ({@code chat_nachricht.werkzeuge}, je Aufruf {@code {name, parameter, dokumentIds,
 * datenstand?, kontextstufe?}}; design.md «Vertrag Welle 7», Speicherung): nur IDs, nie Titel oder Dokumentinhalte.
 */
public record WerkzeugAufruf(String name, Map<String, Object> parameter, List<UUID> dokumentIds,
                             Optional<ChatEreignis.Datenstand> datenstand,
                             Optional<ChatEreignis.Kontextstufe> kontextstufe) {

    public WerkzeugAufruf {
        Objects.requireNonNull(name, "name");
        parameter = Collections.unmodifiableMap(new LinkedHashMap<>(Objects.requireNonNull(parameter, "parameter")));
        dokumentIds = List.copyOf(dokumentIds);
        Objects.requireNonNull(datenstand, "datenstand");
        Objects.requireNonNull(kontextstufe, "kontextstufe");
    }

    public WerkzeugAufruf(String name, Map<String, Object> parameter, List<UUID> dokumentIds,
                          Optional<ChatEreignis.Datenstand> datenstand) {
        this(name, parameter, dokumentIds, datenstand, Optional.empty());
    }

    public static WerkzeugAufruf aus(ChatEreignis.WerkzeugErgebnis ergebnis) {
        var dokumentIds = java.util.stream.Stream.concat(
                ergebnis.dokumente().stream().map(ChatEreignis.QuellDokument::id),
                ergebnis.wissen().stream().flatMap(w -> w.alleDokumentIds().stream())).distinct().toList();
        return new WerkzeugAufruf(ergebnis.name(), ergebnis.parameter(),
                dokumentIds, ergebnis.datenstand(), ergebnis.kontextstufe());
    }
}
