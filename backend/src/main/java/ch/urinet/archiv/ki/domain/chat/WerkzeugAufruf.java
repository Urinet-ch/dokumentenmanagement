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
 * datenstand?}}; design.md «Vertrag Welle 7», Speicherung): nur IDs, nie Titel oder Dokumentinhalte.
 */
public record WerkzeugAufruf(String name, Map<String, Object> parameter, List<UUID> dokumentIds,
                             Optional<ChatEreignis.Datenstand> datenstand) {

    public WerkzeugAufruf {
        Objects.requireNonNull(name, "name");
        parameter = Collections.unmodifiableMap(new LinkedHashMap<>(Objects.requireNonNull(parameter, "parameter")));
        dokumentIds = List.copyOf(dokumentIds);
        Objects.requireNonNull(datenstand, "datenstand");
    }

    public static WerkzeugAufruf aus(ChatEreignis.WerkzeugErgebnis ergebnis) {
        return new WerkzeugAufruf(ergebnis.name(), ergebnis.parameter(),
                ergebnis.dokumente().stream().map(ChatEreignis.QuellDokument::id).toList(), ergebnis.datenstand());
    }
}
