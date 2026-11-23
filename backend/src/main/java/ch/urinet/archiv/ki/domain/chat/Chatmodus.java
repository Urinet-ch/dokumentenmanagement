package ch.urinet.archiv.ki.domain.chat;

import java.util.Optional;

/**
 * Modus eines Chat-Gesprächs (Spec 04, Requirement 21.1): {@link #ARCHIV} sucht in den Dokumenten (Standard, Verhalten
 * wie bisher), {@link #WISSEN} antwortet nur aus den freigegebenen Wissensseiten. Der Modus wird beim Anlegen des
 * Gesprächs gewählt und bleibt fest.
 */
public enum Chatmodus {
    ARCHIV,
    WISSEN;

    /** Genau die Namen {@code ARCHIV} und {@code WISSEN}; alles andere (auch andere Schreibweisen) ist unbekannt. */
    public static Optional<Chatmodus> aus(String wert) {
        for (Chatmodus modus : values()) {
            if (modus.name().equals(wert)) {
                return Optional.of(modus);
            }
        }
        return Optional.empty();
    }
}
