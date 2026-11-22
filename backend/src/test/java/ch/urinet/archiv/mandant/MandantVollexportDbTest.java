package ch.urinet.archiv.mandant;

import ch.urinet.archiv.ablage.domain.AbgelegteDatei;
import ch.urinet.archiv.ablage.application.AblagePort;
import ch.urinet.archiv.konfiguration.application.KonfigurationsdateiVerwaltung;
import ch.urinet.archiv.konfiguration.application.KonfigurationsdateiCodec;
import ch.urinet.archiv.konfiguration.domain.Konfiguration;
import ch.urinet.archiv.mandant.application.MandantKontext;
import ch.urinet.archiv.mandant.application.MandantAufloesung;
import ch.urinet.archiv.mandant.application.MandantProvisionierung;
import ch.urinet.archiv.mandant.application.MandantVollexportDaten;
import ch.urinet.archiv.mandant.application.MandantVollexportKonfiguration;
import ch.urinet.archiv.mandant.application.MandantVollexportRepository;
import ch.urinet.archiv.mandant.application.MandantVollexportSchreiber;
import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.mandant.adapter.out.persistence.JdbcMandantVollexportKonfiguration;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.agroal.api.AgroalDataSource;
import io.agroal.api.security.SimplePassword;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.io.ByteArrayOutputStream;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipFile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Vollexport gegen die echte Datenbank (Req 8.2): Die JDBC-Adapter lesen nur das Schema des Mandanten im Kontext,
 * der Seitenwechsel über 200 Dokumente verliert und verdoppelt nichts, und der Exportstatus wechselt nur einmal.
 */
@QuarkusTest
class MandantVollexportDbTest {

    private static final MandantCode A = new MandantCode("urinet");
    private static final MandantCode B = new MandantCode("vhf-gsk");
    // eigener Thread ohne Request-Kontext, wie im Worker
    private static final ExecutorService THREADS = Executors.newSingleThreadExecutor();

    @Inject
    MandantKontext kontext;

    @Inject
    MandantProvisionierung provisionierung;

    @Inject
    AgroalDataSource datenbank;

    @Inject
    Instance<AgroalDataSource> datenquelle;

    @Inject
    MandantVollexportDaten daten;

    @Inject
    MandantVollexportRepository repository;

    @Inject
    MandantVollexportKonfiguration konfiguration;

    @Inject
    ObjectMapper json;

    @Inject
    KonfigurationsdateiVerwaltung dateiVerwaltung;

    @Inject
    KonfigurationsdateiCodec codec;

    @AfterAll
    static void beenden() {
        THREADS.shutdownNow();
    }

    @BeforeEach
    void mandanten() throws SQLException {
        for (MandantCode code : List.of(A, B)) {
            if (!imRegister(code.wert())) {
                provisionierung.anlegen(code.wert(), null, null);
            }
        }
    }

    @Test
    void exportLiestNurDasSchemaDesKontextesUeberAlleSeiten() throws Exception {
        Set<UUID> idsA = dokumenteEinfuegen("m_urinet", 205);
        Set<UUID> idsB = dokumenteEinfuegen("m_vhf_gsk", 3);

        List<UUID> gelesenA = alleIds(A);
        List<UUID> gelesenB = alleIds(B);

        assertThat(gelesenA).doesNotHaveDuplicates().containsAll(idsA).doesNotContainAnyElementsOf(idsB);
        assertThat(gelesenB).doesNotHaveDuplicates().containsAll(idsB).doesNotContainAnyElementsOf(idsA);
        assertThat(gelesenA.size()).isGreaterThanOrEqualTo(205);
    }

