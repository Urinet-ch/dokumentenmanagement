package ch.urinet.archiv.dokumentart.adapter.out.schema;

import ch.urinet.archiv.gemeinsam.domain.FachFehler;
import ch.urinet.archiv.gemeinsam.domain.Fehlercode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/** Req 9.5: Zusatz-Schema und Zusatzwerte; Schutz gegen SSRF, übergrosse Eingaben, Rechenzeit und Zyklen. */
class NetworkntZusatzSchemaPrueferTest {

    private static final String SCHEMA = """
            {"type":"object","additionalProperties":false,
             "required":["periode"],
             "properties":{"periode":{"type":"string","pattern":"^\\\\d{4}-\\\\d{2}$"},
                           "faellig":{"type":"string","format":"date"},
                           "betrag":{"$ref":"#/$defs/betrag"}},
             "$defs":{"betrag":{"type":"number","minimum":0}}}""";

    private final NetworkntZusatzSchemaPruefer pruefer = new NetworkntZusatzSchemaPruefer(Duration.ofMillis(50));

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
            "{\"type\":\"object\",\"$schema\":\"https://example.org/meta\"}",
            // Verweise im Kreis ohne Fortschritt in den Daten (Review PR #40, Soll 3)
            "{\"type\":\"object\",\"$ref\":\"#\"}",
            "{\"type\":\"object\",\"not\":{\"$ref\":\"#\"}}",
            "{\"type\":\"object\",\"$defs\":{\"a\":{\"$ref\":\"#/$defs/a\"}}}",
            "{\"type\":\"object\",\"properties\":{\"x\":{\"$ref\":\"#/$defs/a\"}},"
                    + "\"$defs\":{\"a\":{\"allOf\":[{\"$ref\":\"#/$defs/b\"}]},\"b\":{\"anyOf\":[{\"$ref\":\"#/$defs/a\"}]}}}",
            "{\"type\":\"object\",\"dependentSchemas\":{\"x\":{\"$ref\":\"#\"}}}",
            "{\"type\":\"object\",\"properties\":{\"a\":{\"$ref\":\"#anker\"}}}"
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

    @Test
    void rekursionUeberDieDatenBleibtErlaubt() {
        String baum = "{\"type\":\"object\",\"properties\":{\"name\":{\"type\":\"string\"},"
                + "\"kind\":{\"$ref\":\"#\"}}}";
        assertThat(pruefer.schemaPruefen(baum)).containsExactlyInAnyOrder("name", "kind");
        assertThatCode(() -> pruefer.wertePruefen(baum, "{\"name\":\"a\",\"kind\":{\"kind\":{\"name\":\"c\"}}}"))
                .doesNotThrowAnyException();
        assertUngueltig(() -> pruefer.wertePruefen(baum, "{\"kind\":{\"name\":1}}"));
    }

    /**
     * Rückfallreiches Muster (ReDoS, Review PR #40, Soll 2): ohne Zeitgrenze rechnet {@code ^(.*a){8}x$} gegen 100
     * Zeichen «a» Stunden; mit Grenze endet die Prüfung nach rund 50 ms mit {@code ZUSATZ_UNGUELTIG}.
     */
    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "{\"type\":\"object\",\"properties\":{\"a\":{\"type\":\"string\",\"pattern\":\"^(.*a){8}x$\"}}} | {\"a\":\"%s\"}",
            "{\"type\":\"object\",\"patternProperties\":{\"^(.*a){8}x$\":{}}} | {\"%s\":1}",
            "{\"type\":\"object\",\"propertyNames\":{\"pattern\":\"^(.*a){8}x$\"}} | {\"%s\":1}"
    })
    void rueckfallreichesMusterBrichtNachDerZeitgrenzeAb(String schema, String werte) {
        String text = werte.formatted("a".repeat(100));
        assertThat(pruefer.schemaPruefen(schema)).isNotNull();
        long start = System.nanoTime();
        assertTimeoutPreemptively(Duration.ofSeconds(10),
                () -> assertUngueltig(() -> pruefer.wertePruefen(schema, text)));
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(1));
    }

    @Test
    void zeitgrenzeMussPositivSein() {
        assertThatThrownBy(() -> new NetworkntZusatzSchemaPruefer(Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("archiv.dokumentart.muster-zeitgrenze");
    }

    private static void assertUngueltig(org.assertj.core.api.ThrowableAssert.ThrowingCallable aufruf) {
        assertThatThrownBy(aufruf).isInstanceOfSatisfying(FachFehler.class,
                f -> assertThat(f.code()).isEqualTo(Fehlercode.ZUSATZ_UNGUELTIG));
    }
}
