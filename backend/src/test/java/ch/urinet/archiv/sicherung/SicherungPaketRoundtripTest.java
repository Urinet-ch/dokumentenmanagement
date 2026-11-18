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

import static org.assertj.core.api.Assertions.assertThat;

/** Echter Export-/Datenbank-/Bucket-Roundtrip; wird nur vom lokalen Restore-Skript aktiviert. */
@QuarkusTest
@TestProfile(SicherungPaketRoundtripTest.ExternesRestoreProfil.class)
@Tag("start-2")
@EnabledIfSystemProperty(named = "archiv.restore.e2e", matches = "true")
class SicherungPaketRoundtripTest {

    private static final String QUELL_PRAEFIX = "urinet-archiv-a-";
    private static final String ZIEL_PRAEFIX = "urinet-archiv-p-";
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

        var mandant = provisionierung.anlegen("urinet", null, null);
        assertThat(mandant.bucket()).isEqualTo(new MandantCode("urinet").bucket(QUELL_PRAEFIX));

        UUID id = UUID.randomUUID();
        String originalKey = "archiv/2026/" + id + "/original.pdf";
        String pdfaKey = "archiv/2026/" + id + "/pdfa.pdf";
        String originalVersion = ablegen(mandant.bucket(), originalKey, ORIGINAL);
        String pdfaVersion = ablegen(mandant.bucket(), pdfaKey, PDFA);
        dokumentAnlegen(mandant.schema(), id, pdfaKey, pdfaVersion, originalKey, originalVersion);

