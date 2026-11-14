package ch.urinet.archiv.ki.domain.chat;

/** Ergebnis eines Laufs des Aufräumers (Requirement 5.4): nur Anzahlen. */
public record Aufgeraeumt(int nachrichten, int gespraeche) {
}
