package ch.urinet.archiv.sicherung;

import ch.urinet.archiv.ablage.adapter.out.AblageEinstellungen;
import ch.urinet.archiv.ablage.adapter.out.s3.S3Ablage;
import ch.urinet.archiv.ablage.domain.ManifestTeil;
import ch.urinet.archiv.ablage.domain.Pruefsumme;
import ch.urinet.archiv.mandant.application.MandantProvisionierung;
import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.protokoll.adapter.in.kommando.ManifestKommando;
import ch.urinet.archiv.protokoll.adapter.out.json.JacksonJsonLeser;
import ch.urinet.archiv.protokoll.application.FestgehalteneManifeste;
import ch.urinet.archiv.protokoll.application.ManifestPruefen;
import ch.urinet.archiv.protokoll.application.ManifestSchluessel;
import ch.urinet.archiv.protokoll.application.ZeitstempelPort;
import ch.urinet.archiv.protokoll.domain.ManifestFenster;
import ch.urinet.archiv.protokoll.domain.ManifestInhalt;
import ch.urinet.archiv.protokoll.domain.ManifestSignatur;
import ch.urinet.archiv.protokoll.domain.Pruefdaten;
import ch.urinet.archiv.protokoll.domain.Stempel;
import ch.urinet.archiv.sicherung.adapter.in.kommando.SicherungKommando;
import io.agroal.api.AgroalDataSource;
import io.quarkus.arc.InjectableInstance;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.BucketVersioningStatus;
import software.amazon.awssdk.services.s3.model.ObjectLockMode;

import jakarta.inject.Inject;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/** Lokaler Export-/Datenbank-/Bucket-Roundtrip (Req 16.3, Teilnachweis Spec 04 Req 4.7). */
@QuarkusTest
@TestProfile(SicherungPaketRoundtripTest.ExternesRestoreProfil.class)
@Tag("start-2")
@EnabledIfSystemProperty(named = "archiv.restore.e2e", matches = "true")
class SicherungPaketRoundtripTest {

    private static final String QUELL_PRAEFIX = "urinet-archiv-a-";
    private static final String ZIEL_PRAEFIX = "urinet-archiv-p-";
    private static final List<String> MANDANTEN = List.of("urinet", "vhf-gsk");
    private static final String MANIFEST = "manifest/2026-01-01.json";
    private static final LocalDate MANIFEST_DATUM = LocalDate.of(2026, 1, 1);
    private static final byte[] ORIGINAL = ("%PDF-1.4\n% synthetisches Original\n"
            + "1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n"
            + "2 0 obj\n<< /Type /Pages /Kids [] /Count 0 >>\nendobj\n%%EOF\n")
            .getBytes(StandardCharsets.US_ASCII);
    private static final byte[] PDFA = ("%PDF-1.4\n% synthetische PDF/A-Arbeitsfassung\n"
            + "1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n"
            + "2 0 obj\n<< /Type /Pages /Kids [] /Count 0 >>\nendobj\n%%EOF\n")
            .getBytes(StandardCharsets.US_ASCII);

    @Inject
    S3Client s3;

    @Inject
    AblageEinstellungen einstellungen;

    @Inject
    MandantProvisionierung provisionierung;

    @Inject
    SicherungKommando sicherung;

    @Inject
    AgroalDataSource datenquelle;

    @Inject
    InjectableInstance<AgroalDataSource> datenquelleInstanz;

    @Inject
    ch.urinet.archiv.mandant.application.MandantRegister register;

    @Inject
    FestgehalteneManifeste festgehalteneManifeste;

    @TestHTTPResource("/q/health/ready")
    URI readiness;

