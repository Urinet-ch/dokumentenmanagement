package ch.urinet.archiv.konfiguration.adapter.out.schema;

import ch.urinet.archiv.dokumentart.application.ZusatzSchemaPruefer;
import ch.urinet.archiv.gemeinsam.domain.FachFehler;
import ch.urinet.archiv.gemeinsam.domain.Fehlercode;
import ch.urinet.archiv.gemeinsam.domain.KonfigurationUngueltig;
import ch.urinet.archiv.konfiguration.application.KonfigurationVersionsRepository;
import ch.urinet.archiv.konfiguration.application.KonfigurationsdateiCodec;
import ch.urinet.archiv.konfiguration.domain.Konfiguration;
import ch.urinet.archiv.konfiguration.domain.Konfiguration.Art;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import jakarta.enterprise.context.ApplicationScoped;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

@ApplicationScoped
public class JsonKonfigurationsdateiCodec implements KonfigurationsdateiCodec {

    static final int MAX_BYTES = 1024 * 1024;
    private final ObjectMapper json;
    private final ZusatzSchemaPruefer schemaPruefer;
    private final KonfigurationVersionsRepository versionen;
    private final String schema;

    public JsonKonfigurationsdateiCodec(ObjectMapper json, ZusatzSchemaPruefer schemaPruefer,
                                        KonfigurationVersionsRepository versionen) {
        this.json = json;
        this.schemaPruefer = schemaPruefer;
        this.versionen = versionen;
        try (InputStream stream = JsonKonfigurationsdateiCodec.class
                .getResourceAsStream("/konfiguration/konfiguration.schema.json")) {
            if (stream == null) {
                throw new IllegalStateException("Konfigurationsschema fehlt");
            }
            this.schema = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Konfigurationsschema nicht lesbar", e);
        }
    }

    public Datei lesen(String text) {
        if (text == null) {
            throw ungueltig();
        }
        if (text.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            throw new FachFehler(Fehlercode.DATEI_ZU_GROSS);
        }
        try {
            List<ZusatzSchemaPruefer.Verletzung> verletzungen = schemaPruefer.konfigurationPruefen(schema, text);
            if (!verletzungen.isEmpty()) {
                throw new KonfigurationUngueltig(verletzungen.stream().map(verletzung ->
                        new KonfigurationUngueltig.Befund(verletzung.pfad(), verletzung.schluesselwort(),
                                "Die Datei verletzt das Schema der Konfigurationsdatei.")).toList());
            }
            JsonNode root = ch.urinet.archiv.gemeinsam.adapter.StrengesJson.lesenGenau(text);
            Integer basisVersion = root.path("basisVersion").isInt() ? root.path("basisVersion").intValue() : null;
            ObjectNode bereinigt = (ObjectNode) root.deepCopy();
            kommentareEntfernen(bereinigt);
            bereinigt.remove("format");
            bereinigt.remove("basisVersion");
            bereinigt.put("version", 1);
            ArrayNode arten = (ArrayNode) bereinigt.path("arten");
            for (JsonNode art : arten) {
                if (art instanceof ObjectNode artObjekt) {
                    JsonNode zusatzSchema = artObjekt.get("zusatzSchema");
                    if (zusatzSchema != null && zusatzSchema.isObject()) {
                        artObjekt.set("zusatzSchema", TextNode.valueOf(versionen.kanonisch(zusatzSchema.toString())));
                    }
                    defaults(artObjekt, Map.of("aktiv", true, "kiVorschlag", true, "reihenfolge", 0));
                    if (!artObjekt.has("kiFelder")) {
                        artObjekt.putArray("kiFelder");
                    }
                    zuordnungsfeldUmbenennen(artObjekt, "code", "klassifikation");
                }
            }
            for (JsonNode klassifikation : bereinigt.path("klassifikationen")) {
                if (klassifikation instanceof ObjectNode objekt) {
                    defaults(objekt, Map.of("aktiv", true));
                    for (JsonNode wert : objekt.path("werte")) {
                        if (wert instanceof ObjectNode wertObjekt) {
                            defaults(wertObjekt, Map.of("aktiv", true, "reihenfolge", 0));
                        }
                    }
                }
            }
            for (JsonNode regel : bereinigt.path("regeln")) {
                if (regel instanceof ObjectNode objekt) {
                    defaults(objekt, Map.of("aktiv", true));
                }
            }
            Konfiguration inhalt = json.treeToValue(bereinigt, Konfiguration.class);
            Konfiguration typisiert = new Konfiguration(inhalt.version(), inhalt.einstellungen(),
                    inhalt.arten().stream().map(art -> art.mitZusatzFeldTypen(
                            schemaPruefer.feldTypen(art.zusatzSchema()))).toList(),
                    inhalt.klassifikationen(), inhalt.regeln(), inhalt.kiTexte());
            return new KonfigurationsdateiCodec.Datei(basisVersion, typisiert);
        } catch (FachFehler e) {
            if (e.code() == Fehlercode.DATEI_ZU_GROSS || e instanceof KonfigurationUngueltig) {
                throw e;
            }
            throw ungueltig();
        } catch (JsonProcessingException e) {
            throw ungueltig();
        }
    }

