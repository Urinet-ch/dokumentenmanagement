package ch.urinet.archiv.dokumentart.adapter.out.schema;

import ch.urinet.archiv.gemeinsam.domain.FachFehler;
import ch.urinet.archiv.gemeinsam.domain.Fehlercode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/**
 * Req 9.5: Zusatz-Schema und Zusatzwerte; Schutz gegen SSRF, übergrosse Eingaben, Rechenzeit und Zyklen.
 * <p>
 * Die Frist läuft gegen eine {@link TestUhr} statt gegen die Wanduhr (PR #40, CI auf langsamem Runner): Sie steht still,
 * solange ein Test nur prüft, dass etwas unter der Grenze durchläuft, und rückt beim Abbruch-Test mit jedem Ablesen
 * um 1 ms vor. So bricht die Prüfung auf jedem Rechner nach gleich vielen Fristprüfungen ab.
 */
class NetworkntZusatzSchemaPrueferTest {

    private static final String SCHEMA = """
            {"type":"object","additionalProperties":false,
             "required":["periode"],
             "properties":{"periode":{"type":"string","pattern":"^\\\\d{4}-\\\\d{2}$"},
                           "faellig":{"type":"string","format":"date"},
                           "betrag":{"$ref":"#/$defs/betrag"}},
             "$defs":{"betrag":{"type":"number","minimum":0}}}""";

    private static final Duration GRENZE = Duration.ofMillis(50);

