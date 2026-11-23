package ch.urinet.archiv.ki.domain.chat;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Gespeicherter Werkzeugaufruf ({@code chat_nachricht.werkzeuge}, je Aufruf {@code {name, parameter, dokumentIds,
 * datenstand?, kontextstufe?, suchweg?}}; design.md «Vertrag Welle 7», Speicherung): nur IDs, nie Titel oder Dokumentinhalte.
 */
public record WerkzeugAufruf(String name, Map<String, Object> parameter, List<UUID> dokumentIds,
                             Optional<ChatEreignis.Datenstand> datenstand,
                             Optional<ChatEreignis.Kontextstufe> kontextstufe, String suchweg,
                             Map<UUID, Double> aehnlichkeiten) {

    public WerkzeugAufruf {
        Objects.requireNonNull(name, "name");
        parameter = Collections.unmodifiableMap(new LinkedHashMap<>(Objects.requireNonNull(parameter, "parameter")));
        dokumentIds = List.copyOf(dokumentIds);
        Objects.requireNonNull(datenstand, "datenstand");
        Objects.requireNonNull(kontextstufe, "kontextstufe");
        aehnlichkeiten = Collections.unmodifiableMap(new LinkedHashMap<>(aehnlichkeiten));
    }

    public WerkzeugAufruf(String name, Map<String, Object> parameter, List<UUID> dokumentIds,
                          Optional<ChatEreignis.Datenstand> datenstand) {
        this(name, parameter, dokumentIds, datenstand, Optional.empty(), null, Map.of());
    }

    public WerkzeugAufruf(String name, Map<String, Object> parameter, List<UUID> dokumentIds,
                          Optional<ChatEreignis.Datenstand> datenstand,
                          Optional<ChatEreignis.Kontextstufe> kontextstufe) {
        this(name, parameter, dokumentIds, datenstand, kontextstufe, null, Map.of());
    }

    public static WerkzeugAufruf aus(ChatEreignis.WerkzeugErgebnis ergebnis) {
        var dokumentIds = java.util.stream.Stream.concat(
                ergebnis.dokumente().stream().map(ChatEreignis.QuellDokument::id),
                ergebnis.wissen().stream().flatMap(w -> w.alleDokumentIds().stream())).distinct().toList();
        Map<UUID, Double> aehnlichkeiten = ergebnis.dokumente().stream()
                .filter(d -> d.aehnlichkeit() != null)
                .collect(Collectors.toMap(ChatEreignis.QuellDokument::id, ChatEreignis.QuellDokument::aehnlichkeit,
                        (erste, ignoriert) -> erste));
        return new WerkzeugAufruf(ergebnis.name(), ergebnis.parameter(),
                dokumentIds, ergebnis.datenstand(), ergebnis.kontextstufe(), ergebnis.suchweg(), aehnlichkeiten);
    }
}
