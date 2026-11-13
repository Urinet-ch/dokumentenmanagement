package ch.urinet.archiv.ablage.adapter.out.s3;

import ch.urinet.archiv.ablage.domain.AbgelegteDatei;
import ch.urinet.archiv.ablage.domain.Kopie;
import ch.urinet.archiv.ablage.domain.Pruefsumme;
import ch.urinet.archiv.export.application.ExportBelege;
import ch.urinet.archiv.export.application.ExportSchreiber;
import ch.urinet.archiv.export.domain.Geschaeftsjahr;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.AbortMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.AbortMultipartUploadResponse;
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadResponse;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadResponse;
import software.amazon.awssdk.services.s3.model.UploadPartRequest;
import software.amazon.awssdk.services.s3.model.UploadPartResponse;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.time.MonthDay;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.SplittableRandom;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Speicher des Jahresexports (Spec 02, Aufgabe 6.5: «nie die ganze Datei im Speicher»): Ein synthetischer Bestand von
 * {@value #DOKUMENTE} Belegen zu je {@value #GROESSE_MIB} MiB (zufällige, kaum komprimierbare Bytes, zusammen
 * 192 MiB) läuft in einer eigenen JVM mit {@code -Xmx}{@value #XMX_MIB}{@code m} durch {@link ExportSchreiber} und
 * den Multipart-Upload {@link S3Exportziel} (Teile zu 8 MiB) gegen ein S3, das die Teile nur zählt und verwirft. Hielte
 * ein Schritt eine ganze Datei oder das ZIP im Heap, endete die Kind-JVM mit {@code OutOfMemoryError}. Die
 * Kind-JVM bekommt nur die Jars, die der Lauf braucht (wie {@code CsvImportSpeicherTest}).
 */
class ExportSpeicherTest {

    static final int DOKUMENTE = 24;
    static final int GROESSE_MIB = 8;
    static final int XMX_MIB = 48;
    private static final int TEIL = 8 * 1024 * 1024;

    @Test
    void exportBleibtImHeapZiel() throws Exception {
        Process prozess = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-XX:+UseSerialGC", "-Xmn2m", "-Xmx" + XMX_MIB + "m", "-XX:+ExitOnOutOfMemoryError",
                "-cp", klassenpfad(), Lauf.class.getName()).redirectErrorStream(true).start();
        String ausgabe = new String(prozess.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(prozess.waitFor(3, TimeUnit.MINUTES)).isTrue();
        assertThat(prozess.exitValue()).as(ausgabe).isZero();
        assertThat(ausgabe).contains("ok");
    }

    private static String klassenpfad() {
        return Stream.of(Lauf.class, ExportSchreiber.class, S3Client.class, RequestBody.class,
                        software.amazon.awssdk.awscore.AwsRequest.class, software.amazon.awssdk.utils.IoUtils.class,
                        software.amazon.awssdk.http.ContentStreamProvider.class,
                        software.amazon.awssdk.regions.Region.class, software.amazon.awssdk.auth.signer.Aws4Signer.class,
                        software.amazon.awssdk.core.checksums.Algorithm.class, org.jboss.logging.Logger.class,
                        org.slf4j.Logger.class, software.amazon.awssdk.metrics.MetricCollector.class,
                        software.amazon.awssdk.endpoints.Endpoint.class, software.amazon.awssdk.identity.spi.Identity.class,
                        software.amazon.awssdk.http.auth.spi.scheme.AuthScheme.class,
                        software.amazon.awssdk.checksums.spi.ChecksumAlgorithm.class,
                        software.amazon.awssdk.profiles.Profile.class, org.reactivestreams.Publisher.class)
                .map(klasse -> {
                    try {
                        return Path.of(klasse.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
                    } catch (URISyntaxException e) {
                        throw new IllegalStateException(e);
                    }
                })
                .distinct()
                .collect(Collectors.joining(File.pathSeparator));
    }

    /** Kind-JVM: synthetischer Bestand → ZIP → Multipart-Upload gegen ein zählendes S3. */
    public static final class Lauf {

        private Lauf() {
        }

        public static void main(String[] args) throws Exception {
            long groesse = (long) GROESSE_MIB * 1024 * 1024;
            List<ExportBelege.Beleg> alle = new ArrayList<>();
            for (int i = 0; i < DOKUMENTE; i++) {
                MessageDigest digest = Pruefsumme.sha256();
                schreiben(i, groesse, new OutputStream() {
                    @Override
                    public void write(int b) {
                        digest.update((byte) b);
                    }

                    @Override
                    public void write(byte[] b, int off, int len) {
                        digest.update(b, off, len);
                    }
                });
                alle.add(new ExportBelege.Beleg(new UUID(0, i + 1), "QUITTUNG", LocalDate.of(2026, 1, 1 + i % 28),
                        Instant.parse("2026-01-01T08:00:00Z"), Instant.parse("2026-01-02T08:00:00Z"), "Partner " + i,
                        "Q" + i, "AUSGABE", new BigDecimal("1.00"), "CHF", List.of(new ExportBelege.Datei(
                        "archiv/2026/" + i + "/pdfa.pdf", "v", Pruefsumme.hex(digest), "pdf"))));
            }
            ExportBelege belege = new ExportBelege() {
                @Override
                public List<Beleg> seite(int jahr, Optional<UUID> nach, int anzahl) {
                    int ab = nach.map(id -> (int) id.getLeastSignificantBits()).orElse(0);
                    return alle.subList(Math.min(ab, alle.size()), Math.min(ab + anzahl, alle.size()));
                }

                @Override
                public List<Manifestdatei> manifeste(LocalDate von, LocalDate bis) {
                    return List.of();
                }
            };
            ExportSchreiber.Quelle quelle = (schluessel, version, ziel) -> {
                int i = Integer.parseInt(schluessel.split("/")[2]);
                MessageDigest digest = Pruefsumme.sha256();
                try {
                    schreiben(i, groesse, new OutputStream() {
                        @Override
                        public void write(int b) throws IOException {
                            digest.update((byte) b);
                            ziel.write(b);
                        }

                        @Override
                        public void write(byte[] b, int off, int len) throws IOException {
                            digest.update(b, off, len);
                            ziel.write(b, off, len);
                        }
                    });
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
                return new Kopie(Pruefsumme.hex(digest), groesse);
            };
            ZaehlendesS3 s3 = new ZaehlendesS3();
            S3Exportziel ziel = new S3Exportziel(s3, "archiv-test", "exporte/speicher.zip", TEIL);
            ExportSchreiber.Ergebnis ergebnis = new ExportSchreiber(belege, quelle, 5)
                    .schreiben(new Geschaeftsjahr(2026, MonthDay.of(12, 31)), ziel.strom());
            AbgelegteDatei zip = ziel.abschliessen();
            if (ergebnis.belege() != DOKUMENTE || zip.groesse() != s3.bytes || zip.groesse() < DOKUMENTE * groesse
                    || s3.teile != ziel.teile() || s3.teile < DOKUMENTE * GROESSE_MIB / 8) {
                throw new IllegalStateException("unerwartet: " + ergebnis + ", " + zip.groesse() + " Bytes, "
                        + s3.teile + " Teile");
            }
            System.out.println("ok " + zip.groesse() + " Bytes in " + s3.teile + " Teilen");
        }

        /** Deterministische Zufallsbytes des Belegs {@code i}, in Blöcken von 64 KiB, nie ganz im Speicher. */
        private static void schreiben(int i, long groesse, OutputStream aus) throws IOException {
            SplittableRandom zufall = new SplittableRandom(i);
            byte[] block = new byte[64 * 1024];
            for (long rest = groesse; rest > 0; rest -= block.length) {
                zufall.nextBytes(block);
                aus.write(block, 0, (int) Math.min(block.length, rest));
            }
        }
    }

    /** Nimmt Multipart-Uploads an, liest jeden Teil einmal und verwirft ihn. */
    static final class ZaehlendesS3 implements S3Client {
        long bytes;
        int teile;

        @Override
        public CreateMultipartUploadResponse createMultipartUpload(CreateMultipartUploadRequest anfrage) {
            return CreateMultipartUploadResponse.builder().uploadId("u").build();
        }

        @Override
        public UploadPartResponse uploadPart(UploadPartRequest anfrage, RequestBody inhalt) {
            try (InputStream ein = inhalt.contentStreamProvider().newStream()) {
                bytes += ein.transferTo(OutputStream.nullOutputStream());
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            teile++;
            return UploadPartResponse.builder().eTag("e" + teile).checksumCRC32("c").build();
        }

        @Override
        public CompleteMultipartUploadResponse completeMultipartUpload(CompleteMultipartUploadRequest anfrage) {
            if (anfrage.multipartUpload().parts().size() != teile) {
                throw new IllegalStateException("Teile fehlen");
            }
            return CompleteMultipartUploadResponse.builder().versionId("v1").build();
        }

        @Override
        public AbortMultipartUploadResponse abortMultipartUpload(AbortMultipartUploadRequest anfrage) {
            return AbortMultipartUploadResponse.builder().build();
        }

        @Override
        public String serviceName() {
            return "s3";
        }

        @Override
        public void close() {
        }
    }
}
