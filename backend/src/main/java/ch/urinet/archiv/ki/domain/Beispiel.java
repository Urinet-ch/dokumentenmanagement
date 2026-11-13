package ch.urinet.archiv.ki.domain;

import java.time.Instant;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/**
 * Gespeichertes Korrekturbeispiel ({@code ki_beispiel}, Req 7.7): bestätigte Art, die bestätigten Werte der korrigierten
 * Felder, die Merkmale der Vorauswahl und die Vertraulichkeit des bestätigten Dokuments (filtert die Auswahl).
 */
public record Beispiel(long id, String art, Map<Feld, String> korrektur, Merkmale merkmale, String vertraulichkeit,
                       String personBenutzer, Instant zeit) {

    public Beispiel {
        Objects.requireNonNull(art, "art");
        korrektur = Collections.unmodifiableMap(korrektur.isEmpty() ? new EnumMap<>(Feld.class)
                : new EnumMap<>(korrektur));
        Objects.requireNonNull(merkmale, "merkmale");
        Objects.requireNonNull(vertraulichkeit, "vertraulichkeit");
        Objects.requireNonNull(zeit, "zeit");
    }
}
