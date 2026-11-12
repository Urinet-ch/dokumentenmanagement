package ch.urinet.archiv.dokumentart.adapter.out.schema;

import com.fasterxml.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.BigIntegerNode;
import tools.jackson.databind.node.DecimalNode;
import tools.jackson.databind.node.DoubleNode;
import tools.jackson.databind.node.FloatNode;
import tools.jackson.databind.node.IntNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.LongNode;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.node.ShortNode;

import java.util.Iterator;
import java.util.Map;

/**
 * Überträgt einen Baum aus Jackson 2 (strenger Leser {@code StrengesJson}) in Jackson 3, das json-schema-validator ab
 * 3.0 verlangt. Knotenart und Zahlentyp bleiben gleich (ein {@code double} bleibt {@code DoubleNode}, kein
 * {@code DecimalNode}), damit die Bibliothek dieselben Werte sieht wie mit 2.0.8 und die Prüfung beim Lesen
 * (doppelte Schlüssel, Zeichen nach dem Wert) nur an einer Stelle liegt. Die Tiefe ist vorher begrenzt.
 * <p>
 * Zusatzwerte überträgt {@link #wert}: Nicht endliche Zahlen (z. B. {@code 1e400} als {@code DoubleNode(Infinity)})
 * sind dort ungültig, unabhängig vom Schema (Review PR #83). Das ist strenger als 2.0.8, das sie mit {@code type: number}
 * und ohne Schranke annahm und nur bei {@code maximum}, {@code multipleOf} und {@code const} ablehnte; 3.x allein liesse
 * sie auch gegen {@code maximum} durch. Die Regel greift nur, wenn der Wert als JSON-Zahl beim Prüfer ankommt.
 */
final class Jackson3Baum {

    private static final JsonNodeFactory KNOTEN = JsonNodeFactory.instance;

    private Jackson3Baum() {
    }

    static tools.jackson.databind.JsonNode von(JsonNode knoten) {
        return von(knoten, false);
    }

    /** Wie {@link #von}, aber eine nicht endliche Zahl ergibt eine {@link IllegalArgumentException}. */
    static tools.jackson.databind.JsonNode wert(JsonNode knoten) {
        return von(knoten, true);
    }

    private static tools.jackson.databind.JsonNode von(JsonNode knoten, boolean nurEndlich) {
        return switch (knoten.getNodeType()) {
            case OBJECT -> {
                ObjectNode objekt = KNOTEN.objectNode();
                for (Iterator<Map.Entry<String, JsonNode>> it = knoten.fields(); it.hasNext(); ) {
                    Map.Entry<String, JsonNode> eintrag = it.next();
                    objekt.set(eintrag.getKey(), von(eintrag.getValue(), nurEndlich));
                }
                yield objekt;
            }
            case ARRAY -> {
                ArrayNode liste = KNOTEN.arrayNode(knoten.size());
                knoten.forEach(kind -> liste.add(von(kind, nurEndlich)));
                yield liste;
            }
            case STRING -> KNOTEN.stringNode(knoten.textValue());
            case BOOLEAN -> KNOTEN.booleanNode(knoten.booleanValue());
            case NULL -> KNOTEN.nullNode();
            case NUMBER -> zahl(knoten, nurEndlich);
            default -> throw new IllegalArgumentException("Knotenart " + knoten.getNodeType() + " nicht unterstützt");
        };
    }

    private static tools.jackson.databind.JsonNode zahl(JsonNode knoten, boolean nurEndlich) {
        if (nurEndlich && (knoten.isDouble() || knoten.isFloat()) && !Double.isFinite(knoten.doubleValue())) {
            throw new IllegalArgumentException("Zahl nicht endlich");
        }
        return switch (knoten.numberType()) {
            case INT -> knoten.isShort() ? ShortNode.valueOf(knoten.shortValue()) : IntNode.valueOf(knoten.intValue());
            case LONG -> LongNode.valueOf(knoten.longValue());
            case BIG_INTEGER -> BigIntegerNode.valueOf(knoten.bigIntegerValue());
            case FLOAT -> FloatNode.valueOf(knoten.floatValue());
            case DOUBLE -> DoubleNode.valueOf(knoten.doubleValue());
            case BIG_DECIMAL -> DecimalNode.valueOf(knoten.decimalValue());
        };
    }
}
