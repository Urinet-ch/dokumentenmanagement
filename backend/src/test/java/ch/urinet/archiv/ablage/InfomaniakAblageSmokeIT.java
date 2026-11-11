package ch.urinet.archiv.ablage;

import ch.urinet.archiv.ablage.adapter.out.AblageEinstellungen;
import ch.urinet.archiv.ablage.adapter.out.s3.BypassVerbot;
import ch.urinet.archiv.ablage.adapter.out.s3.S3Ablage;
import ch.urinet.archiv.ablage.domain.AbgelegteDatei;
import ch.urinet.archiv.ablage.domain.Ablageort;
import ch.urinet.archiv.ablage.domain.ArchivierungAbgebrochen;
import ch.urinet.archiv.ablage.domain.DateiSchluessel;
import ch.urinet.archiv.ablage.domain.ManifestTeil;
import ch.urinet.archiv.ablage.domain.Pruefsumme;
import ch.urinet.archiv.ablage.domain.SperrAenderung;
import ch.urinet.archiv.ablage.domain.Sperrmodus;
import ch.urinet.archiv.ablage.domain.Sperrvorgabe;
import ch.urinet.archiv.ablage.domain.ZuArchivieren;
import ch.urinet.archiv.mandant.domain.MandantCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.BucketVersioningStatus;
import software.amazon.awssdk.services.s3.model.ObjectLockRetention;
import software.amazon.awssdk.services.s3.model.ObjectLockRetentionMode;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Smoke-Test der Ablage gegen den Object Storage von Infomaniak (Spike 0.2, «folgt in Aufgabe 2.3»). Nur von Hand
 * durch Fabian, nie in der CI: Der Name endet auf {@code IT} (Surefire führt ihn nicht aus) und der Test läuft nur mit
 * {@code -Darchiv.smoke.zugang=<Verzeichnis>}. Anleitung: {@code backend/README.md}, «Smoke-Test Ablage gegen
 * Infomaniak».
 *
 * <p>Endpunkt und Region lassen sich mit {@code -Darchiv.smoke.endpunkt} und {@code -Darchiv.smoke.region} für einen
 * Probelauf gegen ein lokales MinIO umstellen; als Nachweis zählt nur der Lauf gegen Infomaniak.
 *
 * <p>Legt einen eigenen Wegwerf-Bucket {@code urinet-archiv-smoke-<zufall>} mit Object Lock und Versionierung an,
 * arbeitet nur mit synthetischen Inhalten und kurzen Sperren (höchstens 2 Minuten) und räumt nach deren Ablauf ohne
 * Bypass-Header auf. Ausgabe nur Schlüssel, Fehlercodes und Ergebnisse, keine Inhalte und keine signierten URLs.
 */
@EnabledIfSystemProperty(named = "archiv.smoke.zugang", matches = ".+")
class InfomaniakAblageSmokeIT {

    private static final URI ENDPUNKT = URI.create(System.getProperty("archiv.smoke.endpunkt",
            "https://s3.gva2.infomaniak.cloud"));
    private static final Region REGION = Region.of(System.getProperty("archiv.smoke.region", "gva2"));
    private static final String PRAEFIX = "urinet-archiv-smoke-";
    private static final Set<String> KEINE_SPERRE = S3Ablage.KEINE_SPERRE;

    @Test
    void ablageGegenInfomaniak() throws Exception {
        try (S3Client s3 = client()) {
            MandantCode mandant = new MandantCode(UUID.randomUUID().toString().replace("-", "").substring(0, 10));
            String bucket = mandant.bucket(PRAEFIX);
            s3.createBucket(b -> b.bucket(bucket).objectLockEnabledForBucket(true));
            melden("Bucket angelegt", bucket);
            try {
                s3.putBucketVersioning(b -> b.bucket(bucket)
                        .versioningConfiguration(v -> v.status(BucketVersioningStatus.ENABLED)));
                pruefen(s3, mandant, bucket);
            } finally {
                aufraeumen(s3, bucket);
            }
        }
    }

