package ch.urinet.archiv.dokument.adapter.in.rest;

import ch.urinet.archiv.gemeinsam.domain.FachFehler;
import ch.urinet.archiv.gemeinsam.domain.Fehlercode;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Zusatzwerte aus dem REST-Body als JSON-Text für den Prüfer (Req 9.5). Die Leser von {@code PATCH /dokumente/{id}}
 * und Generator lesen Kommazahlen als {@code BigDecimal} ({@code USE_BIG_DECIMAL_FOR_FLOATS}): So kommt eine Zahl
 * wie {@code 1e400} als Zahl beim Prüfer an, der sie als nicht endlich ablehnt (422 {@code ZUSATZ_UNGUELTIG}).
 * Als {@code double} gelesen würde sie {@code Infinity}, und {@code toString()} gäbe die Zeichenkette
 * {@code "Infinity"} weiter (Review PR #83, Soll 2). Steht trotzdem eine nicht endliche Zahl im Baum, ist der Zusatz
 * ungültig, statt still zu Text zu werden.
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
