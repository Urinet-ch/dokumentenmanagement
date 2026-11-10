package ch.urinet.archiv.verarbeitung.application;

/**
 * Verwaister Auftrag ({@code LAEUFT} ohne Lebenszeichen seit der Zeitgrenze) mit dem Zustand seines Mandanten: Der
 * Aufräumer gibt verwaiste Aufträge aller Mandanten frei, ruft den Hook nach dem letzten Fehlversuch aber nur für
 * aktive Mandanten sofort auf; für inaktive pausiert die Nachbearbeitung.
 */
public record Verwaist(Auftrag auftrag, boolean mandantAktiv) {
}