    @Test
    void quelleVorbereiten() throws Exception {
        Assumptions.assumeTrue("quelle".equals(System.getProperty("archiv.restore.phase")));

        Properties zustand = new Properties();
        for (String code : MANDANTEN) {
            MandantCode mandantCode = new MandantCode(code);
            var mandant = provisionierung.anlegen(code, null, null);
            assertThat(mandant.bucket()).isEqualTo(mandantCode.bucket(QUELL_PRAEFIX));

            UUID id = UUID.randomUUID();
            String originalKey = "archiv/2026/" + id + "/original.pdf";
            String pdfaKey = "archiv/2026/" + id + "/pdfa.pdf";
            String originalVersion = ablegen(mandant.bucket(), originalKey, ORIGINAL);
            String pdfaVersion = ablegen(mandant.bucket(), pdfaKey, PDFA);
            dokumentAnlegen(mandant.schema(), id, pdfaKey, pdfaVersion, originalKey, originalVersion);
            wissenAnlegen(mandant.schema(), id);
            restoreNeueDatenAnlegen(mandant.schema());
            restoreNeueDatenPruefen(mandant.schema());
            long[] wissen = wissenZaehlen(mandant.schema());
            assertThat(wissen).containsExactly(2, 3, 2);

            KeyPair schluessel = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
            ManifestTestdaten manifest = manifestErzeugen(schluessel, code);
            Map<String, String> versionen = new HashMap<>();
            for (var teil : manifest.teile().entrySet()) {
                versionen.put(teil.getKey(), ablegenGesperrt(mandant.bucket(), teil.getKey(), teil.getValue()));
            }
            manifestFesthalten(mandant.schema(), manifest, versionen);
            String prefix = code + ".";
            zustand.setProperty(prefix + "wissen.seiten", Long.toString(wissen[0]));
            zustand.setProperty(prefix + "wissen.versionen", Long.toString(wissen[1]));
            zustand.setProperty(prefix + "wissen.quellen", Long.toString(wissen[2]));
            long dokumente = dokumenteZaehlen(mandant.schema());
            assertThat(dokumente).isEqualTo(1);
            zustand.setProperty(prefix + "dokument.anzahl", Long.toString(dokumente));
            zustand.setProperty(prefix + "dokument.id", id.toString());
            zustand.setProperty(prefix + "dokument.original.schluessel", originalKey);
            zustand.setProperty(prefix + "dokument.original.sha256", Pruefsumme.von(ORIGINAL));
            zustand.setProperty(prefix + "dokument.original.inhalt", Base64.getEncoder().encodeToString(ORIGINAL));
            zustand.setProperty(prefix + "dokument.pdfa.schluessel", pdfaKey);
            zustand.setProperty(prefix + "dokument.pdfa.sha256", Pruefsumme.von(PDFA));
            zustand.setProperty(prefix + "dokument.pdfa.inhalt", Base64.getEncoder().encodeToString(PDFA));
            zustand.setProperty(prefix + "manifest.version", versionen.get(MANIFEST));
            zustand.setProperty(prefix + "manifest.private",
                    Base64.getEncoder().encodeToString(schluessel.getPrivate().getEncoded()));
            zustand.setProperty(prefix + "manifest.public",
                    Base64.getEncoder().encodeToString(schluessel.getPublic().getEncoded()));
        }
        zustandSpeichern(zustand);
    }

    @Test
    void wiederhergestellteAnwendungUndManifestPruefen() throws Exception {
        Assumptions.assumeTrue("ziel".equals(System.getProperty("archiv.restore.phase")));

        for (String code : MANDANTEN) {
            wiederhergestelltenMandantenPruefen(code);
        }
    }

    @Test
    void wiederhergestellteOidcAnmeldungPruefen() throws Exception {
        Assumptions.assumeTrue("ziel".equals(System.getProperty("archiv.restore.phase")));

        String oidcUrl = erforderlicheEigenschaft("archiv.restore.oidc-url");
        String client = erforderlicheEigenschaft("archiv.restore.oidc-client");
        String secretDatei = erforderlicheEigenschaft("archiv.restore.oidc-client-secret-datei");
        String secret = Files.readString(Path.of(secretDatei), StandardCharsets.UTF_8).trim();
        var anmeldung = given().formParam("grant_type", "client_credentials")
                .formParam("client_id", client)
                .formParam("client_secret", secret)
                .when().post(oidcUrl + "/protocol/openid-connect/token");
        assertThat(anmeldung.statusCode()).as("Anmeldung am wiederhergestellten Realm: " + anmeldung.asString())
                .isEqualTo(200);
        String token = anmeldung.path("access_token");
        assertThat(token).isNotBlank();
        given().auth().oauth2(token).when().get("/api/v1/dokumentarten").then().statusCode(200);
    }

    private static String erforderlicheEigenschaft(String name) {
        String wert = System.getProperty(name);
        if (wert == null || wert.isBlank()) {
            throw new IllegalStateException("Erforderliche Restore-Testeigenschaft fehlt: " + name);
        }
        return wert;
    }

