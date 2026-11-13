package ch.urinet.archiv.dokumentart.adapter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
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
    private static final ObjectReader LESER_GENAU = LESER.reader()
            .with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);

    private StrengesJson() {
    }

    /** Liest genau einen JSON-Wert; {@code null}, wenn der Text leer ist. */
    public static JsonNode lesen(String text) throws JsonProcessingException {
        return wert(LESER.readTree(text));
    }

    /**
     * Wie {@link #lesen}, aber Kommazahlen als {@code BigDecimal}: für Zusatzwerte, damit der Prüfer genau die Zahl
     * prüft, die gespeichert wird (Req 9.5, Review PR #95).
     */
    public static JsonNode lesenGenau(String text) throws JsonProcessingException {
        return wert(LESER_GENAU.readTree(text));
    }

    private static JsonNode wert(JsonNode knoten) {
        return knoten == null || knoten.isMissingNode() ? null : knoten;
    }
}