    private final TestUhr uhr = new TestUhr();
    private final NetworkntZusatzSchemaPruefer pruefer = new NetworkntZusatzSchemaPruefer(GRENZE, uhr);

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
     * Zeichen «a» Stunden; mit Grenze bricht die Prüfung mit {@code ZUSATZ_UNGUELTIG} ab. Vor dem Suchen liest die
     * Prüfung die Uhr nur wenige Male, den Rest der 50 Schritte liefert die Prüfung alle 1'024 Zeichenzugriffe.
     */
    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "{\"type\":\"object\",\"properties\":{\"a\":{\"type\":\"string\",\"pattern\":\"^(.*a){8}x$\"}}} | {\"a\":\"%s\"}",
            "{\"type\":\"object\",\"patternProperties\":{\"^(.*a){8}x$\":{}}} | {\"%s\":1}",
            "{\"type\":\"object\",\"propertyNames\":{\"pattern\":\"^(.*a){8}x$\"}} | {\"%s\":1}"
    })
    void rueckfallreichesMusterBrichtNachDerZeitgrenzeAb(String schema, String werte) {
        brichtNachDerZeitgrenzeAb(schema, werte.formatted("a".repeat(100)));
    }

    /** Mit der Wanduhr (Konstruktor der Anwendung) endet dasselbe Muster ebenfalls, statt Stunden zu rechnen. */
    @Test
    void rueckfallreichesMusterBrichtAuchMitDerWanduhrAb() {
        var mitWanduhr = new NetworkntZusatzSchemaPruefer(GRENZE);
        String schema = "{\"type\":\"object\",\"properties\":{\"a\":{\"type\":\"string\",\"pattern\":\"^(.*a){8}x$\"}}}";
        String werte = "{\"a\":\"" + "a".repeat(100) + "\"}";
        assertTimeoutPreemptively(Duration.ofSeconds(10),
                () -> assertUngueltig(() -> mitWanduhr.wertePruefen(schema, werte)));
    }

    /**
     * Viele kurze Treffer (Review PR #40 Nacht, S1): Jeder Treffer bleibt unter 1'024 Zeichenzugriffen, darum muss die
     * Frist auch vor jeder Musterprüfung greifen, sonst rechnet die Prüfung rund eine Sekunde und nimmt die Werte an.
     */
    @ParameterizedTest
    @ValueSource(strings = {"items", "patternProperties"})
    void vieleKurzeTrefferBrechenNachDerZeitgrenzeAb(String art) {
        String schema;
        String werte;
        if ("items".equals(art)) {
            schema = "{\"type\":\"object\",\"properties\":{\"x\":{\"type\":\"array\",\"items\":{\"allOf\":["
                    + String.join(",", Collections.nCopies(400, "{\"pattern\":\"^(a|aa)*$\"}")) + "]}}}}";
            werte = "{\"x\":[" + String.join(",", Collections.nCopies(3000, "\"aaaaaaaaaaaaa\"")) + "]}";
        } else {
            StringBuilder muster = new StringBuilder();
            for (int i = 0; i < 400; i++) {
                muster.append(i == 0 ? "" : ",").append("\"^(a|aa)*b").append(i).append("\":{}");
            }
            schema = "{\"type\":\"object\",\"patternProperties\":{" + muster + "}}";
            StringBuilder schluessel = new StringBuilder();
            for (int i = 0; i < 2500; i++) {
                schluessel.append(i == 0 ? "" : ",").append("\"aaaaaaaaaaaa%04d\":1".formatted(i));
            }
            werte = "{" + schluessel + "}";
        }
        brichtNachDerZeitgrenzeAb(schema, werte);
    }

    /**
     * Verweise ohne Kreis, aber mit 2^k Pfaden (Review PR #40 Nacht, S2): Die Frist gilt für die ganze Werteprüfung,
     * nicht nur für Muster; ohne sie rechnet k=26 mehrere Sekunden.
     */
    @Test
    void exponentiellerVerweisGraphBrichtNachDerZeitgrenzeAb() {
        int k = 26;
        StringBuilder defs = new StringBuilder();
        for (int i = 0; i < k; i++) {
            String naechster = "{\"$ref\":\"#/$defs/d" + (i + 1) + "\"}";
            defs.append("\"d").append(i).append("\":{\"allOf\":[").append(naechster).append(',').append(naechster)
                    .append("]},");
        }
        defs.append("\"d").append(k).append("\":{\"type\":\"string\"}");
        String schema = "{\"type\":\"object\",\"properties\":{\"x\":{\"$ref\":\"#/$defs/d0\"}},\"$defs\":{" + defs + "}}";
        assertThat(pruefer.schemaPruefen(schema)).containsExactly("x");
        brichtNachDerZeitgrenzeAb(schema, "{\"x\":\"a\"}");
    }

    @Test
    void zeitgrenzeMussPositivSein() {
        assertThatThrownBy(() -> new NetworkntZusatzSchemaPruefer(Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("archiv.dokumentart.muster-zeitgrenze");
    }

    /**
     * Das Schema läuft bei stehender Uhr unter der Grenze durch; danach rückt die Uhr bei jedem Ablesen um 1 ms vor,
     * und die Werteprüfung muss mit {@code ZUSATZ_UNGUELTIG} enden, nachdem die Uhr die Frist überschritten hat (sonst
     * wäre es ein gewöhnlicher Prüffehler, kein Abbruch). Die 10 s fangen nur ein Hängen ab.
     */
    private void brichtNachDerZeitgrenzeAb(String schema, String werte) {
        assertThat(pruefer.schemaPruefen(schema)).isNotNull();
        uhr.schritt(Duration.ofMillis(1));
        long start = uhr.jetzt();
        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> assertUngueltig(() -> pruefer.wertePruefen(schema, werte)));
        assertThat(uhr.zuletztAbgelesen() - start).as("Abbruch durch die Frist").isGreaterThan(GRENZE.toNanos());
    }

    /** Uhr in Nanosekunden, die bei jedem Ablesen um {@code schritt} vorrückt (anfangs 0: sie steht still). */
    private static final class TestUhr implements LongSupplier {
        private final AtomicLong jetzt = new AtomicLong();
        private final AtomicLong zuletzt = new AtomicLong();
        private volatile long schritt;

        @Override
        public long getAsLong() {
            long wert = jetzt.getAndAdd(schritt);
            zuletzt.set(wert);
            return wert;
        }

        void schritt(Duration dauer) {
            schritt = dauer.toNanos();
        }

        long jetzt() {
            return jetzt.get();
        }

        long zuletztAbgelesen() {
            return zuletzt.get();
        }
    }

    private static void assertUngueltig(org.assertj.core.api.ThrowableAssert.ThrowingCallable aufruf) {
        assertThatThrownBy(aufruf).isInstanceOfSatisfying(FachFehler.class,
                f -> assertThat(f.code()).isEqualTo(Fehlercode.ZUSATZ_UNGUELTIG));
    }
}