    private void wiederhergestelltenMandantenPruefen(String code) throws Exception {
        MandantCode mandant = new MandantCode(code);
        String schema = mandant.schema();
        String zielBucket = mandant.bucket(ZIEL_PRAEFIX);
        s3.createBucket(b -> b.bucket(zielBucket).objectLockEnabledForBucket(true));
        s3.putBucketVersioning(b -> b.bucket(zielBucket)
                .versioningConfiguration(v -> v.status(BucketVersioningStatus.ENABLED)));

        Path inventar = restoreDatei("inventar", code);
        Path bucket = restoreDatei("bucket", code);
        List<String> inventarzeilen = Files.readAllLines(inventar, StandardCharsets.UTF_8);
        List<String> ohneManifest = inventarzeilen.stream()
                .filter(zeile -> !zeile.contains("\"schluessel\":\"manifest/")).toList();
        assertThat(ohneManifest).hasSizeLessThan(inventarzeilen.size());
        Path inventarOhneManifest = Files.createTempFile("restore-ohne-manifest-", ".jsonl");
        try {
            Files.write(inventarOhneManifest, ohneManifest, StandardCharsets.UTF_8);
            int fehlendesManifestExit = ohneAnfrage(() -> sicherung.ausfuehren(List.of("wiederherstellen",
                    "--mandant", code, "--inventar", inventarOhneManifest.toString(), "--strom", bucket.toString(),
                    "--kappen"),
                    new PrintStream(new ByteArrayOutputStream()), new PrintStream(new ByteArrayOutputStream())));
            assertThat(fehlendesManifestExit).isEqualTo(4);
        } finally {
            Files.deleteIfExists(inventarOhneManifest);
        }

        var restoreAusgabe = new ByteArrayOutputStream();
        var restoreFehler = new ByteArrayOutputStream();
        int restoreExit = ohneAnfrage(() -> sicherung.ausfuehren(List.of("wiederherstellen", "--mandant", code,
                "--inventar", inventar.toString(), "--strom", bucket.toString(), "--kappen"),
                new PrintStream(restoreAusgabe), new PrintStream(restoreFehler)));
        assertThat(restoreExit).as(() -> restoreFehler.toString(StandardCharsets.UTF_8)
                + restoreAusgabe.toString(StandardCharsets.UTF_8)).isZero();
        assertThat(restoreFehler.toString(StandardCharsets.UTF_8)).isEmpty();

        HttpResponse<Void> health = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(readiness).GET().build(), HttpResponse.BodyHandlers.discarding());
        assertThat(health.statusCode()).isEqualTo(200);

        Properties erwartet = zustandLaden();
        String prefix = code + ".";
        assertThat(wissenZaehlen(schema)).containsExactly(
                Long.parseLong(erwartet.getProperty(prefix + "wissen.seiten")),
                Long.parseLong(erwartet.getProperty(prefix + "wissen.versionen")),
                Long.parseLong(erwartet.getProperty(prefix + "wissen.quellen")));
        restoreNeueDatenPruefen(schema);
        assertThat(dokumenteZaehlen(schema)).isEqualTo(
                Long.parseLong(erwartet.getProperty(prefix + "dokument.anzahl")));
        UUID id = UUID.fromString(erwartet.getProperty(prefix + "dokument.id"));
        String fremderMandant = MANDANTEN.stream().filter(anderer -> !anderer.equals(code)).findFirst().orElseThrow();
        UUID fremdeId = UUID.fromString(erwartet.getProperty(fremderMandant + ".dokument.id"));
        assertThat(fremdeId).isNotEqualTo(id);
        for (String datei : List.of("original", "pdfa")) {
            String fremderSchluessel = erwartet.getProperty(fremderMandant + ".dokument." + datei + ".schluessel");
            var fremdeObjekte = s3.listObjectVersions(r -> r.bucket(zielBucket).prefix(fremderSchluessel));
            assertThat(fremdeObjekte.versions()).as("Fremde %s-Versionen in %s", datei, zielBucket).isEmpty();
            assertThat(fremdeObjekte.deleteMarkers()).as("Fremde %s-Löschmarken in %s", datei, zielBucket).isEmpty();
        }
        try (Connection connection = datenquelle.getConnection()) {
            try (PreparedStatement p = connection.prepareStatement(
                    "select 1 from " + schema + ".dokument where id = ?")) {
                p.setObject(1, fremdeId);
                try (ResultSet r = p.executeQuery()) {
                    assertThat(r.next()).isFalse();
                }
            }
            try (PreparedStatement p = connection.prepareStatement("select d.sha256_pdfa, d.s3_pdfa, "
                    + "d.s3_version_pdfa, f.sha256, f.s3_schluessel, f.s3_version "
                    + "from " + schema + ".dokument d join " + schema + ".dokument_datei f on f.dokument_id = d.id "
                    + "where d.id = ?")) {
                p.setObject(1, id);
                try (ResultSet r = p.executeQuery()) {
                    assertThat(r.next()).isTrue();
                    assertThat(r.getString(1)).isEqualTo(erwartet.getProperty(prefix + "dokument.pdfa.sha256"));
                    assertThat(r.getString(2)).isEqualTo(erwartet.getProperty(prefix + "dokument.pdfa.schluessel"));
                    assertThat(r.getString(4)).isEqualTo(erwartet.getProperty(prefix + "dokument.original.sha256"));
                    assertThat(r.getString(5)).isEqualTo(erwartet.getProperty(prefix + "dokument.original.schluessel"));
                    pruefeDatei(code, r.getString(2), r.getString(3),
                            erwartet.getProperty(prefix + "dokument.pdfa.inhalt"));
                    pruefeDatei(code, r.getString(5), r.getString(6),
                            erwartet.getProperty(prefix + "dokument.original.inhalt"));
                }
            }
            // manifest ist nur Einfügen: Die Zeile behält die Versions-ID der Quelle, geprüft wird über die Hashes.
            try (PreparedStatement p = connection.prepareStatement(
                    "select sha256, s3_version from " + schema + ".manifest where datum = ?")) {
                p.setObject(1, MANIFEST_DATUM);
                try (ResultSet r = p.executeQuery()) {
                    assertThat(r.next()).isTrue();
                    assertThat(r.getString(1)).isEqualTo(Pruefsumme.von(manifestErzeugen(
                            schluesselLesen(erwartet, code), code).inhalt()));
                    assertThat(r.getString(2)).isEqualTo(erwartet.getProperty(prefix + "manifest.version"));
                }
            }
        }