    private void pruefen(S3Client s3, MandantCode mandant, String bucket) throws Exception {
        S3Ablage ablage = ablage(s3, Duration.ofMinutes(1));
        int jahr = LocalDate.now().getYear();

        // 1. Eingang ohne Sperre; Fehlercode von GetObjectRetention ohne Sperre (Soll 2, design.md «Ablage»)
        var schluessel = DateiSchluessel.original(jahr, UUID.randomUUID(), 1, "pdf");
        ZuArchivieren zu = eingang(ablage, mandant, schluessel, "Smoke-Test Original");
        String eingangKey = schluessel.pfad(Ablageort.EINGANG);
        S3Exception ohneSperre = fehler(() -> s3.getObjectRetention(b -> b.bucket(bucket).key(eingangKey)
                .versionId(zu.eingangVersionId())));
        melden("GetObjectRetention ohne Sperre", ohneSperre.statusCode() + " " + code(ohneSperre));
        assertThat(code(ohneSperre)).isIn(KEINE_SPERRE);

        // 2. Archivieren ohne Pflicht: keine Sperre, S3Ablage erkennt «keine Sperre»
        var ohnePflicht = eingang(ablage, mandant, DateiSchluessel.original(jahr, UUID.randomUUID(), 1, "pdf"),
                "Smoke-Test ohne Pflicht");
        AbgelegteDatei frei = ablage.archivieren(mandant, List.of(ohnePflicht), Sperrvorgabe.ohnePflicht())
                .dateien().getFirst();
        assertThat(ablage.sperreVerlaengern(mandant, List.of(frei), Sperrvorgabe.ohnePflicht()).getFirst().ergebnis())
                .isEqualTo(SperrAenderung.Ergebnis.KEINE_SPERRE);
        melden("archiv/ ohne Pflicht", "keine Sperre");

        // 3. eine gelöschte Version gilt nicht als «keine Sperre» (Soll 2)
        s3.deleteObject(b -> b.bucket(bucket).key(frei.schluessel()).versionId(frei.versionId()));
        S3Exception fehlt = fehler(() -> s3.getObjectRetention(b -> b.bucket(bucket).key(frei.schluessel())
                .versionId(frei.versionId())));
        melden("GetObjectRetention fehlende Version", fehlt.statusCode() + " " + code(fehlt));
        assertThat(code(fehlt)).isNotIn(KEINE_SPERRE);
        assertThatThrownBy(() -> ablage.sperreVerlaengern(mandant, List.of(frei), Sperrvorgabe.ohnePflicht()))
                .isInstanceOf(S3Exception.class);

        // 4. Archivieren mit Pflicht: GOVERNANCE, SHA-256 der Kopie über die Versions-ID
        AbgelegteDatei kopie = ablage.archivieren(mandant, List.of(zu), Sperrvorgabe.unbefristet()).dateien().getFirst();
        ObjectLockRetention sperre = s3.getObjectRetention(b -> b.bucket(bucket).key(kopie.schluessel())
                .versionId(kopie.versionId())).retention();
        assertThat(sperre.mode()).isEqualTo(ObjectLockRetentionMode.GOVERNANCE);
        assertThat(kopie.sha256()).isEqualTo(zu.sha256());
        melden("archiv/ mit Pflicht", sperre.mode() + " bis " + sperre.retainUntilDate());

        // 5. ohne Bypass weder löschen noch verkürzen
        S3Exception loeschen = fehler(() -> s3.deleteObject(b -> b.bucket(bucket).key(kopie.schluessel())
                .versionId(kopie.versionId())));
        S3Exception verkuerzen = fehler(() -> s3.putObjectRetention(b -> b.bucket(bucket).key(kopie.schluessel())
                .versionId(kopie.versionId()).retention(r -> r.mode(ObjectLockRetentionMode.GOVERNANCE)
                        .retainUntilDate(Instant.now().plusSeconds(10)))));
        melden("Löschen gesperrt", loeschen.statusCode() + " " + code(loeschen));
        melden("Verkürzen gesperrt", verkuerzen.statusCode() + " " + code(verkuerzen));

        // 6. nur verlängern
        assertThat(ablage(s3, Duration.ofMinutes(2)).sperreVerlaengern(mandant, List.of(kopie),
                Sperrvorgabe.unbefristet()).getFirst().ergebnis()).isEqualTo(SperrAenderung.Ergebnis.VERLAENGERT);
        assertThat(ablage(s3, Duration.ofSeconds(30)).sperreVerlaengern(mandant, List.of(kopie),
                Sperrvorgabe.unbefristet()).getFirst().ergebnis()).isEqualTo(SperrAenderung.Ergebnis.KONFLIKT);
        melden("Sperre", "verlängert, kürzer abgelehnt");

        // 7. vorsignierte URL: GET dieser Version, PUT abgelehnt
        var url = ablage.vorsignierteUrl(mandant, Ablageort.ARCHIV, schluessel, kopie.versionId());
        try (HttpClient http = HttpClient.newHttpClient()) {
            var get = http.send(HttpRequest.newBuilder(url.url()).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
            var put = http.send(HttpRequest.newBuilder(url.url()).PUT(HttpRequest.BodyPublishers.ofString("x")).build(),
                    HttpResponse.BodyHandlers.discarding());
            melden("Vorsignierte URL", "GET " + get.statusCode() + ", PUT " + put.statusCode());
            assertThat(get.statusCode()).isEqualTo(200);
            assertThat(Pruefsumme.von(get.body())).isEqualTo(zu.sha256());
            assertThat(put.statusCode()).isEqualTo(403);
        }

        // 8. neuer Versuch ohne Quelle: die einzige Kopie unter archiv/ bleibt (Soll 1)
        var ohneQuelle = DateiSchluessel.original(jahr, UUID.randomUUID(), 1, "pdf");
        ZuArchivieren verloren = eingang(ablage, mandant, ohneQuelle, "Smoke-Test ohne Quelle");
        s3.putObject(b -> b.bucket(bucket).key(ohneQuelle.pfad(Ablageort.ARCHIV)), RequestBody.fromString("abweichend"));
        ablage.loeschenEingang(mandant, jahr, ohneQuelle.docId());
        assertThatThrownBy(() -> ablage.archivieren(mandant, List.of(verloren), Sperrvorgabe.unbefristet()))
                .isInstanceOfSatisfying(ArchivierungAbgebrochen.class,
                        e -> assertThat(e.grund()).isEqualTo(ArchivierungAbgebrochen.Grund.PRUEFSUMME));
        assertThat(s3.listObjectVersions(b -> b.bucket(bucket).prefix(ohneQuelle.pfad(Ablageort.ARCHIV))).versions())
                .hasSize(1);
        melden("Neuer Versuch ohne Quelle", "abgebrochen, Kopie bleibt");

        // 9. Manifest in COMPLIANCE, gleicher Inhalt gilt als abgelegt
        S3Ablage manifestAblage = new S3Ablage(s3, S3Ablage.presigner(s3, true), new AblageEinstellungen(PRAEFIX,
                Optional.of(Duration.ofMinutes(1)), Sperrmodus.COMPLIANCE, Optional.of(Duration.ofMinutes(1)),
                Duration.ofMinutes(1)), Clock.systemUTC());
        byte[] manifest = "{\"smoke\":true}".getBytes(StandardCharsets.UTF_8);
        LocalDate datum = LocalDate.now();
        AbgelegteDatei abgelegt = manifestAblage.ablegenManifest(mandant, datum, ManifestTeil.JSON, manifest,
                Optional.empty());
        ObjectLockRetention manifestSperre = s3.getObjectRetention(b -> b.bucket(bucket).key(abgelegt.schluessel())
                .versionId(abgelegt.versionId())).retention();
        assertThat(manifestSperre.mode()).isEqualTo(ObjectLockRetentionMode.COMPLIANCE);
        assertThat(manifestAblage.ablegenManifest(mandant, datum, ManifestTeil.JSON, manifest, Optional.empty())
                .versionId()).isEqualTo(abgelegt.versionId());
        melden("Manifest", manifestSperre.mode() + " bis " + manifestSperre.retainUntilDate());

        // 10. der Bypass-Header verlässt den Rechner nicht
        assertThatThrownBy(() -> s3.deleteObject(b -> b.bucket(bucket).key(kopie.schluessel())
                .versionId(kopie.versionId()).bypassGovernanceRetention(true)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining(BypassVerbot.HEADER);
        melden("Bypass-Header", "lokal abgelehnt");
    }

    private static S3Ablage ablage(S3Client s3, Duration hoechstfrist) {
        return new S3Ablage(s3, S3Ablage.presigner(s3, true), new AblageEinstellungen(PRAEFIX, Optional.of(hoechstfrist),
                Sperrmodus.GOVERNANCE, Optional.of(Duration.ofMinutes(1)), Duration.ofMinutes(1)), Clock.systemUTC());
    }

    private static ZuArchivieren eingang(S3Ablage ablage, MandantCode mandant, DateiSchluessel schluessel, String text) {
        byte[] daten = text.getBytes(StandardCharsets.UTF_8);
        AbgelegteDatei datei = ablage.ablegenEingang(mandant, schluessel, new ByteArrayInputStream(daten), daten.length);
        return new ZuArchivieren(schluessel, datei.versionId(), datei.sha256());
    }

    private static S3Exception fehler(Runnable aufruf) {
        try {
            aufruf.run();
        } catch (S3Exception e) {
            return e;
        }
        throw new AssertionError("S3-Fehler erwartet");
    }

    private static String code(S3Exception e) {
        return e.awsErrorDetails() == null ? "?" : e.awsErrorDetails().errorCode();
    }

    private static void melden(String schritt, String ergebnis) {
        System.out.printf("SMOKE %-36s %s%n", schritt, ergebnis);
    }

    /** Wartet das Ende der Sperren ab und löscht ohne Bypass-Header alle Versionen und den Bucket. */
    private static void aufraeumen(S3Client s3, String bucket) throws InterruptedException {
        Instant frist = Instant.now().plus(Duration.ofMinutes(3));
        while (Instant.now().isBefore(frist)) {
            try {
                for (var seite : s3.listObjectVersionsPaginator(b -> b.bucket(bucket))) {
                    for (var v : seite.versions()) {
                        s3.deleteObject(b -> b.bucket(bucket).key(v.key()).versionId(v.versionId()));
                    }
                    for (var m : seite.deleteMarkers()) {
                        s3.deleteObject(b -> b.bucket(bucket).key(m.key()).versionId(m.versionId()));
                    }
                }
                s3.deleteBucket(b -> b.bucket(bucket));
                melden("Aufgeräumt", bucket);
                return;
            } catch (S3Exception e) {
                // noch gesperrt
                Thread.sleep(15_000);
            }
        }
        melden("NICHT aufgeräumt", bucket + " – nach Ablauf der Sperren von Hand löschen");
    }

    private static S3Client client() throws IOException {
        Path verzeichnis = Path.of(System.getProperty("archiv.smoke.zugang").replaceFirst("^~", System.getProperty("user.home")));
        return S3Client.builder()
                .endpointOverride(ENDPUNKT)
                .region(REGION)
                .forcePathStyle(true)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(
                        geheim(verzeichnis.resolve("zugang_id")), geheim(verzeichnis.resolve("zugang_geheimnis")))))
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .overrideConfiguration(o -> o.addExecutionInterceptor(new BypassVerbot()))
                .build();
    }

    /** Liest eine Schlüsseldatei; nur mit Rechten 600 (tech.md Regel 7). */
    private static String geheim(Path datei) throws IOException {
        Set<PosixFilePermission> rechte = Files.getPosixFilePermissions(datei);
        if (!rechte.equals(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE))
                && !rechte.equals(Set.of(PosixFilePermission.OWNER_READ))) {
            throw new IllegalStateException(datei.getFileName() + " braucht die Rechte 600 oder 400");
        }
        return Files.readString(datei).strip();
    }
}
