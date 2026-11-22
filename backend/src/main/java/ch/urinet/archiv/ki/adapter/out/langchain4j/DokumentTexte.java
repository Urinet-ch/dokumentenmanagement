package ch.urinet.archiv.ki.adapter.out.langchain4j;

import ch.urinet.archiv.ki.domain.chat.DokumentDaten;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;

/**
 * Kennzeichnet Dokumenttexte in Werkzeugantworten als Daten (Spec 02, Req 5.3): die Felder {@code inhalt}
 * ({@code dokument_zeigen}) und {@code auszug} ({@code dokumente_suchen}), überall in der JSON-Antwort, gehen in den Block
 * von {@link DokumentDaten}; der interne Rerankerwert {@code aehnlichkeit} wird nicht an das Modell weitergegeben.
 * Eine Antwort, die kein JSON ist, geht nie unverändert an das Modell, sondern als
 * {@code {"fehler":"Werkzeug nicht verfügbar"}}.
 */
final class DokumentTexte {

    static final List<String> FELDER = List.of("inhalt", "auszug");
    static final List<String> WISSEN_FELDER = List.of("titel", "beschreibung");
    static final String NICHT_VERFUEGBAR = "{\"fehler\":\"Werkzeug nicht verfügbar\"}";

    private DokumentTexte() {
    }

    static String kennzeichnen(ObjectMapper json, String antwort) {
        return kennzeichnen(json, antwort, false);
    }

    static String kennzeichnen(ObjectMapper json, String antwort, boolean wissen) {
        try {
            JsonNode wurzel = json.readTree(antwort);
            if (wurzel == null || !wurzel.isContainerNode()) {
                return NICHT_VERFUEGBAR;
            }
            kennzeichnen(wurzel, wissen);
            return json.writeValueAsString(wurzel);
        } catch (JsonProcessingException e) {
            return NICHT_VERFUEGBAR;
        }
    }

    private static void kennzeichnen(JsonNode knoten, boolean wissen) {
        if (knoten instanceof ObjectNode objekt) {
            objekt.remove("aehnlichkeit");
            for (String feld : FELDER) {
                JsonNode wert = objekt.get(feld);
                if (wert != null && wert.isTextual()) {
                    objekt.put(feld, DokumentDaten.kennzeichnen(wert.asText()));
                }
            }
            if (wissen) {
                for (String feld : WISSEN_FELDER) {
                    JsonNode wert = objekt.get(feld);
                    if (wert != null && wert.isTextual()) {
                        objekt.put(feld, DokumentDaten.kennzeichnen(wert.asText()));
                    }
                }
            }
            objekt.forEach(k -> {
                if (k.isContainerNode()) {
                    kennzeichnen(k, wissen);
                }
            });
        } else {
            knoten.forEach(k -> kennzeichnen(k, wissen));
        }
    }
}
