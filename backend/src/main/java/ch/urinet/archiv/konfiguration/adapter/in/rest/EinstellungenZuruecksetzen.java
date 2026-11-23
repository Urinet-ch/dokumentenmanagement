package ch.urinet.archiv.konfiguration.adapter.in.rest;

import ch.urinet.archiv.gemeinsam.domain.KonfigurationUngueltig;
import ch.urinet.archiv.konfiguration.domain.Einstellungskatalog.Gruppe;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;

final class EinstellungenZuruecksetzen {
    record Auftrag(Gruppe gruppe, Integer version) {
    }

    private EinstellungenZuruecksetzen() {
    }

    static Auftrag lesen(JsonNode eingabe) {
        if (!(eingabe instanceof ObjectNode objekt) || objekt.size() != 1) {
            throw ungueltig();
        }
        JsonNode gruppe = objekt.get("gruppe");
        if (gruppe != null && gruppe.isTextual()) {
            try {
                return new Auftrag(Gruppe.valueOf(gruppe.textValue()), null);
            } catch (IllegalArgumentException e) {
                throw ungueltig();
            }
        }
        JsonNode version = objekt.get("version");
        if (version != null && version.isIntegralNumber() && version.canConvertToInt() && version.intValue() > 0) {
            return new Auftrag(null, version.intValue());
        }
        throw ungueltig();
    }

    static ObjectNode gruppenPatch(ObjectMapper json, Gruppe gruppe) {
        ObjectNode patch = json.createObjectNode();
        patch.putNull(switch (gruppe) {
            case GESCHAEFTSJAHR -> "geschaeftsjahrEnde";
            case KENNUNGEN -> "kennungen";
            case BRANDING -> "branding";
            case CHAT -> "chat";
            case WEBSEITEN_CHAT -> "webseitenChat";
            case WISSEN -> "wissen";
        });
        return patch;
    }

    private static KonfigurationUngueltig ungueltig() {
        return new KonfigurationUngueltig(List.of(new KonfigurationUngueltig.Befund("zuruecksetzen",
                "FORMAT_UNGUELTIG", "Geben Sie genau eine gültige Gruppe oder Version an.")));
    }
}
