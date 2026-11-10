package ch.urinet.archiv.dokumentart.adapter.out.schema;

import ch.urinet.archiv.gemeinsam.domain.FachFehler;
import ch.urinet.archiv.gemeinsam.domain.Fehlercode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Req 9.5: Zusatz-Schema und Zusatzwerte; Schutz gegen SSRF und übergrosse Eingaben. */
class NetworkntZusatzSchemaPrueferTest {

    private static final String SCHEMA = """
            {"type":"object","additionalProperties":false,
             "required":["periode"],
             "properties":{"periode":{"type":"string","pattern":"^\\\\d{4}-\\\\d{2}$"},
                           "faellig":{"type":"string","format":"date"},
                           "betrag":{"$ref":"#/$defs/betrag"}},
             "$defs":{"betrag":{"type":"number","minimum":0}}}""";

    private final NetworkntZusatzSchemaPruefer pruefer = new NetworkntZusatzSchemaPruefer();

    @Test
    void gueltigesSchemaLiefertFeldnamen() {
        assertThat(pruefer.schemaPruefen(SCHEMA)).containsExactlyInAnyOrder("periode", "faellig", "betrag");
        assertThat(pruefer.schemaPruefen("{\"type\":\"object\"}")).isEmpty();
        assertThat(pruefer.schemaPruefen(
                "{\"$schema\":\"https://json-schema.org/draft/2020-12/schema\",\"type\":\"object\"}")).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "",
            "kein json",
            "[]",
            "{}",
            "{\"type\":\"string\"}",
            "{\"type\":\"object\"} {}",
            "{\"type\":\"object\",\"type\":\"object\"}",
            "{\"type\":\"object\",\"properties\":{\"a\":{\"type\":\"zahl\"}}}",
            "{\"type\":\"object\",\"properties\":{\"a\":{\"type\":\"string\",\"pattern\":\"(\"}}}",
            "{\"type\":\"object\",\"properties\":{\"a\":{\"$ref\":\"#/$defs/fehlt\"}}}",
            "{\"type\":\"object\",\"properties\":{\"a\":{\"$ref\":\"https://example.org/s.json\"}}}",
            "{\"type\":\"object\",\"properties\":{\"a\":{\"$ref\":\"file:///etc/passwd\"}}}",
            "{\"type\":\"object\",\"properties\":{\"a\":{\"$ref\":\"andere.json#/x\"}}}",
            "{\"type\":\"object\",\"properties\":{\"a\":{\"$dynamicRef\":\"http://127.0.0.1/x\"}}}",
            "{\"type\":\"object\",\"$id\":\"https://example.org/s\"}",
            "{\"type\":\"object\",\"$vocabulary\":{}}",
            "{\"type\":\"object\",\"$schema\":\"http://json-schema.org/draft-07/schema#\"}",
            "{\"type\":\"object\",\"$schema\":\"https://example.org/meta\"}"
    })
    void ungueltigesSchemaIstZusatzUngueltig(String schema) {
        assertUngueltig(() -> pruefer.schemaPruefen(schema));
    }

    @Test
    void schemaUeberDenGrenzenIstUngueltig() {
        String gross = "{\"type\":\"object\",\"description\":\"" + "x".repeat(NetworkntZusatzSchemaPruefer.MAX_SCHEMA_BYTES)
                + "\"}";
        assertUngueltig(() -> pruefer.schemaPruefen(gross));
        String tief = "{\"type\":\"object\",\"properties\":{\"a\":" + "{\"items\":".repeat(10) + "{}" + "}".repeat(10)
                + "}}";
        assertUngueltig(() -> pruefer.schemaPruefen(tief));
        assertUngueltig(() -> pruefer.schemaPruefen(null));
    }

    @Test
    void passendeWerteSindGueltig() {
        assertThatCode(() -> pruefer.wertePruefen(SCHEMA,
                "{\"periode\":\"2026-03\",\"faellig\":\"2026-04-30\",\"betrag\":12.5}")).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{}",
            "{\"periode\":\"03.2026\"}",
            "{\"periode\":\"2026-03\",\"faellig\":\"30.04.2026\"}",
            "{\"periode\":\"2026-03\",\"faellig\":\"2026-02-30\"}",
            "{\"periode\":\"2026-03\",\"betrag\":-1}",
            "{\"periode\":\"2026-03\",\"fremd\":1}",
            "[]",
            "kein json"
    })
    void unpassendeWerteSindZusatzUngueltig(String werte) {
        assertUngueltig(() -> pruefer.wertePruefen(SCHEMA, werte));
    }

    @Test
    void werteUeberDerGrenzeSindUngueltig() {
        String gross = "{\"periode\":\"" + "x".repeat(NetworkntZusatzSchemaPruefer.MAX_WERTE_BYTES) + "\"}";
        assertUngueltig(() -> pruefer.wertePruefen("{\"type\":\"object\"}", gross));
    }

    private static void assertUngueltig(org.assertj.core.api.ThrowableAssert.ThrowingCallable aufruf) {
        assertThatThrownBy(aufruf).isInstanceOfSatisfying(FachFehler.class,
                f -> assertThat(f.code()).isEqualTo(Fehlercode.ZUSATZ_UNGUELTIG));
    }
}
