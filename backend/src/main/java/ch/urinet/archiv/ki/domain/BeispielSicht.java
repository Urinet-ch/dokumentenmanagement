package ch.urinet.archiv.ki.domain;

import java.util.Objects;

/**
 * Welche Beispiele in den Aufruf für einen Beleg dürfen: Den Vorschlag sieht der Erfasser des Belegs (Req 2.21), also nur
 * Beispiele, die er lesen darf. {@code PERSONAL} nur, wenn das Beispiel ihn selbst betrifft ({@code person_benutzer}),
 * auch für Rollen, die alle {@code PERSONAL}-Dokumente lesen: Der Vorschlag ist auch für andere Rollen mit Recht A7
 * sichtbar (vorsichtige Auslegung, PR #109).
 *
 * @param erfasser Kennung des Erfassers ({@code erstellt_von})
 * @param normal   darf bestätigte {@code NORMAL}-Dokumente lesen (A2); unbekannte Erfasser ja
 * @param intern   darf bestätigte {@code INTERN}-Dokumente lesen (A3); unbekannte oder ausgetretene Erfasser nein
 */
public record BeispielSicht(String erfasser, boolean normal, boolean intern) {

    public BeispielSicht {
        Objects.requireNonNull(erfasser, "erfasser");
    }

    public boolean erlaubt(String vertraulichkeit, String personBenutzer) {
        return switch (vertraulichkeit) {
            case "NORMAL" -> normal;
            case "INTERN" -> intern;
            case "PERSONAL" -> erfasser.equals(personBenutzer);
            default -> false;
        };
    }

    public boolean erlaubt(Beispiel beispiel) {
        return erlaubt(beispiel.vertraulichkeit(), beispiel.personBenutzer());
    }
}
