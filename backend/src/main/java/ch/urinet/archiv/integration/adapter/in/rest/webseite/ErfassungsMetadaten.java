package ch.urinet.archiv.integration.adapter.in.rest.webseite;

import ch.urinet.archiv.dokument.application.Aenderung;
import ch.urinet.archiv.dokument.application.Bestaetigung;
import ch.urinet.archiv.gemeinsam.domain.FachFehler;
import ch.urinet.archiv.gemeinsam.domain.Fehlercode;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.HashSet;
import java.util.Set;

final class ErfassungsMetadaten {
    private static final Set<String> BESTAETIGUNG = Set.of("art", "titel", "datum", "partnerId", "projekt", "tags",
            "vertraulichkeit", "personBenutzer", "geschaeftsjahr", "belegnummer", "richtung", "betrag", "waehrung", "faelligkeit",
            "qrReferenz", "konto", "gueltigAb", "gueltigBis", "kuendigungsfrist", "naechsteFrist", "ohnePdfa",
            "fristAbgelaufenAkzeptiert", "zusatz");
    private final ObjectMapper json;
    private final ObjectReader leser;

    ErfassungsMetadaten(ObjectMapper json) {
        this.json = json;
        leser = json.reader().with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .with(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
                .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }

    ObjectNode lesen(String inhalt, boolean bestaetigen) {
        try {
            JsonNode k = inhalt == null ? null : leser.readTree(inhalt);
            if (!(k instanceof ObjectNode objekt) || objekt.isEmpty()) {
                throw ungueltig();
            }
            objekt.properties().forEach(e -> {
                String name = e.getKey();
                if (bestaetigen ? !BESTAETIGUNG.contains(name)
                        : !(Aenderung.FELDER.contains(name) || name.equals("grund")) || name.equals("personBenutzer")) {
                    throw ungueltig();
                }
                JsonNode w = e.getValue();
                if (w.isNull()) {
                    return;
                }
                boolean passt = switch (name) {
                    case "zusatz" -> w.isObject() && !w.has("veroeffentlicht");
                    case "tags" -> w.isArray() && java.util.stream.StreamSupport.stream(w.spliterator(), false)
                            .allMatch(JsonNode::isTextual);
                    case "betrag" -> w.isNumber();
                    case "geschaeftsjahr" -> w.isIntegralNumber() && w.canConvertToInt();
                    case "ohnePdfa", "fristAbgelaufenAkzeptiert" -> w.isBoolean();
                    default -> w.isTextual();
                };
                if (!passt) {
                    throw ungueltig();
                }
            });
            return objekt;
        } catch (JsonProcessingException e) {
            throw ungueltig();
        }
    }

    Aenderung aenderung(ObjectNode k) {
        ObjectNode daten = k.deepCopy();
        Set<String> gesetzt = new HashSet<>();
        daten.fieldNames().forEachRemaining(gesetzt::add);
        gesetzt.remove("grund");
        daten.set("gesetzt", json.valueToTree(gesetzt));
        Set<String> felder = new HashSet<>();
        JsonNode zusatz = daten.get("zusatz");
        if (zusatz != null && !zusatz.isNull()) {
            zusatz.properties().forEach(e -> {
                JsonNode w = e.getValue();
                if (!(w.isNull() || w.isTextual() && w.asText().isEmpty() || w.isContainerNode() && w.isEmpty())) {
                    felder.add(e.getKey());
                }
            });
            daten.put("zusatz", zusatz.toString());
        }
        daten.set("zusatzFelder", json.valueToTree(felder));
        gesetzt.stream().filter(n -> !n.equals("zusatz")).forEach(n -> {
            JsonNode w = daten.get(n);
            if (w != null && w.isTextual() && w.asText().isBlank()) {
                daten.putNull(n);
            }
        });
        return wert(daten, Aenderung.class);
    }

    Bestaetigung bestaetigung(ObjectNode k) {
        ObjectNode daten = k.deepCopy();
        daten.remove("zusatz");
        return wert(daten, Bestaetigung.class);
    }

    private <T> T wert(ObjectNode daten, Class<T> typ) {
        try {
            return leser.treeToValue(daten, typ);
        } catch (JsonProcessingException | IllegalArgumentException e) {
            throw ungueltig();
        }
    }

    private static FachFehler ungueltig() {
        return new FachFehler(Fehlercode.ANFRAGE_UNGUELTIG);
    }
}
