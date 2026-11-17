package ch.urinet.archiv.konfiguration.adapter.out.persistence;

import ch.urinet.archiv.konfiguration.domain.Konfiguration;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Abbild der Migration (jsonb-Objekt, {@code null}-Listen, fehlende IBANs) bleibt lesbar und gleicht dem der App. */
class PanacheKonfigurationVersionsRepositoryTest {

    private final PanacheKonfigurationVersionsRepository repository =
            new PanacheKonfigurationVersionsRepository(new ObjectMapper());

    @Test
    void migrationsabbildMitObjektSchemaUndNullListenIstLesbar() throws Exception {
        String migration = """
                {"version":1,
                 "einstellungen":{"geschaeftsjahrEnde":"12-31","kennungen":{"uid":null}},
                 "arten":[
                  {"code":"B","name":"B","beschreibung":null,"gruppen":[],"pflichtfelder":null,
                   "zusatzSchema":{"type":"object","properties":{"z":{"type":"string"},"a":{"type":"string"}}},
                   "vertraulichkeit":"NORMAL","aufbewahrung":{"jahre":10,"ab":"GESCHAEFTSJAHR"},
                   "gesetzlichePflicht":false,"aktiv":true},
                  {"code":"A","name":"A","beschreibung":null,"gruppen":null,"pflichtfelder":[],
                   "zusatzSchema":null,
                   "vertraulichkeit":"NORMAL","aufbewahrung":{"jahre":null,"ab":null},
                   "gesetzlichePflicht":false,"aktiv":true}]}
                """;

        Konfiguration gelesen = repository.lesen(migration);

        assertThat(gelesen.arten()).extracting(Konfiguration.Art::code).containsExactly("A", "B");
        assertThat(gelesen.arten().getFirst().zusatzSchema()).isEqualTo(Konfiguration.OHNE_ZUSATZ);
        assertThat(gelesen.arten().get(1).zusatzSchema())
                .isEqualTo("{\"properties\":{\"a\":{\"type\":\"string\"},\"z\":{\"type\":\"string\"}},\"type\":\"object\"}");
        assertThat(gelesen.arten().get(1).pflichtfelder()).isEmpty();
        assertThat(gelesen.einstellungen().kennungen().ibans()).isEmpty();
    }

    @Test
    void schemaTextAusDerAppUndAusJsonbSindGleichKanonisch() {
        String client = "{\"type\":\"object\",\"properties\":{\"z\":{\"type\":\"string\"}}}";
        String jsonb = "{\"type\": \"object\", \"properties\": {\"z\": {\"type\": \"string\"}}}";
        String umsortiert = "{\"properties\": {\"z\": {\"type\": \"string\"}}, \"type\": \"object\"}";

        assertThat(repository.kanonisch(jsonb)).isEqualTo(repository.kanonisch(client))
                .isEqualTo(repository.kanonisch(umsortiert));
    }
}
