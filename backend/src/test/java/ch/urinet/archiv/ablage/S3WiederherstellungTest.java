package ch.urinet.archiv.ablage;

import ch.urinet.archiv.ablage.adapter.out.AblageEinstellungen;
import ch.urinet.archiv.ablage.adapter.out.s3.S3Wiederherstellung;
import ch.urinet.archiv.ablage.domain.Pruefsumme;
import ch.urinet.archiv.ablage.domain.Sperrmodus;
import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.sicherung.application.WiederherstellungsZiel.Quittung;
import ch.urinet.archiv.sicherung.application.WiederherstellungsZielNichtGeeignet;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.BucketVersioningStatus;
import software.amazon.awssdk.services.s3.model.ObjectLockRetentionMode;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@QuarkusTest
class S3WiederherstellungTest {

    private static final long GROSSE_DATEI = 50L * 1024 * 1024;
    private static final byte[] MUSTER = new byte[8192];

    static {
        for (int i = 0; i < MUSTER.length; i++) {
            MUSTER[i] = (byte) (i % 251);
        }
    }

    @Inject
    S3Client s3;

    private S3Client verwaltung;
    private MandantCode mandant;
    private final List<String> buckets = new ArrayList<>();

    @BeforeEach
    void bucketAnlegen() {
        verwaltung = TestBucket.verwaltung();
        mandant = bucketAnlegen(true);
    }

    @AfterEach
    void aufraeumen() {
        buckets.forEach(bucket -> TestBucket.aufraeumen(verwaltung, bucket));
        verwaltung.close();
        buckets.clear();
    }

    @Test
    void akzeptiertNurLeeresBucketOhneVersionenOderLoeschmarken() {
        S3Wiederherstellung ziel = ziel();

        assertThat(ziel.leer(mandant)).isTrue();

        verwaltung.putObject(r -> r.bucket(TestBucket.bucket(mandant)).key("archiv/test.pdf"),
                RequestBody.fromString("synthetisch"));
        assertThat(ziel.leer(mandant)).isFalse();

        verwaltung.deleteObject(r -> r.bucket(TestBucket.bucket(mandant)).key("archiv/test.pdf"));
        assertThat(ziel.leer(mandant)).isFalse();
    }

    @Test
    void brichtBeiFehlendemBucketObjectLockOderVersionierungAb() {
        S3Wiederherstellung ziel = ziel();
        MandantCode fehlt = code();
        MandantCode ohneLock = bucketAnlegen(false);
        MandantCode ohneVersionierung = bucketAnlegen(false);
        verwaltung.putBucketVersioning(r -> r.bucket(TestBucket.bucket(ohneVersionierung))
                .versioningConfiguration(v -> v.status(BucketVersioningStatus.SUSPENDED)));

        assertThatThrownBy(() -> ziel.leer(fehlt)).isInstanceOf(WiederherstellungsZielNichtGeeignet.class)
                .hasMessageContaining(fehlt.wert()).hasMessageNotContaining(fehlt.bucket(TestBucket.PRAEFIX));
        assertThatThrownBy(() -> ziel.leer(ohneLock)).isInstanceOf(WiederherstellungsZielNichtGeeignet.class)
                .hasMessageContaining(ohneLock.wert()).hasMessageNotContaining(TestBucket.bucket(ohneLock));
        assertThatThrownBy(() -> ziel.leer(ohneVersionierung))
                .isInstanceOf(WiederherstellungsZielNichtGeeignet.class)
                .hasMessageContaining(ohneVersionierung.wert())
                .hasMessageNotContaining(TestBucket.bucket(ohneVersionierung));
    }

