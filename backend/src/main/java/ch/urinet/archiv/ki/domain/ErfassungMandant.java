package ch.urinet.archiv.ki.domain;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Was die Erfassung vom Mandanten braucht (design.md «Mandant», Mandanten-Einstellungen): Name für den Prompt, eigene
 * Arten und Kennungen ({@code uid}, {@code ibans}), an denen die Nachbearbeitung den Mandanten als Aussteller erkennt
 * (Req 7.5, 7.12). Ohne Kennungen ist der Mandant nie Aussteller.
 */
public record ErfassungMandant(String name, String uid, List<String> ibans, Set<String> eigeneArten) {

    public ErfassungMandant {
        Objects.requireNonNull(name, "name");
        ibans = List.copyOf(ibans);
        eigeneArten = Set.copyOf(eigeneArten);
    }
}