    @Test
    void konfigurationsversionenWerdenVollstaendigUndMandantengetrenntGestreamt(@TempDir Path temp) throws Exception {
        try (Connection verbindung = datenbank.getConnection();
             PreparedStatement a = verbindung.prepareStatement("""
                     insert into m_urinet.konfiguration_version (version, wer, quelle, inhalt, sha256)
                     select n, 'test', 'APP', case when n = 10001
                         then jsonb_set(basis.inhalt, '{kiTexte,rolle}', to_jsonb(repeat('ä🙂', 70000)))
                         when n = 10201 then jsonb_set(basis.inhalt, '{arten}',
                             (select jsonb_agg(jsonb_set(jsonb_set(basis.inhalt->'arten'->0, '{code}',
                                 to_jsonb('SYNTH' || g)), '{zusatzSchema}',
                                 to_jsonb(('{"type":"object","properties":{},"description":"'
                                     || repeat('x', 4000) || '"}')::text)))
                              from generate_series(1, 40) g))
                         else basis.inhalt end, repeat('a', 64)
                     from generate_series(10001, 10201) n
                     cross join (select inhalt from m_urinet.konfiguration_version order by version desc limit 1) basis
                     """);
             PreparedStatement b = verbindung.prepareStatement("""
                     insert into m_vhf_gsk.konfiguration_version (version, wer, quelle, inhalt, sha256)
                     select 20001, 'test', 'APP',
                       jsonb_set(inhalt, '{kiTexte,rolle}', to_jsonb('mandant-b'::text)), repeat('b', 64)
                     from m_vhf_gsk.konfiguration_version order by version desc limit 1
                     """)) {
            a.executeUpdate();
            b.executeUpdate();
        }

        Path zipA = temp.resolve("a.zip");
        Path zipB = temp.resolve("b.zip");
        versionenInsZip(A, zipA, temp);
        versionenInsZip(B, zipB, temp);

        try (ZipFile a = new ZipFile(zipA.toFile()); ZipFile b = new ZipFile(zipB.toFile())) {
            JsonNode versionenA = json.readTree(a.getInputStream(a.getEntry("konfiguration/versionen.json")));
            JsonNode versionenB = json.readTree(b.getInputStream(b.getEntry("konfiguration/versionen.json")));
            Set<Integer> nummernA = new HashSet<>();
            Set<Integer> nummernB = new HashSet<>();
            versionenA.forEach(version -> assertThat(nummernA.add(version.path("version").asInt())).isTrue());
            versionenB.forEach(version -> assertThat(nummernB.add(version.path("version").asInt())).isTrue());
            for (int version = 10001; version <= 10201; version++) {
                assertThat(nummernA).contains(version);
                assertThat(nummernB).doesNotContain(version);
            }
            assertThat(nummernA).doesNotContain(20001);
            assertThat(nummernB).contains(20001);
            JsonNode gross = null;
            for (JsonNode version : versionenA) {
                if (version.path("version").asInt() == 10001) {
                    gross = version.path("inhalt");
                }
            }
            assertThat(gross).isNotNull();
            assertThat(gross.path("kiTexte").path("rolle").asText()).isEqualTo("ä🙂".repeat(70000));
            assertThat(gross.toString().getBytes(StandardCharsets.UTF_8).length).isGreaterThan(131_072);
            JsonNode aktuell = json.readTree(a.getInputStream(a.getEntry("konfiguration.json")));
            assertThat(aktuell.path("arten").findValuesAsText("description"))
                    .hasSize(40).containsOnly("x".repeat(4000));
            assertThat(aktuell.toString().getBytes(StandardCharsets.UTF_8).length).isGreaterThan(131_072);
            String erwartet = THREADS.submit(() -> kontext.mit(A, () -> dateiVerwaltung.exportieren().datei())).get();
            JsonNode erwarteterStand = json.readTree(erwartet);
            for (String feld : List.of("format", "basisVersion", "einstellungen", "kiTexte",
                    "klassifikationen", "arten", "regeln")) {
                assertThat(aktuell.path(feld).equals(erwarteterStand.path(feld))).as(feld).isTrue();
            }
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (DigestInputStream inhalt = new DigestInputStream(
                    a.getInputStream(a.getEntry("konfiguration/versionen.json")), digest)) {
                inhalt.transferTo(OutputStream.nullOutputStream());
            }
            String pruefsummen = new String(a.getInputStream(a.getEntry("pruefsummen.sha256")).readAllBytes(),
                    StandardCharsets.UTF_8);
            assertThat(pruefsummen).contains(HexFormat.of().formatHex(digest.digest())
                    + "  konfiguration/versionen.json");
        }
    }

