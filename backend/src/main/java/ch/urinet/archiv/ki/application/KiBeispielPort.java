package ch.urinet.archiv.ki.application;

import ch.urinet.archiv.ki.domain.Beispiel;
import ch.urinet.archiv.ki.domain.BeispielSicht;
import ch.urinet.archiv.ki.domain.Feld;
import ch.urinet.archiv.ki.domain.Merkmale;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Driven Port zu {@code ki_beispiel} im Schema des Mandanten aus dem Kontext (Req 7.7, 7.9; design.md «KI»). Liest
 * dazu aus {@code dokument} nur, was die Korrektur und die Sicht brauchen; nie Inhalte ins Log.
 */
public interface KiBeispielPort {

    /** Stand vor dem Bestätigen; leer ohne KI-Vorschlag (dann gibt es nichts zu lernen). */
    Optional<Lernstand> lernstand(UUID dokumentId);

    /** {@code erstellt_von} des Dokuments. */
    Optional<String> erfasser(UUID dokumentId);

    /** Speichert in der laufenden Transaktion: Scheitert die Bestätigung, entfällt auch das Beispiel. */
    void speichern(Neu beispiel);

    /**
     * Beispiele mit mindestens einem gemeinsamen Merkmal, deren Dokument bestätigt ist und die die Sicht nach dem
     * aktuellen Stand des Dokuments erlaubt; die meisten gemeinsamen Merkmale zuerst, dann die neuesten.
     */
    List<Beispiel> kandidaten(Merkmale merkmale, BeispielSicht sicht, int hoechstens);

    /** Die neuesten Beispiele, deren Dokument bestätigt ist und die die Sicht erlaubt. */
    List<Beispiel> neueste(BeispielSicht sicht, int anzahl);

    /**
     * @param vorschlag   Werte aus {@code ki_vorschlag} je Feld (Text, wie gespeichert)
     * @param volltext    Volltext des Belegs für die Merkmale
     * @param partnerIban vorgeschlagene IBAN des Partners ({@code ki_vorschlag.partnerIban})
     * @param partnerId   Kontakt, den der Abgleich beim KI-Vorschlag verknüpft hat ({@code ki_partner_id}, Req 7.4);
     *                    eine Änderung des Benutzers im Eingangskorb ändert ihn nicht
     */
    record Lernstand(Map<Feld, String> vorschlag, String volltext, Optional<String> partnerIban,
                     Optional<UUID> partnerId) {
        public Lernstand {
            vorschlag = Map.copyOf(vorschlag);
            volltext = volltext == null ? "" : volltext;
            Objects.requireNonNull(partnerIban, "partnerIban");
            Objects.requireNonNull(partnerId, "partnerId");
        }
    }

    record Neu(UUID dokumentId, String art, Map<Feld, String> korrektur, Merkmale merkmale, Optional<UUID> partnerId) {
        public Neu {
            Objects.requireNonNull(dokumentId, "dokumentId");
            Objects.requireNonNull(art, "art");
            Objects.requireNonNull(korrektur, "korrektur");
            Objects.requireNonNull(merkmale, "merkmale");
            Objects.requireNonNull(partnerId, "partnerId");
        }
    }
}
