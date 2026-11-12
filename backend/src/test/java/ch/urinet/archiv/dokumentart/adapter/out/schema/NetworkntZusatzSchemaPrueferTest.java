package ch.urinet.archiv.dokumentart.adapter.out.schema;

import ch.urinet.archiv.dokumentart.adapter.out.schema.NetworkntZusatzSchemaPruefer.Uhren;
import ch.urinet.archiv.gemeinsam.domain.FachFehler;
import ch.urinet.archiv.gemeinsam.domain.Fehlercode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.Schema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

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

    /** Zusatz-Schema der Art OFFERTE aus dem Seed {@code urinet} (Stufe A, 12.11.2026: fälschlich abgelehnt). */
    private static final String OFFERTE = """
            {"type":"object","properties":{"angebot_gueltig_bis":{"type":"string","format":"date"}},
             "additionalProperties":false}""";

    /** Verhalten ab json-schema-validator 3.x: {@code date-time} und {@code time} nach RFC 3339, Zahlen nach Wert. */
    private static final String SCHEMA_3X = """
            {"type":"object","properties":{"t":{"type":"string","format":"date-time"},
             "z":{"type":"string","format":"time"},"l":{"type":"array","uniqueItems":true},"n":{"type":"number"}}}""";

    private static final Duration GRENZE = Duration.ofMillis(50);
    private static final Duration SEED_GRENZE = Duration.ofSeconds(2);
    /** Wie lange ein gedrosselter Thread in einer CFS-Periode (100 ms) höchstens wartet, ohne zu rechnen. */
    private static final Duration DROSSELUNG = Duration.ofMillis(80);

    private final TestUhr uhr = new TestUhr();
    private final NetworkntZusatzSchemaPruefer pruefer = new NetworkntZusatzSchemaPruefer(GRENZE, SEED_GRENZE, () -> Uhren.eine(uhr));

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
    void werteNachRfc3339UndZahlenNachWertSindGueltig() {
        assertThatCode(() -> pruefer.wertePruefen(SCHEMA_3X,
                "{\"t\":\"2026-11-12T12:00:00-00:00\",\"z\":\"12:00:00.5+01:00\",\"l\":[1,2],\"n\":0.1}"))
                .doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"t\":\"2026-11-12 12:00:00Z\"}",
            "{\"t\":\"2016-12-31T23:59:60\"}",
            "{\"z\":\"12:00:00.Z\"}",
            "{\"l\":[1,1.0]}",
            "{\"n\":1e400}"
    })
    void werteAusserhalbVonRfc3339OderUnendlichSindUngueltig(String werte) {
        assertUngueltig(() -> pruefer.wertePruefen(SCHEMA_3X, werte));
    }

    @Test
    void schemaMitUnendlicherGrenzeIstUngueltig() {
        assertUngueltig(() -> pruefer.schemaPruefen("{\"type\":\"object\",\"maximum\":1e400}"));
    }

    /**
     * Review PR #83: nicht endliche Zahlen in Werten sind ungültig, auch ohne {@code type} im Schema. Neue, strengere
     * Regel: 2.0.8 nahm {@code 1e400} z. B. gegen {@code minimum: 0} an.
     */
    @ParameterizedTest
    @ValueSource(strings = {"\"minimum\":0", "\"minimum\":-1e-400", "\"multipleOf\":4.9e-324", "\"const\":1e400"})
    void unendlicheZahlIstAuchOhneTypUngueltig(String schluessel) {
        String schema = "{\"type\":\"object\",\"properties\":{\"n\":{" + schluessel + "}}}";
        assertUngueltig(() -> pruefer.wertePruefen(schema, "{\"n\":1e400}"));
        assertUngueltig(() -> pruefer.wertePruefen(schema, "{\"n\":-1e400}"));
        assertUngueltig(() -> pruefer.wertePruefen(schema, "{\"n\":[-1e400]}"));
        // NaN lehnt schon der strenge Leser ab, nicht erst Jackson3Baum.wert.
        assertUngueltig(() -> pruefer.wertePruefen(schema, "{\"n\":NaN}"));
    }

    /** Review PR #83 (Nachprüfung, Soll 3): ab 3.x strenger als 2.0.8. */
    @Test
    void strengereFaelleAb3xSindUngueltig() {
        assertUngueltig(() -> pruefer.wertePruefen(
                "{\"type\":\"object\",\"properties\":{\"s\":{\"minLength\":3000000000}}}", "{\"s\":\"x\"}"));
        assertUngueltig(() -> pruefer.wertePruefen(
                "{\"type\":\"object\",\"properties\":{\"n\":{\"multipleOf\":2}}}", "{\"n\":9007199254740993}"));
        assertUngueltig(() -> pruefer.schemaPruefen("{\"type\":\"object\",\"exclusiveMaximum\":1e400}"));
    }

    /** Ab 3.x (Review PR #83, Soll 1, Fälle 3 und 4): grosse Ganzzahlen und Grenzen über {@code int} nach Wert. */
    @Test
    void grosseGanzzahlenGeltenNachWert() {
        String gross = "1" + "0".repeat(900);
        assertThatCode(() -> pruefer.wertePruefen(
                "{\"type\":\"object\",\"properties\":{\"n\":{\"minimum\":0.1,\"multipleOf\":0.01}}}",
                "{\"n\":" + gross + "}")).doesNotThrowAnyException();
        assertThatCode(() -> pruefer.wertePruefen(
                "{\"type\":\"object\",\"properties\":{\"l\":{\"type\":\"array\",\"maxItems\":" + gross + "}}}",
                "{\"l\":[1,1.0]}")).doesNotThrowAnyException();
    }

    /**
     * Review PR #83, Kann 1: Die Registry des Prüfers lädt auch ohne die eigene Vorprüfung nichts nach – weder über
     * HTTP (0 Verbindungen auf einem lokalen Port) noch aus einer Datei.
     */
    @Test
    void registryLaedtOhneVorpruefungNichtsNach(@TempDir Path ordner) throws Exception {
        Path datei = Files.writeString(ordner.resolve("ziel.json"), "{\"type\":\"string\"}");
        AtomicInteger verbindungen = new AtomicInteger();
        try (ServerSocket server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress())) {
            Thread annehmen = Thread.ofPlatform().daemon().start(() -> {
                while (!server.isClosed()) {
                    try (Socket ignoriert = server.accept()) {
                        verbindungen.incrementAndGet();
                    } catch (IOException e) {
                        return;
                    }
                }
            });
            ObjectMapper leser = new ObjectMapper();
            for (String ziel : List.of("http://127.0.0.1:" + server.getLocalPort() + "/s.json", datei.toUri().toString(),
                    "file:" + datei)) {
                tools.jackson.databind.JsonNode schema = Jackson3Baum.von(leser.readTree(
                        "{\"type\":\"object\",\"properties\":{\"a\":{\"$ref\":\"" + ziel + "\"}}}"));
                assertThatThrownBy(() -> {
                    Schema kompiliert = pruefer.registry().getSchema(schema);
                    kompiliert.initializeValidators();
                    kompiliert.validate(Jackson3Baum.von(leser.readTree("{\"a\":\"x\"}")));
                }).as(ziel).isInstanceOf(RuntimeException.class);
            }
            server.close();
            annehmen.join(Duration.ofSeconds(1));
        }
        assertThat(verbindungen).hasValue(0);
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

    /**
     * Mit der Uhr der Anwendung (Konstruktor der Anwendung, CPU-Zeit des Threads) endet dasselbe Muster ebenfalls,
     * statt Stunden zu rechnen; auch mit der Seed-Frist bleibt die Prüfung begrenzt.
     */
    @Test
    void rueckfallreichesMusterBrichtAuchMitDerUhrDerAnwendungAb() {
        var anwendung = new NetworkntZusatzSchemaPruefer(GRENZE, SEED_GRENZE);
        String schema = "{\"type\":\"object\",\"properties\":{\"a\":{\"type\":\"string\",\"pattern\":\"^(.*a){8}x$\"}}}";
        String werte = "{\"a\":\"" + "a".repeat(100) + "\"}";
        assertTimeoutPreemptively(Duration.ofSeconds(10),
                () -> assertUngueltig(() -> anwendung.wertePruefen(schema, werte)));
    }

    /**
     * Ursache Stufe A (12.11.2026, {@code cpus: 0.3}): Wartet der Thread während der Prüfung länger als die Frist auf
     * CPU (CFS-Drosselung), lehnt eine Frist nach der Wanduhr das gültige Schema der OFFERTE ab, obwohl die Prüfung
     * nur rund 1 ms rechnet. Die Wartezeit ist hier ein echtes {@code sleep} beim zweiten Ablesen der Uhr.
     */
    @Test
    void gedrosselteCpuZaehltMitDerWanduhrAlsRechenzeit() {
        var wanduhr = new NetworkntZusatzSchemaPruefer(GRENZE, SEED_GRENZE, () -> Uhren.eine(gedrosselt(System::nanoTime)));
        assertUngueltig(() -> wanduhr.schemaPruefen(OFFERTE));
    }

    /**
     * Mit der CPU-Zeit des Threads (Uhr der Anwendung) zählt die Wartezeit nicht: Seed-Prüfung (Admin-Kommando),
     * Anlegen einer Art und Speichern von Zusatzwerten über die API nehmen gültige Eingaben an, ungültige Werte bleiben
     * {@code ZUSATZ_UNGUELTIG}.
     */
    @Test
    void gedrosselteCpuZaehltMitDerUhrDerAnwendungNicht() {
        assumeTrue(ManagementFactory.getThreadMXBean().isCurrentThreadCpuTimeSupported(), "CPU-Zeit des Threads");
        var anwendung = new NetworkntZusatzSchemaPruefer(GRENZE, SEED_GRENZE,
                () -> gedrosselt(NetworkntZusatzSchemaPruefer.uhrenDesThreads()));
        assertThat(anwendung.seedSchemaPruefen(OFFERTE)).containsExactly("angebot_gueltig_bis");
        var api = new NetworkntZusatzSchemaPruefer(GRENZE, SEED_GRENZE,
                () -> gedrosselt(NetworkntZusatzSchemaPruefer.uhrenDesThreads()));
        assertThat(api.schemaPruefen(OFFERTE)).containsExactly("angebot_gueltig_bis");
        assertThatCode(() -> api.wertePruefen(OFFERTE, "{\"angebot_gueltig_bis\":\"2026-12-31\"}"))
                .doesNotThrowAnyException();
        assertUngueltig(() -> api.wertePruefen(OFFERTE, "{\"angebot_gueltig_bis\":\"31.12.2026\"}"));
    }

    /** Die Uhr der Anwendung ist die CPU-Zeit eines Plattform-Threads; im virtuellen Thread fällt sie auf die Wanduhr zurück. */
    @Test
    void uhrDerAnwendungMisstCpuZeitSonstWanduhr() throws Exception {
        assumeTrue(ManagementFactory.getThreadMXBean().isCurrentThreadCpuTimeSupported(), "CPU-Zeit des Threads");
        assertThat(wartezeitGemessen(NetworkntZusatzSchemaPruefer.uhrenDesThreads().rechen())).isLessThan(DROSSELUNG.toNanos() / 2);
        AtomicReference<Long> virtuell = new AtomicReference<>();
        Thread.ofVirtual().start(() -> virtuell.set(wartezeitGemessen(NetworkntZusatzSchemaPruefer.uhrenDesThreads().rechen())))
                .join();
        assertThat(virtuell.get()).isGreaterThanOrEqualTo(DROSSELUNG.toNanos());
    }

    /**
     * Der öffentliche Konstruktor (Anwendung) misst die Rechenzeit (Review PR #81, S2): Der prüfende Thread wartet
     * {@link #DROSSELUNG} an der Sperre des Caches, nachdem die Frist begonnen hat, und rechnet dabei nicht. Mit der
     * Wanduhr wäre die Frist danach abgelaufen und das gültige Schema {@code ZUSATZ_UNGUELTIG}.
     */
    @Test
    void anwendungZaehltWartezeitNichtAlsRechenzeit() throws Exception {
        assumeTrue(ManagementFactory.getThreadMXBean().isCurrentThreadCpuTimeSupported(), "CPU-Zeit des Threads");
        var anwendung = new NetworkntZusatzSchemaPruefer(GRENZE, SEED_GRENZE);
        AtomicReference<Object> ergebnis = new AtomicReference<>();
        Thread pruefend;
        synchronized (anwendung.cache) {
            pruefend = Thread.ofPlatform().start(() -> {
                try {
                    ergebnis.set(anwendung.schemaPruefen(OFFERTE));
                } catch (RuntimeException e) {
                    ergebnis.set(e);
                }
            });
            long bis = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            while (!wartetAufSperre(pruefend, anwendung.cache)) {
                assertThat(System.nanoTime() - bis).as("Thread wartet an der Sperre des Caches").isNegative();
                Thread.onSpinWait();
            }
            schlafen();
        }
        pruefend.join(Duration.ofSeconds(10));
        assertThat(ergebnis.get()).isEqualTo(Set.of("angebot_gueltig_bis"));
    }

    private static boolean wartetAufSperre(Thread thread, Object sperre) {
        ThreadInfo info = ManagementFactory.getThreadMXBean().getThreadInfo(thread.threadId());
        return info != null && info.getThreadState() == Thread.State.BLOCKED && info.getLockInfo() != null
                && info.getLockInfo().getIdentityHashCode() == System.identityHashCode(sperre);
    }

    /**
     * Die Rechenzeit ist unter Linux ein Systemaufruf (rund 0.6 µs unter {@code --cpus 0.3}); vor jedem Schlüsselwort
     * gelesen, frass sie bei einem gültigen Wert von 64 KiB den Grossteil der 50 ms (Review PR #81, S1). Die Frist
     * liest sie erst, wenn die Wanduhr den Rest der Frist überschritten hat. Hier läuft die Wanduhr 10 µs je Ablesen,
     * die Rechenzeit 5 % davon (gedrosselt): Der Wert ist gültig, und die Rechenzeit wird nur selten gelesen.
     */
    @Test
    void grosserGueltigerWertLiestDieRechenzeitSelten() {
        String schema = "{\"type\":\"object\",\"properties\":{\"l\":{\"type\":\"array\","
                + "\"items\":{\"type\":\"integer\",\"minimum\":0}}}}";
        int anzahl = (NetworkntZusatzSchemaPruefer.MAX_WERTE_BYTES - 16) / 2;
        String werte = "{\"l\":[" + String.join(",", Collections.nCopies(anzahl, "1")) + "]}";
        AtomicLong wand = new AtomicLong();
        AtomicLong rechen = new AtomicLong();
        Uhren uhren = new Uhren(() -> wand.incrementAndGet() * 10_000, () -> {
            rechen.incrementAndGet();
            return wand.get() * 500;
        });
        var gedrosselt = new NetworkntZusatzSchemaPruefer(GRENZE, SEED_GRENZE, () -> uhren);

        assertThatCode(() -> gedrosselt.wertePruefen(schema, werte)).doesNotThrowAnyException();
        assertThat(wand.get() * 10_000).as("Wanduhr über der Frist").isGreaterThan(GRENZE.toNanos());
        assertThat(rechen.get()).as("Ablesen der Rechenzeit").isBetween(2L, 100L);
    }

    /**
     * Der Seed hat eine eigene, grössere Frist; sie begrenzt die Prüfung trotzdem. Je Prüfung ein neuer Prüfer, weil
     * der Cache sonst das Schema ohne Prüfung liefert; die Uhr rückt so vor, dass die Prüfung insgesamt 100 ms dauert.
     */
    @Test
    void seedHatEigeneGroessereFrist() {
        long ablesen = uhr.gelesen();
        assertThat(pruefer.seedSchemaPruefen(OFFERTE)).containsExactly("angebot_gueltig_bis");
        Duration schritt = GRENZE.multipliedBy(2).dividedBy(uhr.gelesen() - ablesen);

        var api = new TestUhr();
        api.schritt(schritt);
        assertUngueltig(() -> new NetworkntZusatzSchemaPruefer(GRENZE, SEED_GRENZE, () -> Uhren.eine(api)).schemaPruefen(OFFERTE));
        var seed = new TestUhr();
        seed.schritt(schritt);
        assertThat(new NetworkntZusatzSchemaPruefer(GRENZE, SEED_GRENZE, () -> Uhren.eine(seed)).seedSchemaPruefen(OFFERTE))
                .containsExactly("angebot_gueltig_bis");
        var langsam = new TestUhr();
        langsam.schritt(SEED_GRENZE);
        assertUngueltig(() -> new NetworkntZusatzSchemaPruefer(GRENZE, SEED_GRENZE, () -> Uhren.eine(langsam))
                .seedSchemaPruefen(OFFERTE));
    }

    /** Wie {@link #gedrosselt(LongSupplier)}; die Wartezeit fällt beim zweiten Ablesen der Wanduhr an. */
    private static Uhren gedrosselt(Uhren uhren) {
        return new Uhren(gedrosselt(uhren.wand()), uhren.rechen());
    }

    /** Uhr, deren Thread beim zweiten Ablesen {@link #DROSSELUNG} lang nicht rechnet (wie unter CFS-Drosselung). */
    private static LongSupplier gedrosselt(LongSupplier uhr) {
        AtomicLong ablesen = new AtomicLong();
        return () -> {
            if (ablesen.incrementAndGet() == 2) {
                schlafen();
            }
            return uhr.getAsLong();
        };
    }

    private static long wartezeitGemessen(LongSupplier uhr) {
        long vorher = uhr.getAsLong();
        schlafen();
        return uhr.getAsLong() - vorher;
    }

    private static void schlafen() {
        try {
            Thread.sleep(DROSSELUNG);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
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

    /**
     * Werte mit {@code format: regex} (Auftrag 40): Die Bibliothek kompiliert jeden Wert über unsere Fabrik, ohne dass
     * ein Schlüsselwort mit Frist dazwischen liegt; ohne Frist vor dem Kompilieren nimmt die Prüfung die Werte an.
     */
    @Test
    void formatRegexBrichtNachDerZeitgrenzeAb() {
        String schema = "{\"type\":\"object\",\"properties\":{\"x\":{\"type\":\"array\",\"items\":{\"format\":\"regex\"}}}}";
        StringBuilder werte = new StringBuilder("{\"x\":[");
        for (int i = 0; i < 200; i++) {
            werte.append(i == 0 ? "" : ",").append("\"a").append(i).append('"');
        }
        brichtNachDerZeitgrenzeAb(schema, werte.append("]}").toString());
    }

    @Test
    void formatRegexPrueftWerteUnterDerZeitgrenze() {
        String schema = "{\"type\":\"object\",\"properties\":{\"x\":{\"type\":\"string\",\"format\":\"regex\"}}}";
        assertThatCode(() -> pruefer.wertePruefen(schema, "{\"x\":\"^a+$\"}")).doesNotThrowAnyException();
        assertUngueltig(() -> pruefer.wertePruefen(schema, "{\"x\":\"(\"}"));
    }

    /**
     * Ohne Grenze rechnet ein einzelner langer Ausdruck ausserhalb jeder Fristprüfung: {@code Pattern.compile} braucht
     * für ein Literal von 48'000 Zeichen rund 0.4 s (Boyer-Moore-Vorbereitung), die Ersetzungen der Bibliothek für
     * offene Zeicheneigenschaften wachsen quadratisch.
     */
    @Test
    void regulaererAusdruckHatHoechstens1000Zeichen() {
        String schema = "{\"type\":\"object\",\"properties\":{\"x\":{\"type\":\"string\",\"format\":\"regex\"}}}";
        int max = NetworkntZusatzSchemaPruefer.MAX_MUSTER_ZEICHEN;
        assertThatCode(() -> pruefer.wertePruefen(schema, "{\"x\":\"" + "a".repeat(max) + "\"}"))
                .doesNotThrowAnyException();
        assertUngueltig(() -> pruefer.wertePruefen(schema, "{\"x\":\"" + "a".repeat(max + 1) + "\"}"));
        assertThat(pruefer.schemaPruefen(musterSchema("a".repeat(max)))).containsExactly("x");
        assertUngueltig(() -> pruefer.schemaPruefen(musterSchema("a".repeat(max + 1))));
    }

    private static String musterSchema(String muster) {
        return "{\"type\":\"object\",\"properties\":{\"x\":{\"type\":\"string\",\"pattern\":\"" + muster + "\"}}}";
    }

    @Test
    void zeitgrenzeMussPositivSein() {
        assertThatThrownBy(() -> new NetworkntZusatzSchemaPruefer(Duration.ZERO, SEED_GRENZE))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("archiv.dokumentart.muster-zeitgrenze");
        assertThatThrownBy(() -> new NetworkntZusatzSchemaPruefer(GRENZE, Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("archiv.dokumentart.seed-zeitgrenze");
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
        private final AtomicLong gelesen = new AtomicLong();
        private volatile long schritt;

        @Override
        public long getAsLong() {
            gelesen.incrementAndGet();
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

        long gelesen() {
            return gelesen.get();
        }
    }

    private static void assertUngueltig(org.assertj.core.api.ThrowableAssert.ThrowingCallable aufruf) {
        assertThatThrownBy(aufruf).isInstanceOfSatisfying(FachFehler.class,
                f -> assertThat(f.code()).isEqualTo(Fehlercode.ZUSATZ_UNGUELTIG));
    }
}
