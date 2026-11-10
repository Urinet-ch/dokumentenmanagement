package ch.urinet.archiv.verarbeitung.application;

import ch.urinet.archiv.verarbeitung.domain.AuftragFehler;

/** Ein Auftrag im Status {@code FEHLER} mit dem Fehler seines letzten Versuchs (nur Art und Fehler-ID). */
public record Fehlgeschlagen(Auftrag auftrag, AuftragFehler fehler) {
}
