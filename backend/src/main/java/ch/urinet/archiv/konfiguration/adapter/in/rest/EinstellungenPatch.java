package ch.urinet.archiv.konfiguration.adapter.in.rest;

import ch.urinet.archiv.gemeinsam.adapter.StrengesJson;
import ch.urinet.archiv.gemeinsam.domain.FachFehler;
import ch.urinet.archiv.gemeinsam.domain.Fehlercode;
import ch.urinet.archiv.gemeinsam.domain.KonfigurationUngueltig;
import ch.urinet.archiv.konfiguration.domain.Einstellungskatalog;
import ch.urinet.archiv.konfiguration.domain.Konfiguration.Einstellungen;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.time.DayOfWeek;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

final class EinstellungenPatch {
    private static final int MAX_BYTES = 16 * 1024;
    private static final Map<String, Einstellungskatalog.Einstellung> KATALOG = Einstellungskatalog.alle().stream()
            .collect(Collectors.toUnmodifiableMap(Einstellungskatalog.Einstellung::schluessel, einstellung -> einstellung));

    record Ergebnis(Einstellungen ziel, List<KonfigurationUngueltig.Befund> befunde) {
        Ergebnis {
            befunde = List.copyOf(befunde);
        }
    }

    private EinstellungenPatch() {
    }

    static JsonNode lesen(InputStream eingabe) throws IOException {
        byte[] bytes = eingabe.readNBytes(MAX_BYTES + 1);
        if (bytes.length > MAX_BYTES) {
            throw new FachFehler(Fehlercode.DATEI_ZU_GROSS);
        }
        try {
            String text = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
            JsonNode patch = StrengesJson.lesenGenau(text);
            if (!(patch instanceof ObjectNode)) {
                throw ungueltig();
            }
            return patch;
        } catch (CharacterCodingException | JsonProcessingException e) {
            throw ungueltig();
        }
    }

    static Ergebnis anwenden(ObjectMapper json, Einstellungen bisher, JsonNode patch) {
        if (!(patch instanceof ObjectNode objekt)) {
            throw ungueltig();
        }
        ObjectNode ziel = json.valueToTree(bisher);
        List<KonfigurationUngueltig.Befund> befunde = new ArrayList<>();
        zusammenfuehren(ziel, objekt, "", befunde);
        if (!ziel.hasNonNull("geschaeftsjahrEnde")) {
            ziel.put("geschaeftsjahrEnde", Einstellungskatalog.GESCHAEFTSJAHR_STANDARD);
        }
        try {
            return new Ergebnis(json.treeToValue(ziel, Einstellungen.class), befunde);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Geprüfte Einstellungen nicht lesbar", e);
        }
    }

    private static void zusammenfuehren(ObjectNode ziel, ObjectNode patch, String eltern,
                                       List<KonfigurationUngueltig.Befund> befunde) {
        patch.fields().forEachRemaining(feld -> {
            String pfad = eltern.isEmpty() ? feld.getKey() : eltern + "." + feld.getKey();
            JsonNode wert = feld.getValue();
            Einstellungskatalog.Einstellung einstellung = KATALOG.get(pfad);
            boolean gruppe = KATALOG.keySet().stream().anyMatch(schluessel -> schluessel.startsWith(pfad + "."));
            if (einstellung == null && !gruppe) {
                befunde.add(new KonfigurationUngueltig.Befund("einstellungen" + (eltern.isEmpty() ? "" : "." + eltern),
                        "FELD_UNBEKANNT", "Die Anfrage enthält ein unbekanntes Feld."));
            } else if (wert.isNull()) {
                ziel.remove(feld.getKey());
            } else if (gruppe && wert instanceof ObjectNode gruppePatch) {
                JsonNode bisher = ziel.get(feld.getKey());
                ObjectNode gruppeZiel = bisher instanceof ObjectNode bisherObjekt
                        ? bisherObjekt : ziel.objectNode();
                ziel.set(feld.getKey(), gruppeZiel);
                zusammenfuehren(gruppeZiel, gruppePatch, pfad, befunde);
            } else if (einstellung != null && typGueltig(einstellung.typ(), wert)) {
                ziel.set(feld.getKey(), wert.deepCopy());
            } else {
                befunde.add(new KonfigurationUngueltig.Befund("einstellungen." + pfad, "TYP_UNGUELTIG",
                        "Der Wert hat nicht den erwarteten Typ."));
                if (einstellung != null && wert.isNumber()
                        && (einstellung.risiko() == Einstellungskatalog.Risiko.WENN_KUERZER
                        || einstellung.risiko() == Einstellungskatalog.Risiko.WENN_UEBER_DOPPELTEM_STANDARD)) {
                    ziel.put(feld.getKey(), risikoZahl(wert.decimalValue(), einstellung.risiko()));
                }
            }
        });
    }

    private static int risikoZahl(BigDecimal wert, Einstellungskatalog.Risiko risiko) {
        if (wert.compareTo(BigDecimal.valueOf(Integer.MIN_VALUE)) < 0) {
            return Integer.MIN_VALUE;
        }
        if (wert.compareTo(BigDecimal.valueOf(Integer.MAX_VALUE)) > 0) {
            return Integer.MAX_VALUE;
        }
        if (wert.signum() == 0) {
            return 0;
        }
        if (wert.abs().compareTo(BigDecimal.ONE) < 0) {
            return risiko == Einstellungskatalog.Risiko.WENN_KUERZER
                    ? (wert.signum() < 0 ? -1 : 0) : (wert.signum() < 0 ? 0 : 1);
        }
        return wert.setScale(0, risiko == Einstellungskatalog.Risiko.WENN_KUERZER
                ? RoundingMode.FLOOR : RoundingMode.CEILING).intValueExact();
    }

    private static boolean typGueltig(Einstellungskatalog.Typ typ, JsonNode wert) {
        return switch (typ) {
            case DATUM, UID, TEXT, FARBE -> wert.isTextual();
            case GANZZAHL -> wert.isIntegralNumber() && wert.canConvertToInt();
            case IBANS -> wert.isArray() && alleTexte(wert);
            case WOCHENTAG -> wert.isTextual() && wochentagGueltig(wert.textValue());
        };
    }

    private static boolean alleTexte(JsonNode werte) {
        for (JsonNode wert : werte) {
            if (!wert.isTextual()) {
                return false;
            }
        }
        return true;
    }

    private static boolean wochentagGueltig(String wert) {
        try {
            DayOfWeek.valueOf(wert);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static KonfigurationUngueltig ungueltig() {
        return new KonfigurationUngueltig(List.of(new KonfigurationUngueltig.Befund("einstellungen",
                "FORMAT_UNGUELTIG", "Die Anfrage muss ein gültiges JSON-Objekt enthalten.")));
    }
}