    public String schreiben(Konfiguration inhalt) {
        try {
            ObjectNode root = json.createObjectNode();
            root.put("format", 1);
            root.put("basisVersion", inhalt.version());
            root.set("einstellungen", json.valueToTree(inhalt.einstellungen()));
            root.set("kiTexte", json.valueToTree(inhalt.kiTexte()));
            root.set("klassifikationen", json.valueToTree(inhalt.klassifikationen()));
            ArrayNode arten = root.putArray("arten");
            for (Art art : inhalt.arten()) {
                ObjectNode artJson = json.valueToTree(art);
                artJson.remove("zusatzFeldTypen");
                zuordnungsfeldUmbenennen(artJson, "klassifikation", "code");
                artJson.set("zusatzSchema", ch.urinet.archiv.gemeinsam.adapter.StrengesJson
                        .lesenGenau(art.zusatzSchema()));
                arten.add(artJson);
            }
            root.set("regeln", json.valueToTree(inhalt.regeln()));
            return json.writeValueAsString(root);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Konfiguration nicht exportierbar", e);
        }
    }

    private static void kommentareEntfernen(JsonNode knoten) {
        if (knoten.isArray()) {
            knoten.forEach(JsonKonfigurationsdateiCodec::kommentareEntfernen);
        } else if (knoten instanceof ObjectNode objekt) {
            Iterator<Map.Entry<String, JsonNode>> felder = objekt.fields();
            while (felder.hasNext()) {
                Map.Entry<String, JsonNode> feld = felder.next();
                if (feld.getKey().startsWith("_")) {
                    felder.remove();
                } else if (!"zusatzSchema".equals(feld.getKey())) {
                    kommentareEntfernen(feld.getValue());
                }
            }
        }
    }

    /** Die Datei nennt die Klassifikation einer Zuordnung {@code code} (design.md), die Domain {@code klassifikation}. */
    private static void zuordnungsfeldUmbenennen(ObjectNode art, String von, String nach) {
        for (JsonNode zuordnung : art.path("klassifikationen")) {
            if (zuordnung instanceof ObjectNode objekt && objekt.has(von)) {
                JsonNode wert = objekt.remove(von);
                objekt.set(nach, wert);
            }
        }
    }

    private void defaults(ObjectNode objekt, Map<String, ?> defaults) {
        defaults.forEach((feld, wert) -> {
            if (!objekt.has(feld)) {
                objekt.set(feld, json.valueToTree(wert));
            }
        });
    }

    private static FachFehler ungueltig() {
        return new FachFehler(Fehlercode.KONFIGURATION_UNGUELTIG);
    }
}
