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

import java.math.BigDecimal;
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
 * <p>
 * Zusatzwerte liest der Prüfer genau ({@code BigDecimal}, Review PR #95). Ungültig ist darum auch eine Zahl, die nicht
 * verlustfrei und lesbar in {@code jsonb} liegen kann: Betrag, der als {@code double} unendlich wird ({@code 1e400}),
 * Zahl ungleich 0, die als {@code double} 0 wird ({@code 1e-400}) oder mehr als {@value #MAX_STELLEN} Stellen in
 * Dezimalschreibweise ohne Exponent, wie Postgres sie ausgibt ({@code 1e-1200} wären 1'201 Stellen; Jackson liest
 * beim Laden höchstens 1'000, Postgres-{@code numeric} nimmt höchstens 16'383 Nachkommastellen).
 */
final class Jackson3Baum {

    /** Höchstzahl der Stellen einer Zahl im Zusatz in Dezimalschreibweise ohne Exponent (Req 9.5). */
    static final int MAX_STELLEN = 400;
    private static final JsonNodeFactory KNOTEN = JsonNodeFactory.instance;

    private Jackson3Baum() {
    }

    static tools.jackson.databind.JsonNode von(JsonNode knoten) {
        return von(knoten, false);
    }

    /** Wie {@link #von}, aber eine nicht darstellbare Zahl ergibt eine {@link IllegalArgumentException}. */
    static tools.jackson.databind.JsonNode wert(JsonNode knoten) {
        return von(knoten, true);
    }

    private static tools.jackson.databind.JsonNode von(JsonNode knoten, boolean nurDarstellbar) {
        return switch (knoten.getNodeType()) {
            case OBJECT -> {
                ObjectNode objekt = KNOTEN.objectNode();
                for (Iterator<Map.Entry<String, JsonNode>> it = knoten.fields(); it.hasNext(); ) {
                    Map.Entry<String, JsonNode> eintrag = it.next();
                    objekt.set(eintrag.getKey(), von(eintrag.getValue(), nurDarstellbar));
                }
                yield objekt;
            }
            case ARRAY -> {
                ArrayNode liste = KNOTEN.arrayNode(knoten.size());
                knoten.forEach(kind -> liste.add(von(kind, nurDarstellbar)));
                yield liste;
            }
            case STRING -> KNOTEN.stringNode(knoten.textValue());
            case BOOLEAN -> KNOTEN.booleanNode(knoten.booleanValue());
            case NULL -> KNOTEN.nullNode();
            case NUMBER -> zahl(knoten, nurDarstellbar);
            default -> throw new IllegalArgumentException("Knotenart " + knoten.getNodeType() + " nicht unterstützt");
        };
    }

    private static tools.jackson.databind.JsonNode zahl(JsonNode knoten, boolean nurDarstellbar) {
        if (nurDarstellbar && !darstellbar(knoten)) {
            throw new IllegalArgumentException("Zahl nicht darstellbar");
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

    static boolean darstellbar(JsonNode knoten) {
        if (knoten.isDouble() || knoten.isFloat()) {
            return Double.isFinite(knoten.doubleValue());
        }
        if (!knoten.isBigDecimal() && !knoten.isBigInteger()) {
            return true;
        }
        BigDecimal zahl = knoten.decimalValue();
        // Stellen vor der Prüfung des Bereichs, damit ein Exponent wie 1e-1000000000 nichts Grosses berechnet
        long skala = zahl.scale();
        long ganz = zahl.signum() == 0 ? 1 : Math.max(zahl.precision() - skala, 1);
        if (ganz + Math.max(skala, 0) > MAX_STELLEN) {
            return false;
        }
        double gerundet = zahl.doubleValue();
        return Double.isFinite(gerundet) && (gerundet != 0 || zahl.signum() == 0);
    }
}
