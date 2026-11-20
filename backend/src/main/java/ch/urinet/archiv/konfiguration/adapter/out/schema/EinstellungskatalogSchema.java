package ch.urinet.archiv.konfiguration.adapter.out.schema;

import ch.urinet.archiv.konfiguration.domain.Einstellungskatalog;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.DayOfWeek;
import java.util.LinkedHashMap;
import java.util.Map;

/** Erzeugt die Schema-Definitionen für den Einstellungskatalog. */
final class EinstellungskatalogSchema {
    private static final Map<String, String> GRUPPEN = Map.of(
            "", "einstellungen",
            "kennungen", "kennungen",
            "branding", "einstellungenBranding",
            "chat", "chatEinstellungen",
            "webseitenChat", "webseitenChatEinstellungen",
            "wissen", "wissenEinstellungen",
            "wissen.lintZeitplan", "lintZeitplan");

    private EinstellungskatalogSchema() {
    }

    static Map<String, ObjectNode> erzeugen(ObjectMapper json) {
        Map<String, ObjectNode> definitionen = new LinkedHashMap<>();
        GRUPPEN.forEach((pfad, name) -> {
            ObjectNode objekt = json.createObjectNode();
            if (pfad.isEmpty() || pfad.equals("kennungen")) {
                objekt.put("type", "object");
            } else {
                objekt.putArray("type").add("object").add("null");
            }
            objekt.putObject("properties");
            objekt.putObject("patternProperties").putObject("^_").put("$ref", "#/$defs/kommentar");
            objekt.put("additionalProperties", false);
            definitionen.put(name, objekt);
        });
        definitionen.get("einstellungen").putArray("required").add("geschaeftsjahrEnde").add("kennungen");
        definitionen.get("kennungen").putArray("required").add("uid").add("ibans");

        GRUPPEN.forEach((pfad, name) -> {
            if (!pfad.isEmpty()) {
                int trennstelle = pfad.lastIndexOf('.');
                String eltern = trennstelle < 0 ? "" : pfad.substring(0, trennstelle);
                String feld = pfad.substring(trennstelle + 1);
                eigenschaften(definitionen.get(GRUPPEN.get(eltern))).putObject(feld).put("$ref", "#/$defs/" + name);
            }
        });

        for (var einstellung : Einstellungskatalog.alle()) {
            String pfad = einstellung.schluessel();
            int trennstelle = pfad.lastIndexOf('.');
            String eltern = trennstelle < 0 ? "" : pfad.substring(0, trennstelle);
            String feld = pfad.substring(trennstelle + 1);
            ObjectNode eigenschaft = eigenschaften(definitionen.get(GRUPPEN.get(eltern))).putObject(feld);
            switch (einstellung.typ()) {
                case DATUM -> eigenschaft.put("type", "string")
                        .put("pattern", "^(0[1-9]|1[0-2])-(0[1-9]|[12][0-9]|3[01])$");
                case UID -> {
                    eigenschaft.putArray("type").add("string").add("null");
                    eigenschaft.put("pattern", Einstellungskatalog.UID_MUSTER);
                }
                case IBANS -> {
                    eigenschaft.put("type", "array");
                    eigenschaft.put("maxItems", Einstellungskatalog.IBAN_ANZAHL_MAX);
                    eigenschaft.putObject("items").put("type", "string");
                }
                case TEXT -> {
                    eigenschaft.putArray("type").add("string").add("null");
                    eigenschaft.put("minLength", 1).put("maxLength", 60);
                }
                case FARBE -> {
                    eigenschaft.putArray("type").add("string").add("null");
                    eigenschaft.put("pattern", "^#[0-9a-fA-F]{6}$");
                }
                case GANZZAHL -> {
                    eigenschaft.putArray("type").add("integer").add("null");
                    eigenschaft.put("minimum", einstellung.zahl().minimum())
                            .put("maximum", einstellung.zahl().maximum());
                }
                case WOCHENTAG -> {
                    eigenschaft.putArray("type").add("string").add("null");
                    var werte = eigenschaft.putArray("enum");
                    for (DayOfWeek tag : DayOfWeek.values()) {
                        werte.add(tag.name());
                    }
                    werte.addNull();
                }
            }
        }
        return definitionen;
    }

    private static ObjectNode eigenschaften(ObjectNode schema) {
        return (ObjectNode) schema.get("properties");
    }
}
