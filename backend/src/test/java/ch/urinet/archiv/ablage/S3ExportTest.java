package ch.urinet.archiv.ablage;

import ch.urinet.archiv.ablage.adapter.out.AblageEinstellungen;
import ch.urinet.archiv.ablage.adapter.out.s3.S3Ablage;
import ch.urinet.archiv.ablage.application.Exportziel;
import ch.urinet.archiv.ablage.domain.AbgelegteDatei;
import ch.urinet.archiv.ablage.domain.Kopie;
import ch.urinet.archiv.ablage.domain.Pruefsumme;
import ch.urinet.archiv.ablage.domain.Sperrmodus;
import ch.urinet.archiv.ablage.domain.VorsignierteUrl;
import ch.urinet.archiv.mandant.domain.MandantCode;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.ExpirationStatus;
import software.amazon.awssdk.services.s3.model.LifecycleRule;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Ablage des Jahresexports gegen MinIO (Spec 02, Aufgabe 6.5): Multipart-Upload in Teilen von 5 MiB, Abbruch ohne
 * Rückstand, Kopie aus {@code archiv/} mit SHA-256, vorsignierte Adresse mit Ablauf und Lebenszyklus von
 * {@code exporte/}. Eigener Bucket mit Object Lock und Versionierung je Test.
 */
@QuarkusTest
class S3ExportTest {

    private static final Path ZWISCHENABLAGE = Path.of("target", "arbeit-s3export", "ablage").toAbsolutePath();
    private static final int TEIL = 5 * 1024 * 1024;

    @Inject
    S3Client s3;

    private S3Client verwaltung;
    private MandantCode mandant;
    private String bucket;
    private S3Ablage ablage;

    @BeforeEach
    void bucketAnlegen() {
        verwaltung = TestBucket.verwaltung();
        mandant = TestBucket.neu(verwaltung, true);
        bucket = TestBucket.bucket(mandant);
        ablage = new S3Ablage(s3, S3Ablage.presigner(s3, true), new AblageEinstellungen(TestBucket.PRAEFIX,
                Optional.of(Duration.ofDays(1)), Sperrmodus.GOVERNANCE, Optional.of(Duration.ofDays(1)),
                Duration.ofMinutes(5), ZWISCHENABLAGE, TEIL), Clock.systemUTC());
    }

    @AfterEach
    void aufraeumen() {
        TestBucket.aufraeumen(verwaltung, mandant);
        verwaltung.close();
    }

    @AfterAll
    static void keinBypassGesendet() {
        assertThat(KeinBypassInterceptor.gesendetAusserSelbsttest()).as("Aufrufe mit Bypass-Header").isEmpty();
    }

    @Test
    void schreibtInTeilenUndLiefertVersionUndPruefsumme() throws Exception {
        UUID id = UUID.randomUUID();
        byte[] daten = new byte[2 * TEIL + 12_345];
        new Random(7).nextBytes(daten);
        AbgelegteDatei zip;
        try (Exportziel ziel = ablage.exportSchreiben(mandant, id)) {
            OutputStream aus = ziel.strom();
            aus.write(daten, 0, 1000);
            aus.write(daten[1000]);
            aus.write(daten, 1001, daten.length - 1001);
            zip = ziel.abschliessen();
        }

        assertThat(zip.schluessel()).isEqualTo("exporte/" + id + ".zip");
        assertThat(zip.groesse()).isEqualTo(daten.length);
        assertThat(zip.sha256()).isEqualTo(Pruefsumme.von(daten));
        assertThat(zip.sperreBis()).isEmpty();
        assertThat(verwaltung.headObject(b -> b.bucket(bucket).key(zip.schluessel()).versionId(zip.versionId())
                .partNumber(1)).partsCount()).isEqualTo(3);
        assertThat(Pruefsumme.von(verwaltung.getObjectAsBytes(b -> b.bucket(bucket).key(zip.schluessel())
                .versionId(zip.versionId())).asByteArray())).isEqualTo(zip.sha256());
        assertThat(TestAblage.gesperrt(mandant.wert(), zip.schluessel(), zip.versionId())).isFalse();
    }

    @Test
    void ohneAbschlussBleibtNichtsZurueck() throws Exception {
        UUID id = UUID.randomUUID();
        try (Exportziel ziel = ablage.exportSchreiben(mandant, id)) {
            ziel.strom().write(new byte[TEIL + 1]);
        }
        assertThat(verwaltung.listObjectVersions(b -> b.bucket(bucket).prefix("exporte/")).versions()).isEmpty();
        assertThat(verwaltung.listMultipartUploads(b -> b.bucket(bucket)).uploads()).isEmpty();
    }

