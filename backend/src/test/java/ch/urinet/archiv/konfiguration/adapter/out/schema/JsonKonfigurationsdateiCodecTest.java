package ch.urinet.archiv.konfiguration.adapter.out.schema;

import ch.urinet.archiv.dokumentart.application.ZusatzSchemaPruefer;
import ch.urinet.archiv.gemeinsam.domain.FachFehler;
import ch.urinet.archiv.gemeinsam.domain.Fehlercode;
import ch.urinet.archiv.konfiguration.application.KonfigurationVersionsRepository;
import ch.urinet.archiv.konfiguration.application.KonfigurationsdateiCodec;
import ch.urinet.archiv.konfiguration.domain.KonfigurationQuelle;
import ch.urinet.archiv.konfiguration.domain.KonfigurationsVersion;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JsonKonfigurationsdateiCodecTest {

    @Test
    void liestKommentareEntferntUndSetztDokumentierteDefaults() {
        KonfigurationsdateiCodec codec = codec();
        KonfigurationsdateiCodec.Datei datei = codec.lesen("""
                {
                  "format": 1, "basisVersion": 4, "_hinweis": "ignorieren",
                  "einstellungen": {
                    "geschaeftsjahrEnde": "12-31",
                    "kennungen": { "uid": null, "ibans": [] }
                  },
                  "kiTexte": { "rolle": "", "feldregeln": "", "kiFreigegeben": false },
                  "klassifikationen": [], "regeln": [],
                  "arten": [{
                    "code": "RECHNUNG", "name": "Rechnung", "gruppen": ["finanz"],
                    "pflichtfelder": ["titel", "datum"], "vertraulichkeit": "NORMAL",
                    "aufbewahrung": { "jahre": 10, "ab": "GESCHAEFTSJAHR" },
                    "gesetzlichePflicht": false, "selbstAusgestellt": true,
                    "zusatzSchema": { "type": "object", "properties": {}, "additionalProperties": false }
                  }]
                }
                """);

        assertThat(datei.basisVersion()).isEqualTo(4);
        assertThat(datei.inhalt().arten()).singleElement().satisfies(art -> {
            assertThat(art.aktiv()).isTrue();
            assertThat(art.kiVorschlag()).isTrue();
            assertThat(art.kiFelder()).isEmpty();
        });
        assertThat(codec.schreiben(datei.inhalt())).doesNotContain("_hinweis");
    }

    @Test
    void zuordnungenHeissenInDerDateiCode() throws Exception {
        KonfigurationsdateiCodec codec = codec();
        KonfigurationsdateiCodec.Datei datei = codec.lesen("""
                {
                  "format": 1, "basisVersion": 4,
                  "einstellungen": { "geschaeftsjahrEnde": "12-31", "kennungen": { "uid": null, "ibans": [] } },
                  "kiTexte": { "rolle": "", "feldregeln": "", "kiFreigegeben": false },
                  "klassifikationen": [], "regeln": [],
                  "arten": [{
                    "code": "BULLETIN", "name": "Bulletin", "gruppen": ["frei"], "pflichtfelder": ["titel"],
                    "vertraulichkeit": "NORMAL", "aufbewahrung": { "jahre": null }, "gesetzlichePflicht": false,
                    "zusatzSchema": { "type": "object" },
                    "klassifikationen": [{ "code": "fachgebiet", "pflicht": true, "standardWert": "bad" }]
                  }]
                }
                """);

        assertThat(datei.inhalt().arten().getFirst().klassifikationen()).singleElement()
                .satisfies(zuordnung -> assertThat(zuordnung.klassifikation()).isEqualTo("fachgebiet"));
        var zuordnung = new ObjectMapper().readTree(codec.schreiben(datei.inhalt()))
                .path("arten").get(0).path("klassifikationen").get(0);
        assertThat(zuordnung.path("code").asText()).isEqualTo("fachgebiet");
        assertThat(zuordnung.has("klassifikation")).isFalse();
    }

    @Test
    void weistDateienUeberEinMiBAb() {
        KonfigurationsdateiCodec codec = codec();

        assertThatThrownBy(() -> codec.lesen(" ".repeat(JsonKonfigurationsdateiCodec.MAX_BYTES + 1)))
                .isInstanceOf(FachFehler.class)
                .extracting(fehler -> ((FachFehler) fehler).code())
                .isEqualTo(Fehlercode.DATEI_ZU_GROSS);
    }

    private static KonfigurationsdateiCodec codec() {
        ZusatzSchemaPruefer pruefer = new ZusatzSchemaPruefer() {
            @Override
            public Set<String> schemaPruefen(String schemaJson) {
                return Set.of();
            }

            @Override
            public Map<String, String> feldTypen(String schemaJson) {
                return Map.of();
            }

            @Override
            public Set<String> seedSchemaPruefen(String schemaJson) {
                return Set.of();
            }

            @Override
            public void wertePruefen(String schemaJson, String werteJson) {
            }

            @Override
            public List<Verletzung> konfigurationPruefen(String schemaJson, String dateiJson) {
                return List.of();
            }

            @Override
            public Map<String, Object> werteLesen(String werteJson) {
                return Map.of();
            }

            @Override
            public boolean reservierteFelderGueltig(String schemaJson) {
                return true;
            }

            @Override
            public boolean datumsfeldGueltig(String schemaJson, String feld) {
                return true;
            }

            @Override
            public LocalDate datumswert(String werteJson, String feld) {
                throw new UnsupportedOperationException();
            }
        };
        KonfigurationVersionsRepository versionen = new KonfigurationVersionsRepository() {
            @Override
            public void sperren() {
            }

            @Override
            public Optional<KonfigurationsVersion> aktuell() {
                return Optional.empty();
            }

            @Override
            public Optional<KonfigurationsVersion> version(int nummer) {
                return Optional.empty();
            }

            @Override
            public List<KonfigurationsVersion> alle() {
                return List.of();
            }

            @Override
            public KonfigurationsVersion anlegen(int nummer, String wer, String grund, KonfigurationQuelle quelle,
                                                  ch.urinet.archiv.konfiguration.domain.Konfiguration inhalt) {
                throw new UnsupportedOperationException();
            }

            @Override
            public String kanonisch(String json) {
                return json;
            }
        };
        return new JsonKonfigurationsdateiCodec(new ObjectMapper(), pruefer, versionen);
    }
}