        var zielAblage = new S3Ablage(s3, S3Ablage.presigner(s3, true), einstellungen, Clock.systemUTC());
        var manifestImZiel = zielAblage.lesenManifest(mandant, MANIFEST_DATUM,
                ManifestTeil.JSON).orElseThrow();
        assertThat(manifestImZiel.datei().versionId()).isNotEqualTo(erwartet.getProperty(prefix + "manifest.version"));
        assertThat(manifestImZiel.inhalt()).isEqualTo(manifestErzeugen(schluesselLesen(erwartet, code), code).inhalt());

        var schluessel = schluesselLesen(erwartet, code);
        var pruefer = new ManifestPruefen(zielAblage, new JacksonJsonLeser(), manifestSchluessel(schluessel),
                new SynthetischerZeitstempel(), festgehalteneManifeste, Duration.ofHours(24));
        var kommando = new ManifestKommando(pruefer, register, datenquelleInstanz);
        var ausgabe = new ByteArrayOutputStream();
        var fehler = new ByteArrayOutputStream();
        int exit = ohneAnfrage(() -> kommando.ausfuehren(List.of("pruefen", MANIFEST_DATUM.toString(), "--mandant",
                mandant.wert()),
                new PrintStream(ausgabe), new PrintStream(fehler)));
        assertThat(fehler.toString(StandardCharsets.UTF_8)).isEmpty();
        assertThat(ausgabe.toString(StandardCharsets.UTF_8)).contains("gegen die Datenbank")
                .contains("Ergebnis: gültig").doesNotContain("nicht festgehalten");
        assertThat(exit).isZero();

