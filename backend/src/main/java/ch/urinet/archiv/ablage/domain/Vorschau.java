package ch.urinet.archiv.ablage.domain;

import java.util.Objects;
import java.util.UUID;

/**
 * Schlüssel der Vorschaubilder (design.md «Ablage»): {@code vorschau/<docId>/<seite>.png}, ohne Jahr und ohne Sperre,
 * jederzeit aus dem PDF/A regenerierbar.
 */
public final class Vorschau {

    public static final String PRAEFIX = "vorschau";

    private Vorschau() {
    }

    public static String ordner(UUID docId) {
        return PRAEFIX + "/" + Objects.requireNonNull(docId, "docId") + "/";
    }

    public static String schluessel(UUID docId, int seite) {
        if (seite < 1 || seite > 99_999) {
            throw new IllegalArgumentException("Ungültige Seite: " + seite);
        }
        return ordner(docId) + seite + ".png";
    }
}
