package ch.urinet.archiv.konfiguration.adapter.out.persistence;

import ch.urinet.archiv.konfiguration.domain.Konfiguration;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

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

    @Test
    void liestRegelnAusEinemVersionsabbild() throws Exception {
        String abbild = """
                {"version":2,
                 "einstellungen":{"geschaeftsjahrEnde":"12-31","kennungen":{"uid":null,"ibans":[]}},
                 "arten":[],"klassifikationen":[],
                 "regeln":[{"code":"monat_erwartet","art":"BANK","typ":"ERWARTET",
                   "parameter":{"je":"MONAT","datum":"BELEGDATUM"},
                   "schwere":"WARNUNG","meldung":"Bankbeleg fehlt.","aktiv":true}]}
                """;

        Konfiguration gelesen = repository.lesen(abbild);

        assertThat(gelesen.regeln()).singleElement().satisfies(regel -> {
            assertThat(regel.code()).isEqualTo("monat_erwartet");
            assertThat(regel.typisierteParameter())
                    .isEqualTo(new ch.urinet.archiv.konfiguration.domain.Regel.Erwartet(
                            "MONAT", "BELEGDATUM", null, null));
        });
    }

    @Test
    void typenDerZusatzfelderGehoerenNichtInsAbbild() throws Exception {
        ObjectMapper mitAusschluss = new ObjectMapper();
        new KonfigurationJson().customize(mitAusschluss);
        var app = new PanacheKonfigurationVersionsRepository(mitAusschluss);
        var art = new Konfiguration.Art("BANK", "Bank", null, List.of("frei"), List.of("titel"),
                app.kanonisch("{\"type\":\"object\",\"properties\":{\"periode\":{\"type\":\"string\","
                        + "\"format\":\"date\"}}}"), "NORMAL", new Konfiguration.Aufbewahrung(null, null), false,
                true, false, List.of(), null, null, true, List.of(), null, 0, List.of(), Map.of("periode", "date"));
        var konfiguration = new Konfiguration(2, new Konfiguration.Einstellungen("12-31",
                new Konfiguration.Kennungen(null, List.of())), List.of(art), List.of(), List.of());

        String abbild = mitAusschluss.writeValueAsString(konfiguration);
        // Abbilder, die die App vor dieser Änderung schrieb, tragen die Typen noch; sie werden beim Lesen übergangen.
        String frueher = abbild.replace("\"arten\":[{",
                "\"arten\":[{\"zusatzFeldTypen\":{\"periode\":\"date\"},");

        assertThat(abbild).doesNotContain("zusatzFeldTypen");
        Konfiguration ausApp = app.lesen(abbild);
        assertThat(ausApp.arten().getFirst().zusatzFeldTypen()).isEmpty();
        assertThat(ausApp.gleicherInhalt(app.lesen(frueher))).isTrue();
        assertThat(ausApp.arten().getFirst().mitZusatzFeldTypen(Map.of("periode", "date"))).isEqualTo(art);
    }
}
