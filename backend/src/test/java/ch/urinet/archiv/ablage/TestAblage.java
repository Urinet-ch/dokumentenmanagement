package ch.urinet.archiv.ablage;

import org.eclipse.microprofile.config.ConfigProvider;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.LifecycleRule;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.ObjectLockRetention;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Lesender Blick auf den Bucket eines Mandanten in MinIO für Szenarien ausserhalb von {@code ablage} (Aufgabe 2.4):
 * Versionen unter einem Präfix zählen und eine Version lesen. Nutzt den Verwaltungs-Client ohne Interceptor, damit
 * {@link S3Stoerung} ihn nicht trifft.
 */
public final class TestAblage {

    private TestAblage() {
    }

    public static String bucket(String mandant) {
        return ConfigProvider.getConfig().getValue("archiv.ablage.bucket-praefix", String.class) + mandant;
    }

    /** Alle Versionen (ohne Löschmarken) unter dem Präfix als {@code key@version}. */
    public static List<String> versionen(String mandant, String praefix) {
        List<String> versionen = new ArrayList<>();
        try (S3Client s3 = TestBucket.verwaltung()) {
            for (var seite : s3.listObjectVersionsPaginator(b -> b.bucket(bucket(mandant)).prefix(praefix))) {
                seite.versions().forEach(v -> versionen.add(v.key() + "@" + v.versionId()));
            }
        }
        return versionen;
    }

    /** Löscht alle Versionen und Löschmarken unter dem Präfix, deren Schlüssel die Bedingung erfüllt. */
    public static void loeschen(String mandant, String praefix, Predicate<String> schluessel) {
        try (S3Client s3 = TestBucket.verwaltung()) {
            for (var seite : s3.listObjectVersionsPaginator(b -> b.bucket(bucket(mandant)).prefix(praefix))) {
                seite.versions().stream().filter(v -> schluessel.test(v.key())).forEach(v -> s3.deleteObject(
                        b -> b.bucket(bucket(mandant)).key(v.key()).versionId(v.versionId())));
                seite.deleteMarkers().stream().filter(m -> schluessel.test(m.key())).forEach(m -> s3.deleteObject(
                        b -> b.bucket(bucket(mandant)).key(m.key()).versionId(m.versionId())));
            }
        }
    }

    /** Legt unter dem Schlüssel eine neue Version ab; die bisherigen Versionen bleiben erhalten. */
    public static void neueVersion(String mandant, String schluessel, String inhalt) {
        try (S3Client s3 = TestBucket.verwaltung()) {
            s3.putObject(b -> b.bucket(bucket(mandant)).key(schluessel), RequestBody.fromString(inhalt));
        }
    }

    /** Object-Lock-Sperre einer Version (Modus und Ablauf). */
    public static ObjectLockRetention sperre(String mandant, String schluessel, String versionId) {
        try (S3Client s3 = TestBucket.verwaltung()) {
            return s3.getObjectRetention(b -> b.bucket(bucket(mandant)).key(schluessel).versionId(versionId))
                    .retention();
        }
    }

    public static byte[] lesen(String mandant, String schluessel, String versionId) {
        try (S3Client s3 = TestBucket.verwaltung()) {
            ResponseBytes<GetObjectResponse> inhalt = s3.getObjectAsBytes(b -> b.bucket(bucket(mandant))
                    .key(schluessel).versionId(versionId));
            return inhalt.asByteArray();
        }
    }

    /** Legt eine neue Version ohne Sperre ab und liefert ihre Versions-ID (synthetische Testdaten). */
    public static String ablegen(String mandant, String schluessel, byte[] inhalt) {
        try (S3Client s3 = TestBucket.verwaltung()) {
            return s3.putObject(b -> b.bucket(bucket(mandant)).key(schluessel), RequestBody.fromBytes(inhalt))
                    .versionId();
        }
    }

    /** Regeln des Lebenszyklus des Buckets; leer ohne Konfiguration. */
    public static List<LifecycleRule> lebenszyklus(String mandant) {
        try (S3Client s3 = TestBucket.verwaltung()) {
            return s3.getBucketLifecycleConfiguration(b -> b.bucket(bucket(mandant))).rules();
        } catch (S3Exception e) {
            if (e.awsErrorDetails() != null && "NoSuchLifecycleConfiguration".equals(e.awsErrorDetails().errorCode())) {
                return List.of();
            }
            throw e;
        }
    }

    /** Ob die Version eine Object-Lock-Sperre trägt (Modus oder Datum); ohne Sperre antwortet S3 mit einem Fehler. */
    public static boolean gesperrt(String mandant, String schluessel, String versionId) {
        try (S3Client s3 = TestBucket.verwaltung()) {
            var r = s3.getObjectRetention(b -> b.bucket(bucket(mandant)).key(schluessel).versionId(versionId))
                    .retention();
            return r != null && (r.mode() != null || r.retainUntilDate() != null);
        } catch (S3Exception e) {
            return false;
        }
    }
}
