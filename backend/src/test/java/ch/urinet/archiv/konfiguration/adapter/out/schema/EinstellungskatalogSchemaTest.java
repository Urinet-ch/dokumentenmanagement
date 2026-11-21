package ch.urinet.archiv.konfiguration.adapter.out.schema;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;

import static org.assertj.core.api.Assertions.assertThat;

class EinstellungskatalogSchemaTest {
    @Test
    void dateiStimmtMitDemErzeugtenKatalogschemaUeberein() throws IOException {
        ObjectMapper json = new ObjectMapper();
        try (InputStream stream = getClass().getResourceAsStream("/konfiguration/konfiguration.schema.json")) {
            assertThat(stream).isNotNull();
            var definitionen = json.readTree(stream).path("$defs");
            EinstellungskatalogSchema.erzeugen(json).forEach((name, schema) ->
                    assertThat(definitionen.path(name)).as(name).isEqualTo(schema));
        }
    }
}
