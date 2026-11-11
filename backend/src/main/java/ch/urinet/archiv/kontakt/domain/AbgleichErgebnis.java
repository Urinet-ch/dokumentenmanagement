package ch.urinet.archiv.kontakt.domain;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Ergebnis des Partner-Abgleichs (Req 7.4): verknüpfen, «unsicher» (Auswahl durch den Benutzer) oder «neuer Partner?». */
public sealed interface AbgleichErgebnis {

    /** Genau ein Kontakt passt; {@code merkmal} nennt die Stufe, die ihn gefunden hat. */
    record Verknuepft(UUID kontaktId, Merkmal merkmal) implements AbgleichErgebnis {
        public Verknuepft {
            Objects.requireNonNull(kontaktId, "kontaktId");
            Objects.requireNonNull(merkmal, "merkmal");
        }
    }

    /** Mehrere Kontakte kommen in Frage; nichts wird verknüpft, die Kandidaten in absteigender Eignung. */
    record Unsicher(List<UUID> kandidaten, Merkmal merkmal) implements AbgleichErgebnis {
        public Unsicher {
            kandidaten = List.copyOf(kandidaten);
            Objects.requireNonNull(merkmal, "merkmal");
        }
    }

    /** Kein Kontakt passt: «neuer Partner?». */
    record NeuerPartner() implements AbgleichErgebnis {
    }
}
