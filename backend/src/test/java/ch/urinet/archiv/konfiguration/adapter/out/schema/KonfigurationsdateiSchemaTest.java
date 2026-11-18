package ch.urinet.archiv.konfiguration.adapter.out.schema;

import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class KonfigurationsdateiSchemaTest {

    private static final Schema SCHEMA = schema();

    @Test
    void akzeptiertDieKonfigurationsdateiAusDemDesign() {
        String datei = """
                {
                  "format": 1,
                  "basisVersion": 7,
                  "einstellungen": {
                    "geschaeftsjahrEnde": "12-31",
                    "kennungen": { "uid": null, "ibans": [] }
                  },
                  "kiTexte": { "rolle": "Archivassistent", "feldregeln": "", "kiFreigegeben": false },
                  "klassifikationen": [{
                    "code": "fachgebiet", "name": "Fachgebiet", "mehrfach": true,
                    "kiVorschlag": true, "suchfilter": true, "oeffentlichFilter": true,
                    "werte": [{ "code": "bad", "name": "Bad" }]
                  }],
                  "arten": [{
                    "code": "PROTOKOLL", "name": "Protokoll", "gruppen": ["frei"],
                    "pflichtfelder": ["titel", "zusatz.sitzung"], "vertraulichkeit": "INTERN",
                    "aufbewahrung": { "jahre": 10, "ab": "FELD", "feld": "sitzung" },
                    "gesetzlichePflicht": false, "selbstAusgestellt": true,
                    "zusatzSchema": {
                      "type": "object",
                      "properties": { "sitzung": { "type": "string", "format": "date" } },
                      "additionalProperties": false
                    },
                    "klassifikationen": [{ "code": "fachgebiet", "pflicht": false }]
                  }],
                  "regeln": [{
                    "code": "protokoll_eindeutig", "art": "PROTOKOLL", "typ": "EINDEUTIG",
                    "parameter": { "felder": ["zusatz.sitzung"] }, "schwere": "FEHLER",
                    "meldung": "Dieses Protokoll ist bereits erfasst."
                  }]
                }
                """;

        assertThat(SCHEMA.validate(datei, InputFormat.JSON)).isEmpty();
    }

    @Test
    void lehntUnbekannteFelderUndUnpassendeRegelparameterAb() {
        String unbekannt = """
                {
                  "format":1,
                  "einstellungen":{"geschaeftsjahrEnde":"12-31","kennungen":{"uid":null,"ibans":[]}},
                  "kiTexte":{"rolle":"","feldregeln":"","kiFreigegeben":false},
                  "klassifikationen":[],"arten":[],"regeln":[],
                  "mandant":"vhf-gsk"
                }
                """;
        String falscherParameter = """
                {
                  "format":1,
                  "einstellungen":{"geschaeftsjahrEnde":"12-31","kennungen":{"uid":null,"ibans":[]}},
                  "kiTexte":{"rolle":"","feldregeln":"","kiFreigegeben":false},
                  "klassifikationen":[],
                  "arten":[],
                  "regeln":[{
                    "code":"folge","typ":"FOLGE","parameter":{"felder":["belegnummer"]},
                    "schwere":"WARNUNG","meldung":"Lücke"
                  }]
                }
                """;

        assertThat(SCHEMA.validate(unbekannt, InputFormat.JSON)).isNotEmpty();
        assertThat(SCHEMA.validate(falscherParameter, InputFormat.JSON)).isNotEmpty();
    }

    private static Schema schema() {
        try (InputStream in = KonfigurationsdateiSchemaTest.class.getResourceAsStream(
                "/konfiguration/konfiguration.schema.json")) {
            if (in == null) {
                throw new IllegalStateException("Schema der Konfigurationsdatei fehlt");
            }
            return SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
                    .getSchema(new String(in.readAllBytes(), StandardCharsets.UTF_8), InputFormat.JSON);
        } catch (IOException e) {
            throw new IllegalStateException("Schema der Konfigurationsdatei nicht lesbar", e);
        }
    }
}
