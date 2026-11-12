package ch.urinet.archiv.suche.domain;

import java.util.Objects;

/**
 * Ein Stück der Textstelle eines Treffers (Req 14.4): {@code treffer} heisst, das Stück ist ein hervorgehobener
 * Suchbegriff. Der Text ist reiner Text ohne Markup; die Oberfläche hebt hervor, ohne HTML einzusetzen.
 */
public record AuszugTeil(String text, boolean treffer) {

    public AuszugTeil {
        Objects.requireNonNull(text, "text");
    }
}
