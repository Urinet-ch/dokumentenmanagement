package ch.urinet.archiv.ablage;

import ch.urinet.archiv.ablage.adapter.out.AblageEinstellungen;
import ch.urinet.archiv.ablage.adapter.out.s3.S3Ablage;
import ch.urinet.archiv.ablage.domain.Pruefsumme;
import ch.urinet.archiv.ablage.domain.Sperrmodus;
import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.sicherung.application.SicherungBucketExport;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Ein Objekt grösser als der Heap der Speicher-Ausführung ({@code -Xmx64m}, pom.xml) wird über {@link S3Ablage} und
 * MinIO stromweise exportiert (Aufgabe 5.5b, Req 16.8). Ein {@code @QuarkusTest} startet unter {@code -Xmx64m} nicht,
 * deshalb läuft der Test im normalen Heap und belegt das Streaming über die Schreibgrösse: Kein einzelner Schreibaufruf
 * an die Senke ist grösser als 8 MiB (ein puffernder Export schriebe das Objekt in einem Stück). Der SHA-256-Trailer
 * wird gegen den Inhalt geprüft, der nie im Speicher steht.
 */
@QuarkusTest
class SicherungExportGrossobjektTest {

    private static final long GROESSE = 96L * 1024 * 1024;
    private static final Path ZWISCHENABLAGE = Path.of("target", "arbeit-s3ablage", "knapp").toAbsolutePath();

    @Inject
    S3Client s3;

    private S3Client verwaltung;
    private MandantCode mandant;
    private String bucket;

    @BeforeEach
    void bucketAnlegen() {
        verwaltung = TestBucket.verwaltung();
        mandant = TestBucket.neu(verwaltung, true);
        bucket = TestBucket.bucket(mandant);
    }

    @AfterEach
    void aufraeumen() {
        TestBucket.aufraeumen(verwaltung, mandant);
        verwaltung.close();
    }

    @Test
    void objektGroesserAlsDerHeapWirdStromweiseExportiert() throws IOException {
        assertThat(GROESSE).as("grösser als der Heap der Speicher-Ausführung").isGreaterThan(64L * 1024 * 1024);
        String key = "archiv/2026/" + UUID.randomUUID() + "/gross.bin";
        String versionId = verwaltung.putObject(r -> r.bucket(bucket).key(key),
                RequestBody.fromContentProvider(() -> new MusterStrom(), GROESSE, "application/octet-stream"))
                .versionId();
        var digest = Pruefsumme.sha256();
        try (InputStream ein = new MusterStrom()) {
            byte[] block = new byte[64 * 1024];
            for (int n; (n = ein.read(block)) > 0; ) {
                digest.update(block, 0, n);
            }
        }
        var einstellungen = new AblageEinstellungen(TestBucket.PRAEFIX, Optional.of(Duration.ofDays(1)),
                Sperrmodus.GOVERNANCE, Optional.of(Duration.ofDays(1)), Duration.ofMinutes(5), ZWISCHENABLAGE);
        var export = new SicherungBucketExport(new S3Ablage(s3, S3Ablage.presigner(s3, true), einstellungen,
                Clock.systemUTC()));
        Zaehler senke = new Zaehler();

        export.zuwachs(mandant, v -> true, senke);

        long rahmen = SicherungBucketExport.BUCKET_FORMAT.length() + 4 + 4 + key.length() + 4 + versionId.length()
                + 8 + 8 + 4 + 32;
        assertThat(senke.anzahl).isEqualTo(rahmen + GROESSE);
        assertThat(senke.groessterSchreibaufruf).as("grösster einzelner Schreibaufruf").isLessThanOrEqualTo(8 * 1024 * 1024);
        assertThat(HexFormat.of().formatHex(senke.ende)).isEqualTo(Pruefsumme.hex(digest));
    }

    /** Zählt die Bytes und behält die letzten 32. */
    private static final class Zaehler extends OutputStream {
        long anzahl;
        int groessterSchreibaufruf;
        final byte[] ende = new byte[32];

        @Override
        public void write(int b) {
            System.arraycopy(ende, 1, ende, 0, 31);
            ende[31] = (byte) b;
            anzahl++;
            groessterSchreibaufruf = Math.max(groessterSchreibaufruf, 1);
        }

        @Override
        public void write(byte[] daten, int ab, int laenge) {
            anzahl += laenge;
            groessterSchreibaufruf = Math.max(groessterSchreibaufruf, laenge);
            if (laenge >= 32) {
                System.arraycopy(daten, ab + laenge - 32, ende, 0, 32);
            } else {
                System.arraycopy(ende, laenge, ende, 0, 32 - laenge);
                System.arraycopy(daten, ab, ende, 32 - laenge, laenge);
            }
        }
    }

    /** Erzeugt deterministische Bytes, ohne sie zu halten. */
    private static final class MusterStrom extends InputStream {
        private long position;

        @Override
        public int read() {
            return position >= GROESSE ? -1 : (int) ((position++ * 31) & 0xff);
        }

        @Override
        public int read(byte[] ziel, int ab, int laenge) {
            if (position >= GROESSE) {
                return -1;
            }
            int n = (int) Math.min(laenge, GROESSE - position);
            for (int i = 0; i < n; i++) {
                ziel[ab + i] = (byte) (((position + i) * 31) & 0xff);
            }
            position += n;
            return n;
        }
    }
}