    @Test
    void kopiertGenauDieVersionUndNurAusArchivUndManifest() {
        String key = "archiv/2026/" + UUID.randomUUID() + "/pdfa.pdf";
        String alt = verwaltung.putObject(b -> b.bucket(bucket).key(key), RequestBody.fromString("alt")).versionId();
        verwaltung.putObject(b -> b.bucket(bucket).key(key), RequestBody.fromString("neu"));
        ByteArrayOutputStream ziel = new ByteArrayOutputStream();

        Kopie kopie = ablage.kopieren(mandant, key, alt, ziel);

        assertThat(ziel.toString(StandardCharsets.UTF_8)).isEqualTo("alt");
        assertThat(kopie).isEqualTo(new Kopie(Pruefsumme.von("alt".getBytes(StandardCharsets.UTF_8)), 3));
        assertThatThrownBy(() -> ablage.kopieren(mandant, "eingang/2026/x/pdfa.pdf", alt, ziel))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ablage.kopieren(mandant, key, null, ziel)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void vorsignierteAdresseAufDieVersionMitAblaufUndName() throws Exception {
        AbgelegteDatei zip;
        try (Exportziel ziel = ablage.exportSchreiben(mandant, UUID.randomUUID())) {
            ziel.strom().write("ZIP".getBytes(StandardCharsets.UTF_8));
            zip = ziel.abschliessen();
        }
        Instant bis = Instant.now().plus(Duration.ofHours(23));
        VorsignierteUrl url = ablage.exportUrl(mandant, zip.schluessel(), zip.versionId(), bis,
                "jahresexport-urinet-2026.zip");

        assertThat(url.gueltigBis()).isBetween(bis.minusSeconds(2), bis.plusSeconds(2));
        assertThat(url.url().toString()).contains("versionId=", "X-Amz-Expires=");
        try (HttpClient http = HttpClient.newHttpClient()) {
            var antwort = http.send(HttpRequest.newBuilder(url.url()).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(antwort.statusCode()).isEqualTo(200);
            assertThat(antwort.body()).isEqualTo("ZIP");
            assertThat(antwort.headers().firstValue("Content-Disposition")).hasValue(
                    "attachment; filename=\"jahresexport-urinet-2026.zip\"");
        }
        assertThatThrownBy(() -> ablage.exportUrl(mandant, "archiv/x.pdf", zip.versionId(), bis, "a.zip"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ablage.exportUrl(mandant, zip.schluessel(), zip.versionId(),
                Instant.now().plus(Duration.ofDays(8)), "a.zip")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ablage.exportUrl(mandant, zip.schluessel(), zip.versionId(), bis, "a\".zip"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(URI.create(url.url().toString()).getQuery()).doesNotContain("bypass");
    }

    @Test
    void lebenszyklusFuerExporteBehaeltAndereRegeln() {
        LifecycleRule fremd = LifecycleRule.builder().id("fremd").status(ExpirationStatus.ENABLED)
                .filter(f -> f.prefix("vorschau/")).expiration(e -> e.days(30)).build();
        verwaltung.putBucketLifecycleConfiguration(b -> b.bucket(bucket).lifecycleConfiguration(l -> l.rules(fremd)));

        ablage.exportLebenszyklusSichern(mandant);
        ablage.exportLebenszyklusSichern(mandant);

        var regeln = verwaltung.getBucketLifecycleConfiguration(b -> b.bucket(bucket)).rules();
        assertThat(regeln).extracting(LifecycleRule::id).containsExactlyInAnyOrder("fremd", "exporte-7-tage");
        LifecycleRule export = regeln.stream().filter(r -> r.id().equals("exporte-7-tage")).findFirst().orElseThrow();
        assertThat(export.filter().prefix()).isEqualTo("exporte/");
        assertThat(export.expiration().days()).isEqualTo(7);
        assertThat(export.noncurrentVersionExpiration().noncurrentDays()).isEqualTo(1);
        // MinIO übernimmt AbortIncompleteMultipartUpload nicht in die Konfiguration (Ceph und AWS schon)
        if (export.abortIncompleteMultipartUpload() != null) {
            assertThat(export.abortIncompleteMultipartUpload().daysAfterInitiation()).isEqualTo(1);
        }
    }

    @Test
    void ohneBisherigeKonfiguration() {
        ablage.exportLebenszyklusSichern(mandant);
        assertThat(verwaltung.getBucketLifecycleConfiguration(b -> b.bucket(bucket)).rules())
                .extracting(LifecycleRule::id).containsExactly("exporte-7-tage");
    }
}
