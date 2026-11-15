package ch.urinet.archiv.export.domain;

/**
 * Art des letzten Fehlversuchs eines gescheiterten Exports (Art aus {@code auftrag.fehler}, design.md «Verarbeitung»):
 * {@code VERWAIST} heisst, der Worker hat nach seinem letzten Lebenszeichen nichts mehr gemeldet (z. B. Absturz der
 * JVM), {@code ZEITGRENZE} der Lauf überschritt die Zeitgrenze des Auftrags, {@code FEHLGESCHLAGEN} jeder andere Fehler.
 * Die Art trägt nie Werte (Req 16.7).
 */
public enum ExportFehlerart {
    VERWAIST, ZEITGRENZE, FEHLGESCHLAGEN;

    /** Art des Auftrags ({@code verwaist}, {@code zeitgrenze}, sonst {@code FEHLGESCHLAGEN}). */
    public static ExportFehlerart aus(String art) {
        return switch (art == null ? "" : art) {
            case "verwaist" -> VERWAIST;
            case "zeitgrenze" -> ZEITGRENZE;
            default -> FEHLGESCHLAGEN;
        };
    }
}
