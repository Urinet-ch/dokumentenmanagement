package ch.urinet.archiv.konfiguration.adapter.in.rest;

import ch.urinet.archiv.gemeinsam.adapter.StrengesJson;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;

/**
 * Einstellungen der Wissensbasis in Konfigurationsdatei und Versionsabbild, solange Wissen für den Mandanten nicht
 * freigeschaltet ist (Spec 04, Req 22.6): Export und Versionsdetails zeigen den Block {@code einstellungen.wissen} nicht,
 * und eine eingespielte Datei ändert ihn nicht (die Datei bekommt den aktuellen Stand, sie kann ihn also weder lesen
 * noch ändern). Das Betreiber-Kommando {@code archiv konfiguration} ist davon nicht betroffen.
 */
final class WissenOhneFreischaltung {

    private WissenOhneFreischaltung() {
    }

    /** Konfigurationsdatei ohne {@code einstellungen.wissen}. */
    static String ohneWissen(ObjectMapper json, String datei) {
        try {
            JsonNode wurzel = json.readTree(datei);
            if (wurzel.path("einstellungen") instanceof ObjectNode einstellungen) {
                einstellungen.remove("wissen");
            }
            return json.writeValueAsString(wurzel);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Export der Konfiguration nicht lesbar", e);
        }
    }

    /** Versionsabbild ohne {@code einstellungen.wissen}. */
    static JsonNode ohneWissen(ObjectMapper json, Object inhalt) {
        JsonNode baum = json.valueToTree(inhalt);
        if (baum.path("einstellungen") instanceof ObjectNode einstellungen) {
            einstellungen.remove("wissen");
        }
        return baum;
    }

    /**
     * Setzt in einer eingespielten Datei {@code einstellungen.wissen} auf den aktuellen Stand. Eine Datei, die sich nicht
     * lesen lässt oder keinen Block {@code einstellungen} hat, bleibt unverändert; ihre Fehler meldet die übliche Prüfung.
     */
    static String wissenBehalten(ObjectMapper json, String datei, String aktuellerExport, int hoechstensBytes) {
        if (datei == null || datei.getBytes(StandardCharsets.UTF_8).length > hoechstensBytes) {
            return datei;
        }
        JsonNode wurzel;
        try {
            wurzel = StrengesJson.lesenGenau(datei);
        } catch (JsonProcessingException | RuntimeException e) {
            return datei;
        }
        if (!(wurzel.path("einstellungen") instanceof ObjectNode einstellungen)) {
            return datei;
        }
        try {
            JsonNode aktuell = json.readTree(aktuellerExport).path("einstellungen").path("wissen");
            if (aktuell.isMissingNode()) {
                einstellungen.remove("wissen");
            } else {
                einstellungen.set("wissen", aktuell);
            }
            return json.writeValueAsString(wurzel);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Export der Konfiguration nicht lesbar", e);
        }
    }
}