        KeyPair schluessel = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        ManifestTestdaten manifest = manifestErzeugen(schluessel);
        Map<String, String> versionen = new HashMap<>();
        for (var teil : manifest.teile().entrySet()) {
            versionen.put(teil.getKey(), ablegenGesperrt(mandant.bucket(), teil.getKey(), teil.getValue()));
        }
        String manifestVersion = versionen.get(MANIFEST);
        manifestFesthalten(mandant.schema(), manifest, versionen);
        Properties zustand = new Properties();
        zustand.setProperty("dokument.id", id.toString());
        zustand.setProperty("dokument.original.schluessel", originalKey);
        zustand.setProperty("dokument.original.sha256", Pruefsumme.von(ORIGINAL));
        zustand.setProperty("dokument.original.inhalt", Base64.getEncoder().encodeToString(ORIGINAL));
        zustand.setProperty("dokument.pdfa.schluessel", pdfaKey);
        zustand.setProperty("dokument.pdfa.sha256", Pruefsumme.von(PDFA));
        zustand.setProperty("dokument.pdfa.inhalt", Base64.getEncoder().encodeToString(PDFA));
        zustand.setProperty("manifest.version", manifestVersion);
        zustand.setProperty("manifest.private", Base64.getEncoder().encodeToString(schluessel.getPrivate().getEncoded()));
        zustand.setProperty("manifest.public", Base64.getEncoder().encodeToString(schluessel.getPublic().getEncoded()));
        zustandSpeichern(zustand);
    }

    @Test
    void wiederhergestellteAnwendungUndManifestPruefen() throws Exception {
        Assumptions.assumeTrue("ziel".equals(System.getProperty("archiv.restore.phase")));

        // Wie bei der Restore-Probe: leerer Ziel-Bucket erst im Ziel-Schritt, die Wiederherstellung verlangt ihn leer.
        String zielBucket = new MandantCode("urinet").bucket(ZIEL_PRAEFIX);
        s3.createBucket(b -> b.bucket(zielBucket).objectLockEnabledForBucket(true));
        s3.putBucketVersioning(b -> b.bucket(zielBucket)
                .versioningConfiguration(v -> v.status(BucketVersioningStatus.ENABLED)));

        Path inventar = Path.of(System.getProperty("archiv.restore.inventar"));
        List<String> inventarzeilen = Files.readAllLines(inventar, StandardCharsets.UTF_8);
        List<String> ohneManifest = inventarzeilen.stream()
                .filter(zeile -> !zeile.contains("\"schluessel\":\"manifest/")).toList();
        assertThat(ohneManifest).hasSizeLessThan(inventarzeilen.size());
        Path inventarOhneManifest = Files.createTempFile("restore-ohne-manifest-", ".jsonl");
        try {
            Files.write(inventarOhneManifest, ohneManifest, StandardCharsets.UTF_8);
            int fehlendesManifestExit = ohneAnfrage(() -> sicherung.ausfuehren(List.of("wiederherstellen",
                    "--mandant", "urinet",
                    "--inventar", inventarOhneManifest.toString(), "--strom",
                    System.getProperty("archiv.restore.bucket"), "--kappen"),
                    new PrintStream(new ByteArrayOutputStream()), new PrintStream(new ByteArrayOutputStream())));
            assertThat(fehlendesManifestExit).isEqualTo(4);
        } finally {
            Files.deleteIfExists(inventarOhneManifest);
        }

        var restoreAusgabe = new ByteArrayOutputStream();
        var restoreFehler = new ByteArrayOutputStream();
        int restoreExit = ohneAnfrage(() -> sicherung.ausfuehren(List.of("wiederherstellen", "--mandant", "urinet",
                "--inventar",
                System.getProperty("archiv.restore.inventar"), "--strom",
                System.getProperty("archiv.restore.bucket"), "--kappen"),
                new PrintStream(restoreAusgabe), new PrintStream(restoreFehler)));
        assertThat(restoreExit).as(() -> restoreFehler.toString(StandardCharsets.UTF_8)
                + restoreAusgabe.toString(StandardCharsets.UTF_8)).isZero();
        assertThat(restoreFehler.toString(StandardCharsets.UTF_8)).isEmpty();

        HttpResponse<Void> health = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(readiness).GET().build(), HttpResponse.BodyHandlers.discarding());
        assertThat(health.statusCode()).isEqualTo(200);

        Properties erwartet = zustandLaden();
        UUID id = UUID.fromString(erwartet.getProperty("dokument.id"));
        try (Connection connection = datenquelle.getConnection()) {
            try (PreparedStatement p = connection.prepareStatement("select count(*) from m_urinet.dokument");
                 ResultSet r = p.executeQuery()) {
                assertThat(r.next()).isTrue();
                assertThat(r.getLong(1)).isEqualTo(1);
            }
            try (PreparedStatement p = connection.prepareStatement("select d.sha256_pdfa, d.s3_pdfa, "
                    + "d.s3_version_pdfa, f.sha256, f.s3_schluessel, f.s3_version "
                    + "from m_urinet.dokument d join m_urinet.dokument_datei f on f.dokument_id = d.id "
                    + "where d.id = ?")) {
                p.setObject(1, id);
                try (ResultSet r = p.executeQuery()) {
                    assertThat(r.next()).isTrue();
                    assertThat(r.getString(1)).isEqualTo(erwartet.getProperty("dokument.pdfa.sha256"));
                    assertThat(r.getString(2)).isEqualTo(erwartet.getProperty("dokument.pdfa.schluessel"));
                    assertThat(r.getString(4)).isEqualTo(erwartet.getProperty("dokument.original.sha256"));
                    assertThat(r.getString(5)).isEqualTo(erwartet.getProperty("dokument.original.schluessel"));
                    pruefeDatei(r.getString(2), r.getString(3), erwartet.getProperty("dokument.pdfa.inhalt"));
                    pruefeDatei(r.getString(5), r.getString(6), erwartet.getProperty("dokument.original.inhalt"));
                }
            }
            // manifest ist nur Einfügen: Die Zeile behält die Versions-ID der Quelle, geprüft wird über die Hashes.
            try (PreparedStatement p = connection.prepareStatement(
                    "select sha256, s3_version from m_urinet.manifest where datum = ?")) {
                p.setObject(1, MANIFEST_DATUM);
                try (ResultSet r = p.executeQuery()) {
                    assertThat(r.next()).isTrue();
                    assertThat(r.getString(1)).isEqualTo(Pruefsumme.von(
                            manifestErzeugen(schluesselLesen(erwartet)).inhalt()));
                    assertThat(r.getString(2)).isEqualTo(erwartet.getProperty("manifest.version"));
                }
            }
        }

        var mandant = new MandantCode("urinet");
        var zielAblage = new S3Ablage(s3, S3Ablage.presigner(s3, true), einstellungen, Clock.systemUTC());
        var manifestImZiel = zielAblage.lesenManifest(mandant, MANIFEST_DATUM,
                ManifestTeil.JSON).orElseThrow();
        assertThat(manifestImZiel.datei().versionId()).isNotEqualTo(erwartet.getProperty("manifest.version"));
        assertThat(manifestImZiel.inhalt()).isEqualTo(manifestErzeugen(schluesselLesen(erwartet)).inhalt());

        var schluessel = schluesselLesen(erwartet);
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

    private void pruefeDatei(String key, String version, String erwarteterInhalt) throws Exception {
        try (var objekt = s3.getObject(r -> r.bucket(new MandantCode("urinet").bucket(ZIEL_PRAEFIX))
                .key(key).versionId(version))) {
            byte[] erhalten = objekt.readAllBytes();
            byte[] erwartet = Base64.getDecoder().decode(erwarteterInhalt);
            assertThat(Pruefsumme.von(erhalten)).isEqualTo(Pruefsumme.von(erwartet));
            assertThat(erhalten).containsExactly(erwartet);
        }
    }

    private static ManifestTestdaten manifestErzeugen(KeyPair schluessel) {
        byte[] inhalt = new ManifestInhalt("urinet", MANIFEST_DATUM, null, ManifestFenster.bis(MANIFEST_DATUM),
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

    private static KeyPair schluesselLesen(Properties zustand) throws Exception {
        KeyFactory factory = KeyFactory.getInstance("Ed25519");
        PrivateKey privat = factory.generatePrivate(new PKCS8EncodedKeySpec(
                Base64.getDecoder().decode(zustand.getProperty("manifest.private"))));
        PublicKey oeffentlich = factory.generatePublic(new X509EncodedKeySpec(
                Base64.getDecoder().decode(zustand.getProperty("manifest.public"))));
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
            return Map.ofEntries(
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
                    Map.entry("quarkus.mailer.mock", "true"));
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
