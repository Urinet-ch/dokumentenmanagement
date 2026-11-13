package ch.urinet.archiv.ki.application;

/**
 * Driven Port für {@code ki_verbrauch} im Schema des Mandanten aus dem Kontext (design.md «KI»): eine Zeile je Aufruf
 * des Sprachmodells, in einer eigenen Transaktion, damit der Verbrauch auch bei einem späteren Fehler bleibt.
 */
public interface KiVerbrauchPort {

    String ERFASSUNG = "ERFASSUNG";

    void erfassen(String zweck, KiPort.Verbrauch verbrauch);
}