    private void versionenInsZip(MandantCode code, Path pfad, Path temp) throws Exception {
        THREADS.submit(() -> kontext.mit(code, () -> {
            MandantVollexportDaten leer = new MandantVollexportDaten() {
                @Override
                public List<Dokument> seite(Optional<UUID> nach, int anzahl) {
                    return List.of();
                }

                @Override
                public List<Manifestdatei> manifeste() {
                    return List.of();
                }
            };
            try (OutputStream datei = Files.newOutputStream(pfad)) {
                new MandantVollexportSchreiber(leer, (AblagePort)
                        java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),
                                new Class<?>[]{AblagePort.class},
                                (proxy, methode, argumente) -> { throw new AssertionError(methode.getName()); }),
                        (mandant, strom) -> {}, konfiguration).schreiben(code, UUID.randomUUID(), temp, datei);
            } catch (java.io.IOException e) {
                throw new java.io.UncheckedIOException(e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Vollexport unterbrochen", e);
            }
            return null;
        })).get();
    }

    @Test
    void statusWechseltNurEinmalVonOffenZuFertigOderFehler() throws Exception {
        UUID fertig = UUID.randomUUID();
        UUID fehler = UUID.randomUUID();
        var zip = new AbgelegteDatei("exporte/" + fertig + ".zip", "v1", "a".repeat(64), 12, Optional.empty());

        boolean[] ergebnisse = THREADS.submit(() -> kontext.mit(A, () -> new boolean[]{
                repository.anlegen(fertig), repository.anlegen(fertig),
                repository.fertig(fertig, zip, 7), repository.fertig(fertig, zip, 8),
                repository.anlegen(fehler), repository.fehler(fehler, UUID.randomUUID(),
                        MandantVollexportRepository.Fehlerart.KONFIGURATION_ZU_GROSS),
                repository.fertig(fehler, zip, 1)})).get();

        assertThat(ergebnisse).containsExactly(true, false, true, false, true, true, false);
        var geladen = THREADS.submit(() -> kontext.mit(A, () -> repository.laden(fertig))).get().orElseThrow();
        assertThat(geladen.status()).isEqualTo(MandantVollexportRepository.Status.FERTIG);
        assertThat(geladen.anzahl()).contains(7);
        assertThat(geladen.sha256()).contains("a".repeat(64));
        var fehlerstatus = THREADS.submit(() -> kontext.mit(A, () -> repository.laden(fehler))).get().orElseThrow();
        assertThat(fehlerstatus.status()).isEqualTo(MandantVollexportRepository.Status.FEHLER);
        assertThat(fehlerstatus.fehlerart()).contains(MandantVollexportRepository.Fehlerart.KONFIGURATION_ZU_GROSS);
        // der Export gehört nur dem Schema A
        assertThat(THREADS.submit(() -> kontext.mit(B, () -> repository.laden(fertig))).get()).isEmpty();
    }

    @Test
    void aktuelleDateiWirdBytegenauVorDemZipBegrenzt(@TempDir Path temp) throws Exception {
        assertThat(JdbcMandantVollexportKonfiguration.MAX_KONFIGURATION_BYTES).isEqualTo(1_048_576);
        String schema = "m_exporttest_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection verbindung = datenbank.getConnection(); var befehl = verbindung.createStatement()) {
            befehl.execute("create schema " + schema);
            try {
                befehl.execute("create table " + schema + ".konfiguration_version as "
                        + "select * from m_urinet.konfiguration_version order by version limit 1");
                befehl.execute("create table " + schema + ".ki_texte as select * from m_urinet.ki_texte");
                MandantAufloesung aufloesung = new MandantAufloesung(null, null, null) {
                    @Override
                    public String schema() {
                        return schema;
                    }
                };
                var leser = new JdbcMandantVollexportKonfiguration(datenquelle, aufloesung);
                grosseRegelEinfuegen(verbindung, schema, 100, 1);
                int basis = leser.aktuell().json().getBytes(StandardCharsets.UTF_8).length;
                int max = JdbcMandantVollexportKonfiguration.MAX_KONFIGURATION_BYTES;
                for (int delta : new int[]{-1, 0}) {
                    int version = 102 + delta;
                    grosseRegelEinfuegen(verbindung, schema, version, 1 + max + delta - basis);
                    var aktuell = leser.aktuell();
                    assertThat(aktuell.nummer()).isEqualTo(version);
                    assertThat(aktuell.json().getBytes(StandardCharsets.UTF_8)).hasSize(max + delta);
                    assertThat(json.readTree(aktuell.json()).path("regeln").get(0).path("parameter")
                            .path("wenn").path("gleich").asText()).hasSize(1 + max + delta - basis);
                }
                heapLauf(schema, temp.resolve("knapp.zip"), true);
                grosseRegelEinfuegen(verbindung, schema, 103, 1 + max + 1 - basis);
                assertThatThrownBy(leser::aktuell).isInstanceOf(MandantVollexportKonfiguration.ZuGross.class);
                ByteArrayOutputStream ziel = new ByteArrayOutputStream();
                MandantVollexportDaten leer = new MandantVollexportDaten() {
                    @Override
                    public List<Dokument> seite(Optional<UUID> nach, int anzahl) {
                        return List.of();
                    }

                    @Override
                    public List<Manifestdatei> manifeste() {
                        return List.of();
                    }
                };
                AblagePort ablage = (AblagePort) java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),
                        new Class<?>[]{AblagePort.class},
                        (proxy, methode, argumente) -> { throw new AssertionError(methode.getName()); });
                assertThatThrownBy(() -> new MandantVollexportSchreiber(leer, ablage, (mandant, strom) -> {}, leser)
                        .schreiben(A, UUID.randomUUID(), temp, ziel))
                        .isInstanceOf(MandantVollexportKonfiguration.ZuGross.class);
                assertThat(ziel.size()).isZero();
                grosseRegelEinfuegen(verbindung, schema, 104, 36 * 1024 * 1024);
                heapLauf(schema, temp.resolve("abgelehnt.zip"), false);
            } finally {
                befehl.execute("drop schema " + schema + " cascade");
            }
        }
    }

    @Test
    void aktuelleDateiNormalisiertAuchAlteAbbilderWieDerCodec() throws Exception {
        String schema = "m_exporttest_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection verbindung = datenbank.getConnection(); var befehl = verbindung.createStatement()) {
            befehl.execute("create schema " + schema);
            try {
                befehl.execute("create table " + schema + ".konfiguration_version as "
                        + "select * from m_urinet.konfiguration_version "
                        + "where jsonb_array_length(inhalt->'arten') > 0 order by version limit 1");
                befehl.execute("create table " + schema + ".ki_texte as select * from m_urinet.ki_texte");
                ObjectNode abbild;
                Konfiguration.KiTexte live;
                try (ResultSet zeile = befehl.executeQuery("select v.inhalt::text, t.rolle, t.feldregeln, "
                        + "t.ki_freigegeben from " + schema + ".konfiguration_version v cross join "
                        + schema + ".ki_texte t where t.id = 1")) {
                    assertThat(zeile.next()).isTrue();
                    abbild = (ObjectNode) json.readTree(zeile.getString(1));
                    live = new Konfiguration.KiTexte(zeile.getString(2), zeile.getString(3), zeile.getBoolean(4));
                }
                ObjectNode einstellungen = (ObjectNode) abbild.path("einstellungen");
                einstellungen.remove(List.of("branding", "chat", "webseitenChat", "wissen"));
                ObjectNode art = (ObjectNode) abbild.withArray("arten").get(0);
                art.remove(List.of("kiVorschlag", "kiFelder", "reihenfolge"));
                art.putNull("aktiv");
                art.putArray("klassifikationen").addObject().put("klassifikation", "kategorie")
                        .putNull("pflicht").put("standardWert", "ä🙂");
                ObjectNode klassifikation = abbild.putArray("klassifikationen").addObject();
                klassifikation.put("code", "kategorie").put("name", "Älter");
                klassifikation.putNull("mehrfach");
                klassifikation.putArray("werte").addObject().put("code", "beispiel").put("name", "Wert");
                for (boolean schemaAlsObjekt : List.of(false, true)) {
                    ObjectNode schemaJson = json.createObjectNode().put("type", "object");
                    schemaJson.putObject("properties").putObject("titel").put("type", "string");
                    if (schemaAlsObjekt) {
                        art.set("zusatzSchema", schemaJson);
                    } else {
                        art.put("zusatzSchema", schemaJson.toString());
                    }
                    try (PreparedStatement update = verbindung.prepareStatement(
                            "update " + schema + ".konfiguration_version set inhalt = ?::jsonb")) {
                        update.setString(1, abbild.toString());
                        update.executeUpdate();
                    }
                    ObjectNode referenzBaum = abbild.deepCopy();
                    for (JsonNode artKnoten : referenzBaum.path("arten")) {
                        ObjectNode artObjekt = (ObjectNode) artKnoten;
                        JsonNode zusatz = artObjekt.get("zusatzSchema");
                        if (zusatz != null && !zusatz.isNull() && !zusatz.isTextual()) {
                            artObjekt.put("zusatzSchema", zusatz.toString());
                        }
                    }
                    Konfiguration gelesen = json.treeToValue(referenzBaum, Konfiguration.class);
                    Konfiguration referenz = new Konfiguration(gelesen.version(), gelesen.einstellungen(),
                            gelesen.arten(), gelesen.klassifikationen(), gelesen.regeln(), live);
                    MandantAufloesung aufloesung = new MandantAufloesung(null, null, null) {
                        @Override
                        public String schema() {
                            return schema;
                        }
                    };
                    var leser = new JdbcMandantVollexportKonfiguration(datenquelle, aufloesung);
                    assertThat(json.readTree(leser.aktuell().json()))
                            .as("Zusatzschema als %s", schemaAlsObjekt ? "Objekt" : "Text")
                            .isEqualTo(json.readTree(codec.schreiben(referenz)));
                }
            } finally {
                befehl.execute("drop schema " + schema + " cascade");
            }
        }
    }

    @Test
    void groessenpruefungUndDateiBleibenBeiGleichzeitigerAenderungImSelbenStand() throws Exception {
        String schema = "m_exporttest_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection verbindung = datenbank.getConnection(); var befehl = verbindung.createStatement()) {
            befehl.execute("create schema " + schema);
            try {
                befehl.execute("create table " + schema + ".konfiguration_version as "
                        + "select * from m_urinet.konfiguration_version order by version limit 1");
                befehl.execute("create table " + schema + ".ki_texte as select * from m_urinet.ki_texte");
                AtomicBoolean geaendert = new AtomicBoolean();
                AgroalDataSource verschachtelt = (AgroalDataSource) java.lang.reflect.Proxy.newProxyInstance(
                        AgroalDataSource.class.getClassLoader(), new Class<?>[]{AgroalDataSource.class},
                        (proxy, methode, argumente) -> {
                            if (!methode.getName().equals("getConnection")) {
                                throw new AssertionError(methode.getName());
                            }
                            Connection echt = datenbank.getConnection();
                            return java.lang.reflect.Proxy.newProxyInstance(Connection.class.getClassLoader(),
                                    new Class<?>[]{Connection.class}, (p, m, a) -> {
                                        Object ergebnis = aufrufen(echt, m, a);
                                        if (!m.getName().equals("prepareStatement")) {
                                            return ergebnis;
                                        }
                                        PreparedStatement vorbereitet = (PreparedStatement) ergebnis;
                                        return java.lang.reflect.Proxy.newProxyInstance(
                                                PreparedStatement.class.getClassLoader(),
                                                new Class<?>[]{PreparedStatement.class}, (p2, m2, a2) -> {
                                                    Object antwort = aufrufen(vorbereitet, m2, a2);
                                                    if (!m2.getName().equals("executeQuery")) {
                                                        return antwort;
                                                    }
                                                    ResultSet zeile = (ResultSet) antwort;
                                                    return java.lang.reflect.Proxy.newProxyInstance(
                                                            ResultSet.class.getClassLoader(),
                                                            new Class<?>[]{ResultSet.class}, (p3, m3, a3) -> {
                                                                Object wert = aufrufen(zeile, m3, a3);
                                                                if (m3.getName().equals("getLong")
                                                                        && geaendert.compareAndSet(false, true)) {
                                                                    grosseRegelEinfuegen(verbindung, schema, 100,
                                                                            1_048_576);
                                                                }
                                                                return wert;
                                                            });
                                                });
                                    });
                        });
                @SuppressWarnings("unchecked")
                Instance<AgroalDataSource> quelle = (Instance<AgroalDataSource>) java.lang.reflect.Proxy
                        .newProxyInstance(Instance.class.getClassLoader(), new Class<?>[]{Instance.class},
                                (proxy, methode, argumente) -> {
                                    if (methode.getName().equals("get")) {
                                        return verschachtelt;
                                    }
                                    throw new AssertionError(methode.getName());
                                });
                MandantAufloesung aufloesung = new MandantAufloesung(null, null, null) {
                    @Override
                    public String schema() {
                        return schema;
                    }
                };
                var leser = new JdbcMandantVollexportKonfiguration(quelle, aufloesung);
                var ersterStand = leser.aktuell();
                assertThat(geaendert).isTrue();
                assertThat(ersterStand.nummer()).isLessThan(100);
                assertThat(ersterStand.json().getBytes(StandardCharsets.UTF_8)).hasSizeLessThanOrEqualTo(1_048_576);
                assertThatThrownBy(leser::aktuell).isInstanceOf(MandantVollexportKonfiguration.ZuGross.class);
            } finally {
                befehl.execute("drop schema " + schema + " cascade");
            }
        }
    }

    private static Object aufrufen(Object ziel, java.lang.reflect.Method methode, Object[] argumente) throws Throwable {
        try {
            return methode.invoke(ziel, argumente);
        } catch (java.lang.reflect.InvocationTargetException e) {
            throw e.getCause();
        }
    }


    private void heapLauf(String schema, Path zip, boolean erlaubt) throws Exception {
        var factory = datenbank.getConfiguration().connectionPoolConfiguration().connectionFactoryConfiguration();
        String passwort = factory.credentials().stream().filter(SimplePassword.class::isInstance)
                .map(SimplePassword.class::cast).map(SimplePassword::getWord).findFirst().orElseThrow();
        String klassenpfad = System.getProperty("surefire.test.class.path");
        if (klassenpfad == null || klassenpfad.isBlank()) {
            throw new IllegalStateException("Test-Klassenpfad für JDBC-Heaptest fehlt");
        }
        Process prozess = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-XX:+UseSerialGC", "-Xmx32m", "-XX:+ExitOnOutOfMemoryError", "-cp", klassenpfad,
                JdbcHeapLauf.class.getName()).redirectErrorStream(true).start();
        try (PrintWriter eingabe = new PrintWriter(prozess.getOutputStream(), true, StandardCharsets.UTF_8)) {
            eingabe.println(factory.jdbcUrl());
            eingabe.println(factory.principal().getName());
            eingabe.println(passwort);
            eingabe.println(schema);
            eingabe.println(zip);
            eingabe.println(erlaubt);
        }
        boolean beendet = prozess.waitFor(3, TimeUnit.MINUTES);
        if (!beendet) {
            prozess.destroyForcibly();
        }
        assertThat(beendet).as("JDBC-Heaptest beendet").isTrue();
        String ausgabe = new String(prozess.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(prozess.exitValue()).as(ausgabe).isZero();
        assertThat(ausgabe).contains("Heaptest OK");
    }

    /** Eigene JVM: nur Testzugang und synthetischer Mandant, kein Quarkus-Start im 32-MiB-Heap. */
    public static final class JdbcHeapLauf {
        public static void main(String[] args) throws Exception {
            try (BufferedReader eingabe = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
                String url = eingabe.readLine();
                String benutzer = eingabe.readLine();
                String passwort = eingabe.readLine();
                String schema = eingabe.readLine();
                Path zip = Path.of(eingabe.readLine());
                boolean erlaubt = Boolean.parseBoolean(eingabe.readLine());
                AgroalDataSource datenquelle = (AgroalDataSource) java.lang.reflect.Proxy.newProxyInstance(
                        AgroalDataSource.class.getClassLoader(), new Class<?>[]{AgroalDataSource.class},
                        (proxy, methode, argumente) -> {
                            if (methode.getName().equals("getConnection")) {
                                return DriverManager.getConnection(url, benutzer, passwort);
                            }
                            throw new AssertionError(methode.getName());
                        });
                @SuppressWarnings("unchecked")
                Instance<AgroalDataSource> quelle = (Instance<AgroalDataSource>) java.lang.reflect.Proxy
                        .newProxyInstance(Instance.class.getClassLoader(), new Class<?>[]{Instance.class},
                                (proxy, methode, argumente) -> {
                                    if (methode.getName().equals("get")) {
                                        return datenquelle;
                                    }
                                    throw new AssertionError(methode.getName());
                                });
                MandantAufloesung aufloesung = new MandantAufloesung(null, null, null) {
                    @Override
                    public String schema() {
                        return schema;
                    }
                };
                var leser = new JdbcMandantVollexportKonfiguration(quelle, aufloesung);
                MandantVollexportDaten daten = new MandantVollexportDaten() {
                    @Override
                    public List<Dokument> seite(Optional<UUID> nach, int anzahl) {
                        return List.of();
                    }

                    @Override
                    public List<Manifestdatei> manifeste() {
                        return List.of();
                    }
                };
                AblagePort ablage = (AblagePort) java.lang.reflect.Proxy.newProxyInstance(
                        AblagePort.class.getClassLoader(), new Class<?>[]{AblagePort.class},
                        (proxy, methode, argumente) -> { throw new AssertionError(methode.getName()); });
                try (OutputStream ziel = Files.newOutputStream(zip)) {
                    try {
                        new MandantVollexportSchreiber(daten, ablage, (mandant, strom) -> {}, leser)
                                .schreiben(new MandantCode("urinet"), UUID.randomUUID(), zip.getParent(), ziel);
                        if (!erlaubt) {
                            throw new AssertionError("Zu grosse Konfiguration wurde akzeptiert");
                        }
                    } catch (MandantVollexportKonfiguration.ZuGross e) {
                        if (erlaubt) {
                            throw e;
                        }
                    }
                }
                if (erlaubt) {
                    try (ZipFile datei = new ZipFile(zip.toFile())) {
                        if (datei.getEntry("konfiguration.json").getSize() != 1_048_576) {
                            throw new AssertionError("Aktuelle Konfigurationsdatei unvollständig");
                        }
                    }
                } else if (Files.size(zip) != 0) {
                    throw new AssertionError("Teil-ZIP geschrieben");
                }
                System.out.println("Heaptest OK");
            }
        }
    }

    private static void grosseRegelEinfuegen(Connection verbindung, String schema, int version, int zeichen)
            throws SQLException {
        try (PreparedStatement befehl = verbindung.prepareStatement("insert into " + schema
                + ".konfiguration_version (version, wer, quelle, inhalt, sha256) "
                + "select ?, 'test', 'APP', jsonb_set(inhalt, '{regeln}', jsonb_build_array(jsonb_build_object("
                + "'code', 'TEST', 'art', 'BELEG', 'typ', 'PFLICHT_WENN', 'parameter', "
                + "jsonb_build_object('wenn', jsonb_build_object('gleich', repeat('x', ?))), "
                + "'schwere', 'WARNUNG', 'meldung', 'test', 'aktiv', true))), repeat('a', 64) "
                + "from " + schema + ".konfiguration_version order by version limit 1")) {
            befehl.setInt(1, version);
            befehl.setInt(2, zeichen);
            befehl.executeUpdate();
        }
    }

    @Test
    void bestandZaehltNurDasEigeneSchemaUndMeldetJuengsteAenderung() throws Exception {
        dokumenteEinfuegen("m_vhf_gsk", 2);
        var vorher = THREADS.submit(() -> kontext.mit(B, () -> repository.bestand())).get();
        var aVorher = THREADS.submit(() -> kontext.mit(A, () -> repository.bestand())).get();

        dokumenteEinfuegen("m_vhf_gsk", 1);
        var nachher = THREADS.submit(() -> kontext.mit(B, () -> repository.bestand())).get();
        var a = THREADS.submit(() -> kontext.mit(A, () -> repository.bestand())).get();

        assertThat(nachher.dokumente()).isEqualTo(vorher.dokumente() + 1);
        assertThat(nachher.letzteAenderung()).isPresent();
        assertThat(nachher.letzteAenderung().get()).isAfterOrEqualTo(vorher.letzteAenderung().orElseThrow());
        assertThat(a.dokumente()).isEqualTo(aVorher.dokumente());
    }

    @Test
    void bestandHebtLetzteAenderungBeiNeuerDateiOhneDokumentUpdate() throws Exception {
        Set<UUID> ids = dokumenteEinfuegen("m_vhf_gsk", 1);
        UUID id = ids.iterator().next();
        var vorher = THREADS.submit(() -> kontext.mit(B, () -> repository.bestand())).get();
        try (Connection verbindung = datenbank.getConnection();
             PreparedStatement befehl = verbindung.prepareStatement("insert into m_vhf_gsk.dokument_datei "
                     + "(dokument_id, nr, rolle, sha256, empfangen) values (?, 1, 'ORIGINAL', ?, "
                     + "clock_timestamp() + interval '1 hour')")) {
            befehl.setObject(1, id);
            befehl.setString(2, "a".repeat(64));
            befehl.executeUpdate();
        }

        var nachher = THREADS.submit(() -> kontext.mit(B, () -> repository.bestand())).get();

        assertThat(nachher.dokumente()).isEqualTo(vorher.dokumente());
        assertThat(nachher.letzteAenderung().orElseThrow()).isAfter(vorher.letzteAenderung().orElseThrow());
    }

    private List<UUID> alleIds(MandantCode code) throws Exception {
        return THREADS.submit(() -> kontext.mit(code, () -> {
            List<UUID> ids = new ArrayList<>();
            Optional<UUID> nach = Optional.empty();
            while (true) {
                var seite = daten.seite(nach, 200);
                seite.forEach(d -> ids.add(d.id()));
                if (seite.size() < 200) {
                    return ids;
                }
                nach = Optional.of(seite.getLast().id());
            }
        })).get();
    }

    private Set<UUID> dokumenteEinfuegen(String schema, int anzahl) throws SQLException {
        Set<UUID> ids = new HashSet<>();
        try (Connection verbindung = datenbank.getConnection();
             PreparedStatement befehl = verbindung.prepareStatement("insert into " + schema
                     + ".dokument (id, quelle, erstellt_von, status) values (?, 'UPLOAD', 'test', 'NEU')")) {
            for (int i = 0; i < anzahl; i++) {
                UUID id = UUID.randomUUID();
                ids.add(id);
                befehl.setObject(1, id);
                befehl.addBatch();
            }
            befehl.executeBatch();
        }
        return ids;
    }

    private boolean imRegister(String code) throws SQLException {
        try (Connection verbindung = datenbank.getConnection(); PreparedStatement abfrage =
                verbindung.prepareStatement("select 1 from public.mandant where code = ?")) {
            abfrage.setString(1, code);
            try (ResultSet zeile = abfrage.executeQuery()) {
                return zeile.next();
            }
        }
    }
}
