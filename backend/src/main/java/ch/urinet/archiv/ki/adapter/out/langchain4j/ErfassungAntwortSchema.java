package ch.urinet.archiv.ki.adapter.out.langchain4j;

import ch.urinet.archiv.ki.domain.ArtEintrag;
import ch.urinet.archiv.ki.domain.KiKlassifikation;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Baut das strikte Antwortschema aus den KI-fähigen Feldern des aktuellen Mandanten. */
final class ErfassungAntwortSchema {

    private static final ObjectMapper JSON = new ObjectMapper();

    private ErfassungAntwortSchema() {
    }

    static String json(List<ArtEintrag> arten, List<KiKlassifikation> klassifikationen) {
        ObjectNode schema = (ObjectNode) lesen(AntwortSchema.json());
        ObjectNode eigenschaften = (ObjectNode) schema.path("properties");
        ArrayNode codes = JSON.createArrayNode();
        arten.forEach(art -> codes.add(art.code()));
        eigenschaften.with("art").set("enum", codes);
        if (arten.stream().noneMatch(art -> art.gruppen().contains("finanz"))) {
            finanzfelderEntfernen(schema);
        }

        Map<String, List<JsonNode>> zusatzSchemas = new LinkedHashMap<>();
        for (ArtEintrag art : arten) {
            JsonNode zusatz = lesen(art.zusatzSchema());
            JsonNode felder = zusatz.path("properties");
            for (String feld : art.kiFelder()) {
                if (!feld.equals("zugriff") && !feld.equals("veroeffentlicht")) {
                    JsonNode typ = felder.get(feld);
                    if (typ == null) {
                        throw new IllegalStateException("KI-Feld fehlt im Zusatz-Schema");
                    }
                    zusatzSchemas.computeIfAbsent("zusatz." + feld, ignoriert -> new ArrayList<>())
                            .add(nullable(typ));
                }
            }
        }
        zusatzSchemas.forEach((feld, varianten) -> eigenschaften.set(feld, vereinigen(varianten)));
        anhaengenPflichtfelder(schema, zusatzSchemas.keySet());
        sicherheitAnhaengen(schema, zusatzSchemas.keySet());

        Map<String, JsonNode> klassifikationsSchemas = new LinkedHashMap<>();
        for (KiKlassifikation klassifikation : klassifikationen) {
            List<String> werte = klassifikation.werte().stream().filter(KiKlassifikation.Wert::aktiv)
                    .map(KiKlassifikation.Wert::code).toList();
            if (werte.isEmpty()) {
                continue;
            }
            String feld = "klassifikationen." + klassifikation.code();
            klassifikationsSchemas.put(feld, klassifikationSchema(klassifikation, werte));
        }
        klassifikationsSchemas.forEach(eigenschaften::set);
        anhaengenPflichtfelder(schema, klassifikationsSchemas.keySet());
        sicherheitAnhaengen(schema, klassifikationsSchemas.keySet());

        try {
            return JSON.writeValueAsString(schema);
        } catch (Exception e) {
            throw new IllegalStateException("Antwortschema nicht serialisierbar", e);
        }
    }

    private static JsonNode nullable(JsonNode schema) {
        ObjectNode kopie = schema.deepCopy();
        JsonNode typ = kopie.get("type");
        if (typ != null && typ.isTextual()) {
            ArrayNode typen = JSON.createArrayNode().add(typ.textValue()).add("null");
            kopie.set("type", typen);
            if (kopie.path("enum").isArray()) {
                ((ArrayNode) kopie.path("enum")).addNull();
            }
            return kopie;
        }
        if (typ != null && typ.isArray()) {
            ArrayNode typen = (ArrayNode) typ;
            if (!typen.toString().contains("\"null\"")) {
                typen.add("null");
            }
            if (kopie.path("enum").isArray()) {
                ((ArrayNode) kopie.path("enum")).addNull();
            }
            return kopie;
        }
        ObjectNode alternative = JSON.createObjectNode();
        ArrayNode alternativen = alternative.putArray("anyOf");
        alternativen.add(kopie);
        alternativen.add(JSON.createObjectNode().put("type", "null"));
        return alternative;
    }

    private static JsonNode vereinigen(List<JsonNode> varianten) {
        Set<String> eindeutige = new LinkedHashSet<>();
        ArrayNode alternativen = JSON.createArrayNode();
        varianten.forEach(variante -> {
            String json = variante.toString();
            if (eindeutige.add(json)) {
                alternativen.add(variante);
            }
        });
        return alternativen.size() == 1 ? alternativen.get(0) : JSON.createObjectNode().set("anyOf", alternativen);
    }

    private static JsonNode klassifikationSchema(KiKlassifikation klassifikation, List<String> werte) {
        ArrayNode enumWerte = JSON.createArrayNode();
        werte.forEach(enumWerte::add);
        if (!klassifikation.mehrfach()) {
            ObjectNode einzel = JSON.createObjectNode();
            einzel.putArray("type").add("string").add("null");
            einzel.set("enum", enumWerte.addNull());
            return einzel;
        }
        ObjectNode item = JSON.createObjectNode().put("type", "string");
        item.set("enum", enumWerte);
        ObjectNode array = JSON.createObjectNode();
        array.putArray("type").add("array").add("null");
        array.set("items", item);
        return array;
    }

    private static void anhaengenPflichtfelder(ObjectNode schema, Set<String> felder) {
        ArrayNode required = (ArrayNode) schema.path("required");
        felder.forEach(required::add);
    }

    private static void sicherheitAnhaengen(ObjectNode schema, Set<String> felder) {
        ObjectNode sicherheit = (ObjectNode) schema.path("properties").path("sicherheit");
        ObjectNode eigenschaften = (ObjectNode) sicherheit.path("properties");
        ArrayNode required = (ArrayNode) sicherheit.path("required");
        for (String feld : felder) {
            eigenschaften.set(feld, JSON.createObjectNode().putArray("type").add("number").add("null"));
            required.add(feld);
        }
    }

    private static void finanzfelderEntfernen(ObjectNode schema) {
        Set<String> felder = Set.of("partnerIban", "belegnummer", "richtung", "betrag", "waehrung",
                "faelligkeit", "qrReferenz");
        ObjectNode properties = (ObjectNode) schema.path("properties");
        felder.forEach(properties::remove);
        requiredEntfernen(schema.path("required"), felder);
        ObjectNode sicherheit = (ObjectNode) properties.path("sicherheit");
        ObjectNode sicherheitEigenschaften = (ObjectNode) sicherheit.path("properties");
        Set<String> sicherheitsfelder = Set.of("belegnummer", "richtung", "betrag", "waehrung", "faelligkeit");
        sicherheitsfelder.forEach(sicherheitEigenschaften::remove);
        requiredEntfernen(sicherheit.path("required"), sicherheitsfelder);
    }

    private static void requiredEntfernen(JsonNode requiredNode, Set<String> zuEntfernen) {
        ArrayNode required = (ArrayNode) requiredNode;
        List<String> behalten = new ArrayList<>();
        required.forEach(feld -> {
            if (feld.isTextual() && !zuEntfernen.contains(feld.asText())) {
                behalten.add(feld.asText());
            }
        });
        required.removeAll();
        behalten.forEach(required::add);
    }

    private static JsonNode lesen(String schema) {
        try {
            return JSON.readTree(schema);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Konfigurationsschema nicht lesbar", e);
        }
    }
}
