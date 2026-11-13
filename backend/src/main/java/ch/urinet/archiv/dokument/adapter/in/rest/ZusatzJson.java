package ch.urinet.archiv.dokument.adapter.in.rest;

import ch.urinet.archiv.gemeinsam.domain.FachFehler;
import ch.urinet.archiv.gemeinsam.domain.Fehlercode;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Zusatzwerte aus dem REST-Body als JSON-Text für Prüfer und Speicherung (Req 9.5): Derselbe Text geht an den
 * Prüfer und nach {@code dokument.zusatz}. Die Leser von {@code PATCH /dokumente/{id}} und Generator lesen Kommazahlen
 * als {@code BigDecimal} ({@code USE_BIG_DECIMAL_FOR_FLOATS}), und der Prüfer liest den Text ebenfalls genau; so prüft
 * er genau die Zahl, die gespeichert wird, und lehnt Zahlen ab, die {@code jsonb} nicht verlustfrei und lesbar hält
 * ({@code 1e400}, {@code 1e-400}, {@code 1e-1200}; 422 {@code ZUSATZ_UNGUELTIG}, Review PR #83 und #95). Als
 * {@code double} gelesen würde {@code 1e400} zu {@code Infinity}, und {@code toString()} gäbe die Zeichenkette
 * {@code "Infinity"} weiter. Steht trotzdem eine nicht endliche Zahl im Baum, ist der Zusatz ungültig, statt still zu
 * Text zu werden.
 */
final class ZusatzJson {

    private ZusatzJson() {
    }

    static String text(JsonNode zusatz) {
        if (zusatz == null) {
            return null;
        }
        if (!endlich(zusatz)) {
            throw new FachFehler(Fehlercode.ZUSATZ_UNGUELTIG);
        }
        return zusatz.toString();
    }

    private static boolean endlich(JsonNode knoten) {
        if (knoten.isContainerNode()) {
            for (JsonNode kind : knoten) {
                if (!endlich(kind)) {
                    return false;
                }
            }
            return true;
        }
        return !(knoten.isDouble() || knoten.isFloat()) || Double.isFinite(knoten.doubleValue());
    }
}