    @Test
    void schreibt50MiBGestreamtSetztSperrenUndVerkuerztKeineBestehendeSperre() throws IOException {
        S3Wiederherstellung ziel = ziel();
        assertThat(ziel.leer(mandant)).isTrue();
        String bucket = TestBucket.bucket(mandant);
        String schluessel = "archiv/50-mib.bin";
        Instant jetzt = Instant.now();

        Quittung quittung = ziel.schreiben(schluessel, "quellversion", jetzt, musterstrom(GROSSE_DATEI),
                GROSSE_DATEI);

        assertThat(quittung.sha256()).isEqualTo(musterSha256(GROSSE_DATEI));
        MessageDigest digest = Pruefsumme.sha256();
        long gelesen;
        try (InputStream objekt = s3.getObject(r -> r.bucket(bucket).key(schluessel).versionId(quittung.versionId()));
             DigestInputStream geprueft = new DigestInputStream(objekt, digest)) {
            gelesen = geprueft.transferTo(OutputStream.nullOutputStream());
        }
        assertThat(gelesen).isEqualTo(GROSSE_DATEI);
        assertThat(Pruefsumme.hex(digest)).isEqualTo(quittung.sha256());

        Instant sperreBis = Instant.now().plus(Duration.ofDays(2)).truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        ziel.sperren(quittung, Sperrmodus.GOVERNANCE, sperreBis);
        var gesetzteSperre = s3.getObjectRetention(r -> r.bucket(bucket).key(schluessel)
                .versionId(quittung.versionId())).retention();
        assertThat(gesetzteSperre.mode()).isEqualTo(ObjectLockRetentionMode.GOVERNANCE);
        assertThat(gesetzteSperre.retainUntilDate()).isEqualTo(sperreBis);

        String verlaengert = "archiv/verlaengert.bin";
        Quittung verlaengertQuittung = ziel.schreiben(verlaengert, "quellversion-2", jetzt,
                new ByteArrayInputStream(new byte[]{1, 2, 3}), 3);
        Instant bestehendBis = Instant.now().plus(Duration.ofDays(5))
                .truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        verwaltung.putObjectRetention(r -> r.bucket(bucket).key(verlaengert)
                .versionId(verlaengertQuittung.versionId()).retention(o -> o
                        .mode(ObjectLockRetentionMode.GOVERNANCE).retainUntilDate(bestehendBis)));
        ziel.sperren(verlaengertQuittung, Sperrmodus.GOVERNANCE, Instant.now().plus(Duration.ofDays(2)));

        assertThat(s3.getObjectRetention(r -> r.bucket(bucket).key(verlaengert)
                .versionId(verlaengertQuittung.versionId())).retention().retainUntilDate())
                .isEqualTo(bestehendBis);
    }

    @Test
    void verwirftNurDieGeradeGeschriebeneVersion() {
        S3Wiederherstellung ziel = ziel();
        assertThat(ziel.leer(mandant)).isTrue();
        String bucket = TestBucket.bucket(mandant);
        Quittung quittung = ziel.schreiben("archiv/temp.bin", "quellversion", Instant.now(),
                new ByteArrayInputStream(new byte[]{4, 5}), 2);
        String erhalteneVersion = verwaltung.putObject(r -> r.bucket(bucket).key("archiv/behalten.bin"),
                RequestBody.fromString("synthetisch")).versionId();

        ziel.verwerfen(quittung);

        assertThat(verwaltung.listObjectVersions(r -> r.bucket(bucket)).versions())
                .extracting(v -> v.key() + "@" + v.versionId())
                .containsExactly("archiv/behalten.bin@" + erhalteneVersion);
    }

    private S3Wiederherstellung ziel() {
        AblageEinstellungen einstellungen = new AblageEinstellungen(TestBucket.PRAEFIX, Optional.empty(),
                Sperrmodus.GOVERNANCE, Optional.empty(), Duration.ofMinutes(5),
                Path.of("target", "arbeit-s3ablage", "sicherung").toAbsolutePath());
        return new S3Wiederherstellung(s3, einstellungen);
    }

    private MandantCode bucketAnlegen(boolean objectLock) {
        MandantCode code = TestBucket.neu(verwaltung, objectLock);
        buckets.add(TestBucket.bucket(code));
        return code;
    }

    private static MandantCode code() {
        return new MandantCode("tfehlendesbucket");
    }

    private static InputStream musterstrom(long groesse) {
        return new InputStream() {
            private long position;

            @Override
            public int read() {
                if (position == groesse) {
                    return -1;
                }
                return MUSTER[(int) (position++ % MUSTER.length)] & 0xff;
            }

            @Override
            public int read(byte[] daten, int ab, int laenge) {
                if (position == groesse) {
                    return -1;
                }
                int n = (int) Math.min(laenge, groesse - position);
                for (int i = 0; i < n; i++) {
                    daten[ab + i] = MUSTER[(int) ((position + i) % MUSTER.length)];
                }
                position += n;
                return n;
            }
        };
    }

    private static String musterSha256(long groesse) {
        MessageDigest digest = Pruefsumme.sha256();
        for (long position = 0; position < groesse; position += MUSTER.length) {
            digest.update(MUSTER, 0, (int) Math.min(MUSTER.length, groesse - position));
        }
        return Pruefsumme.hex(digest);
    }
}