        // Negativprobe: Eine weitere, veränderte Fassung des Manifests im Ziel-Bucket muss die Prüfung scheitern lassen.
        byte[] veraendert = Arrays.copyOf(manifestImZiel.inhalt(), manifestImZiel.inhalt().length + 1);
        veraendert[veraendert.length - 1] = ' ';
        s3.putObject(r -> r.bucket(zielBucket).key(MANIFEST), RequestBody.fromBytes(veraendert));
        var negativAusgabe = new ByteArrayOutputStream();
        int negativExit = ohneAnfrage(() -> kommando.ausfuehren(List.of("pruefen", MANIFEST_DATUM.toString(), "--mandant",
                mandant.wert()), new PrintStream(negativAusgabe), new PrintStream(new ByteArrayOutputStream())));
        assertThat(negativExit).isNotZero();
        assertThat(negativAusgabe.toString(StandardCharsets.UTF_8)).doesNotContain("Ergebnis: gültig");
    }

    private static Path restoreDatei(String art, String mandant) {
        String endung = art.equals("inventar") ? ".jsonl" : ".bin";
        return Path.of(System.getProperty("archiv.restore.verzeichnis"), art + "-" + mandant + endung);
    }

    // Wie im Kommando-Betrieb ohne Request-Kontext; MandantKontext verlangt sonst eine Anmeldung.
    private static <T> T ohneAnfrage(Callable<T> arbeit) throws Exception {
        try (var thread = Executors.newSingleThreadExecutor()) {
            return thread.submit(arbeit).get();
        }
    }

    private String ablegen(String bucket, String key, byte[] bytes) {
        return s3.putObject(r -> r.bucket(bucket).key(key), RequestBody.fromBytes(bytes)).versionId();
    }

    private String ablegenGesperrt(String bucket, String key, byte[] bytes) {
        return s3.putObject(r -> r.bucket(bucket).key(key).objectLockMode(ObjectLockMode.GOVERNANCE)
                        .objectLockRetainUntilDate(Instant.now().plus(Duration.ofDays(2))),
                RequestBody.fromBytes(bytes)).versionId();
    }

    private void dokumentAnlegen(String schema, UUID id, String pdfaKey, String pdfaVersion,
                                 String originalKey, String originalVersion) throws Exception {
        try (Connection connection = datenquelle.getConnection()) {
            try (PreparedStatement p = connection.prepareStatement("insert into " + schema
                    + ".dokument (id, quelle, erstellt_von, status, s3_pdfa, s3_version_pdfa, sha256_pdfa) "
                    + "values (?, 'UPLOAD', 'restore-roundtrip-test', 'ARCHIVIERT', ?, ?, ?)")) {
                p.setObject(1, id);
                p.setString(2, pdfaKey);
                p.setString(3, pdfaVersion);
                p.setString(4, Pruefsumme.von(PDFA));
                p.executeUpdate();
            }
            try (PreparedStatement p = connection.prepareStatement("insert into " + schema
                    + ".dokument_datei (dokument_id, nr, rolle, sha256, s3_schluessel, s3_version) "
                    + "values (?, 1, 'ORIGINAL', ?, ?, ?)")) {
                p.setObject(1, id);
                p.setString(2, Pruefsumme.von(ORIGINAL));
                p.setString(3, originalKey);
                p.setString(4, originalVersion);
                p.executeUpdate();
            }
        }
    }

    private void wissenAnlegen(String schema, UUID dokumentId) throws Exception {
        try (Connection connection = datenquelle.getConnection()) {
            for (int nr = 1; nr <= 2; nr++) {
                UUID seite = UUID.randomUUID();
                try (PreparedStatement p = connection.prepareStatement("insert into " + schema
                        + ".wissensseite (id, pfad, typ, titel, beschreibung, vertraulichkeit, inhalt, version, "
                        + "erzeugt_von, erzeugt_am, freigegeben_von, freigegeben_am, fuer_webseite, "
                        + "fuer_webseite_von, fuer_webseite_am) "
                        + "values (?, ?, 'Konzept', 'Synthetische Probe', 'Probe', 'NORMAL', 'Testinhalt', ?, "
                        + "'human:probe', '2030-01-01T00:00:00Z', 'human:probe', '2030-01-01T00:00:00Z', ?, ?, ?)")) {
                    p.setObject(1, seite);
                    p.setString(2, "restore/" + seite);
                    p.setInt(3, nr);
                    boolean fuerWebseite = nr == 1;
                    p.setBoolean(4, fuerWebseite);
                    p.setString(5, fuerWebseite ? "human:restore-roundtrip" : null);
                    p.setObject(6, fuerWebseite
                            ? OffsetDateTime.parse("2030-01-02T03:04:05Z") : null);
                    p.executeUpdate();
                }
                for (int version = 1; version <= nr; version++) {
                    try (PreparedStatement p = connection.prepareStatement("insert into " + schema
                            + ".wissensseite_version (seite_id, version, daten, inhalt, erzeugt_von, "
                            + "freigegeben_von, zeit) values (?, ?, '{}', 'Testinhalt', 'human:probe', "
                            + "'human:probe', '2030-01-01T00:00:00Z')")) {
                        p.setObject(1, seite);
                        p.setInt(2, version);
                        p.executeUpdate();
                    }
                }
                try (PreparedStatement p = connection.prepareStatement("insert into " + schema
                        + ".wissensseite_quelle (seite_id, schluessel, dokument_id, seite_von) values (?, ?, ?, 1)")) {
                    p.setObject(1, seite);
                    p.setString(2, "q-" + nr);
                    p.setObject(3, dokumentId);
                    p.executeUpdate();
                }
                try (PreparedStatement p = connection.prepareStatement("insert into " + schema
                        + ".wissensseite_dokument (seite_id, dokument_id) values (?, ?)")) {
                    p.setObject(1, seite);
                    p.setObject(2, dokumentId);
                    p.executeUpdate();
                }
            }
        }
    }

    private void restoreNeueDatenAnlegen(String schema) throws Exception {
        try (Connection connection = datenquelle.getConnection()) {
            try (PreparedStatement p = connection.prepareStatement("insert into " + schema
                    + ".oeffentliche_frage (frage, quellen, tokens, zeit) "
                    + "values (?, cast(? as jsonb), ?, ?)")) {
                p.setString(1, "Synthetische Restore-Frage");
                p.setString(2, "[]");
                p.setInt(3, 7);
                p.setObject(4, OffsetDateTime.parse("2030-01-03T04:05:06Z"));
                p.executeUpdate();
            }
            try (PreparedStatement p = connection.prepareStatement("insert into " + schema
                    + ".benutzer_einladung (benutzer, versendet_am, gueltig_bis) values (?, ?, ?)")) {
                p.setString(1, "restore-roundtrip-benutzer");
                p.setObject(2, OffsetDateTime.parse("2030-01-01T00:00:00Z"));
                p.setObject(3, OffsetDateTime.parse("2030-01-08T00:00:00Z"));
                p.executeUpdate();
            }
        }
    }

    private void restoreNeueDatenPruefen(String schema) throws Exception {
        try (Connection connection = datenquelle.getConnection();
             PreparedStatement p = connection.prepareStatement("select fuer_webseite, fuer_webseite_von, "
                     + "fuer_webseite_am from " + schema + ".wissensseite order by fuer_webseite desc");
             ResultSet r = p.executeQuery()) {
            assertThat(r.next()).isTrue();
            assertThat(r.getBoolean(1)).isTrue();
            assertThat(r.getString(2)).isEqualTo("human:restore-roundtrip");
            assertThat(r.getTimestamp(3).toInstant()).isEqualTo(Instant.parse("2030-01-02T03:04:05Z"));
            assertThat(r.next()).isTrue();
            assertThat(r.getBoolean(1)).isFalse();
            assertThat(r.getString(2)).isNull();
            assertThat(r.getTimestamp(3)).isNull();
            assertThat(r.next()).isFalse();
        }
        try (Connection connection = datenquelle.getConnection();
             PreparedStatement p = connection.prepareStatement("select frage, quellen::text, tokens, zeit from "
                     + schema + ".oeffentliche_frage");
             ResultSet r = p.executeQuery()) {
            assertThat(r.next()).isTrue();
            assertThat(r.getString(1)).isEqualTo("Synthetische Restore-Frage");
            assertThat(r.getString(2)).isEqualTo("[]");
            assertThat(r.getInt(3)).isEqualTo(7);
            assertThat(r.getTimestamp(4).toInstant()).isEqualTo(Instant.parse("2030-01-03T04:05:06Z"));
            assertThat(r.next()).isFalse();
        }
        try (Connection connection = datenquelle.getConnection();
             PreparedStatement p = connection.prepareStatement("select benutzer, versendet_am, gueltig_bis from "
                     + schema + ".benutzer_einladung");
             ResultSet r = p.executeQuery()) {
            assertThat(r.next()).isTrue();
            assertThat(r.getString(1)).isEqualTo("restore-roundtrip-benutzer");
            assertThat(r.getTimestamp(2).toInstant()).isEqualTo(Instant.parse("2030-01-01T00:00:00Z"));
            assertThat(r.getTimestamp(3).toInstant()).isEqualTo(Instant.parse("2030-01-08T00:00:00Z"));
            assertThat(r.next()).isFalse();
        }
    }

    private long[] wissenZaehlen(String schema) throws Exception {
        try (Connection connection = datenquelle.getConnection();
             PreparedStatement p = connection.prepareStatement("select (select count(*) from " + schema
                     + ".wissensseite), (select count(*) from " + schema + ".wissensseite_version), "
                     + "(select count(*) from " + schema + ".wissensseite_quelle)");
             ResultSet r = p.executeQuery()) {
            assertThat(r.next()).isTrue();
            return new long[] {r.getLong(1), r.getLong(2), r.getLong(3)};
        }
    }

    private long dokumenteZaehlen(String schema) throws Exception {
        try (Connection connection = datenquelle.getConnection();
             PreparedStatement p = connection.prepareStatement("select count(*) from " + schema + ".dokument");
             ResultSet r = p.executeQuery()) {
            assertThat(r.next()).isTrue();
            return r.getLong(1);
        }
    }

    /** Hält das synthetische Manifest wie {@code JdbcManifestDaten.festhalten} fest (Zeilen nur Einfügen). */
    private void manifestFesthalten(String schema, ManifestTestdaten manifest, Map<String, String> versionen)
            throws Exception {
        OffsetDateTime bis = ManifestFenster.bis(MANIFEST_DATUM).atOffset(ZoneOffset.UTC);
        String sig = ManifestTeil.SIG.pfad(MANIFEST_DATUM);
        String tsr = ManifestTeil.tsr("haupt").pfad(MANIFEST_DATUM);
        String kette = ManifestTeil.kette("haupt").pfad(MANIFEST_DATUM);
        String crl = ManifestTeil.crl("haupt").pfad(MANIFEST_DATUM);
        try (Connection connection = datenquelle.getConnection()) {
            try (PreparedStatement p = connection.prepareStatement("insert into " + schema + ".manifest (datum, von, "
                    + "bis, sha256, vorher_sha256, anzahl, protokolle, s3_schluessel, s3_version, sig_sha256, "
                    + "sig_s3_version, sperre_bis) values (?, null, ?, ?, null, 0, '{}'::jsonb, ?, ?, ?, ?, null)")) {
                p.setObject(1, MANIFEST_DATUM);
                p.setObject(2, bis);
                p.setString(3, Pruefsumme.von(manifest.inhalt()));
                p.setString(4, MANIFEST);
                p.setString(5, versionen.get(MANIFEST));
                p.setString(6, Pruefsumme.von(manifest.teile().get(sig)));
                p.setString(7, versionen.get(sig));
                p.executeUpdate();
            }
            String pruefdaten = "[{\"art\":\"kette\",\"sha256\":\"" + Pruefsumme.von(manifest.teile().get(kette))
                    + "\",\"s3_version\":\"" + versionen.get(kette) + "\"},{\"art\":\"crl\",\"sha256\":\""
                    + Pruefsumme.von(manifest.teile().get(crl)) + "\",\"s3_version\":\"" + versionen.get(crl) + "\"}]";
            try (PreparedStatement p = connection.prepareStatement("insert into " + schema + ".manifest_zeitstempel "
                    + "(datum, dienst, gen_time, tsr_sha256, s3_schluessel, s3_version, pruefdaten) "
                    + "values (?, 'haupt', ?, ?, ?, ?, cast(? as jsonb))")) {
                p.setObject(1, MANIFEST_DATUM);
                p.setObject(2, bis.plus(Duration.ofMinutes(20)));
                p.setString(3, Pruefsumme.von(manifest.teile().get(tsr)));
                p.setString(4, tsr);
                p.setString(5, versionen.get(tsr));
                p.setString(6, pruefdaten);
                p.executeUpdate();
            }
        }
    }

    private void pruefeDatei(String code, String key, String version, String erwarteterInhalt) throws Exception {
        try (var objekt = s3.getObject(r -> r.bucket(new MandantCode(code).bucket(ZIEL_PRAEFIX))
                .key(key).versionId(version))) {
            byte[] erhalten = objekt.readAllBytes();
            byte[] erwartet = Base64.getDecoder().decode(erwarteterInhalt);
            assertThat(Pruefsumme.von(erhalten)).isEqualTo(Pruefsumme.von(erwartet));
            assertThat(erhalten).containsExactly(erwartet);
        }
    }

    private static ManifestTestdaten manifestErzeugen(KeyPair schluessel, String code) {
        byte[] inhalt = new ManifestInhalt(code, MANIFEST_DATUM, null, ManifestFenster.bis(MANIFEST_DATUM),
                null, null, List.of(), new TreeMap<>(), List.of()).kanonisch();
        byte[] signatur = ManifestSignatur.signieren(schluessel.getPrivate(), inhalt);
        byte[] imprint = ManifestSignatur.imprint(inhalt, signatur);
        byte[] tsr = ByteBuffer.allocate(40).put(imprint)
                .putLong(ManifestFenster.bis(MANIFEST_DATUM).plus(Duration.ofMinutes(20)).getEpochSecond()).array();
        Map<String, byte[]> teile = Map.of(
                MANIFEST, inhalt,
                ManifestTeil.SIG.pfad(MANIFEST_DATUM), signatur,
                ManifestTeil.tsr("haupt").pfad(MANIFEST_DATUM), tsr,
                ManifestTeil.kette("haupt").pfad(MANIFEST_DATUM),
                "kette haupt".getBytes(StandardCharsets.UTF_8),
                ManifestTeil.crl("haupt").pfad(MANIFEST_DATUM),
                "crl haupt".getBytes(StandardCharsets.UTF_8));
        return new ManifestTestdaten(inhalt, teile);
    }

    private record ManifestTestdaten(byte[] inhalt, Map<String, byte[]> teile) {
    }

    private void zustandSpeichern(Properties zustand) throws Exception {
        Path datei = Path.of(System.getProperty("archiv.restore.state"));
        Files.createDirectories(datei.getParent());
        try (var ausgabe = Files.newOutputStream(datei)) {
            zustand.store(ausgabe, "Synthetische Restore-Testdaten");
        }
    }

    private Properties zustandLaden() throws Exception {
        Properties zustand = new Properties();
        try (var eingabe = Files.newInputStream(Path.of(System.getProperty("archiv.restore.state")))) {
            zustand.load(eingabe);
        }
        return zustand;
    }

    private static KeyPair schluesselLesen(Properties zustand, String mandant) throws Exception {
        String prefix = mandant + ".";
        KeyFactory factory = KeyFactory.getInstance("Ed25519");
        PrivateKey privat = factory.generatePrivate(new PKCS8EncodedKeySpec(
                Base64.getDecoder().decode(zustand.getProperty(prefix + "manifest.private"))));
        PublicKey oeffentlich = factory.generatePublic(new X509EncodedKeySpec(
                Base64.getDecoder().decode(zustand.getProperty(prefix + "manifest.public"))));
        return new KeyPair(oeffentlich, privat);
    }

    private static ManifestSchluessel manifestSchluessel(KeyPair paar) {
        return new ManifestSchluessel() {
            @Override
            public PrivateKey privat() {
                return paar.getPrivate();
            }

            @Override
            public PublicKey oeffentlich() {
                return paar.getPublic();
            }
        };
    }

    public static class ExternesRestoreProfil implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            boolean probe = "P".equals(System.getProperty("archiv.restore.stufe"));
            boolean ziel = "ziel".equals(System.getProperty("archiv.restore.phase"));
            Map<String, String> konfiguration = new HashMap<>(Map.ofEntries(
                    Map.entry("archiv.rolle", "api"),
                    Map.entry("archiv.stufe", probe ? "P" : "A"),
                    Map.entry("archiv.ablage.bucket-praefix",
                            System.getProperty("archiv.restore.bucket-praefix", QUELL_PRAEFIX)),
                    Map.entry("archiv.ablage.manifest-sperrmodus", probe ? "COMPLIANCE" : "GOVERNANCE"),
                    Map.entry("archiv.ablage.max-sperrfrist", "P1D"),
                    Map.entry("archiv.ablage.manifest-max-sperrfrist", "P1D"),
                    Map.entry("quarkus.datasource.devservices.enabled", "false"),
                    Map.entry("quarkus.compose.devservices.enabled", "false"),
                    // ohne Compose Dev Services: Zugang aus den Secret-Dateien des Skripts (smallrye.config.source.file)
                    Map.entry("quarkus.s3.aws.credentials.static-provider.access-key-id", "${s3_app_zugang_id}"),
                    Map.entry("quarkus.s3.aws.credentials.static-provider.secret-access-key",
                            "${s3_app_zugang_geheimnis}"),
                    Map.entry("quarkus.keycloak.devservices.enabled", "false"),
                    Map.entry("quarkus.oidc.tenant-enabled", "false"),
                    Map.entry("quarkus.oidc.health.enabled", "false"),
                    Map.entry("quarkus.mailer.mock", "true")));
            if (ziel) {
                String oidcUrl = System.getProperty("archiv.restore.oidc-url");
                if (oidcUrl == null || oidcUrl.isBlank()) {
                    throw new IllegalStateException("OIDC-URL des wiederhergestellten Keycloak fehlt");
                }
                konfiguration.put("quarkus.oidc.auth-server-url", oidcUrl);
                konfiguration.put("quarkus.oidc.tenant-enabled", "true");
                konfiguration.put("quarkus.oidc.health.enabled", "true");
            }
            return konfiguration;
        }
    }

    private static final class SynthetischerZeitstempel implements ZeitstempelPort {
        @Override
        public List<String> dienste() {
            return List.of("haupt");
        }

        @Override
        public Stempel stempeln(String dienst, byte[] hash) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Stempel pruefen(String dienst, byte[] token, byte[] hash) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Pruefdaten pruefdaten(String dienst, Stempel stempel) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Instant pruefenAbgelegt(String dienst, byte[] token, byte[] hash, String kette,
                                       Pruefdaten pruefdaten) {
            if (!dienst.equals("haupt") || !kette.equals("kette haupt")
                    || pruefdaten.art() != Pruefdaten.Art.CRL
                    || !Arrays.equals(Arrays.copyOf(token, 32), hash)) {
                throw new IllegalStateException("Synthetischer Zeitstempel ist ungültig");
            }
            return Instant.ofEpochSecond(ByteBuffer.wrap(token, 32, 8).getLong());
        }
    }
}
