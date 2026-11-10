package ch.urinet.archiv.dokumentart.adapter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;

/**
 * Strenger JSON-Leser der Dokumentarten (Body der Resource und Zusatz-Schemas): Doppelte Schlüssel und Zeichen nach
 * dem JSON-Wert sind ungültig, statt dass der letzte Schlüssel still gewinnt.
 */
public final class StrengesJson {

    private static final ObjectMapper LESER = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
            .build();

    private StrengesJson() {
    }

    /** Liest genau einen JSON-Wert; {@code null}, wenn der Text leer ist. */
    public static JsonNode lesen(String text) throws JsonProcessingException {
        JsonNode knoten = LESER.readTree(text);
        return knoten == null || knoten.isMissingNode() ? null : knoten;
    }
}
