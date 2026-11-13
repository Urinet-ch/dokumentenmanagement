package ch.urinet.archiv.ablage;

import ch.urinet.archiv.mandant.domain.MandantCode;
import org.eclipse.microprofile.config.ConfigProvider;
import org.jboss.logging.Logger;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.interceptor.ExecutionInterceptor;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.BucketVersioningStatus;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.net.URI;
import java.util.Arrays;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Test-Buckets in MinIO (Compose Dev Services) oder Ceph RGW (Profil {@code ceph}) mit Object Lock und Versionierung,
 * ohne Bucket Policy (sonst griffe deren Deny auf den Bypass, T 6c; MinIO wendet sie auf den Root-Zugang nicht an, ein
 * Test darf sich darauf nicht stützen). Der Verwaltungs-Client hat keinen Interceptor: Nur er sendet in Tests den
 * Bypass-Header (Gegenprobe zu COMPLIANCE, Aufräumen von GOVERNANCE-Sperren).
 */
final class TestBucket {

    static final String PRAEFIX = "archiv-test-";
    private static final Logger LOG = Logger.getLogger(TestBucket.class);

    private TestBucket() {
    }

    static MandantCode neu(S3Client s3, boolean objectLock) {
        MandantCode code = new MandantCode("t" + UUID.randomUUID().toString().replace("-", "").substring(0, 12));
        String bucket = bucket(code);
        s3.createBucket(b -> b.bucket(bucket).objectLockEnabledForBucket(objectLock));
        s3.putBucketVersioning(b -> b.bucket(bucket)
                .versioningConfiguration(v -> v.status(BucketVersioningStatus.ENABLED)));
        return code;
    }

    static String bucket(MandantCode code) {
        return code.bucket(PRAEFIX);
    }

    /** S3-Client ohne Interceptor mit dem Zugang des Test-Servers (MinIO oder Ceph RGW), nur für Tests. */
    static S3Client verwaltung() {
        return client();
    }

    /** S3-Client mit dem Zugang des Test-Servers und den angegebenen Interceptors, nur für Tests. */
    static S3Client client(ExecutionInterceptor... interceptors) {
        var config = ConfigProvider.getConfig();
        return S3Client.builder()
                .endpointOverride(URI.create(config.getValue("quarkus.s3.endpoint-override", String.class)))
                .region(Region.of(config.getValue("quarkus.s3.aws.region", String.class)))
                .forcePathStyle(true)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(
                        config.getValue("quarkus.s3.aws.credentials.static-provider.access-key-id", String.class),
                        config.getValue("quarkus.s3.aws.credentials.static-provider.secret-access-key", String.class))))
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .overrideConfiguration(o -> Arrays.stream(interceptors).forEach(o::addExecutionInterceptor))
                .build();
    }

    /**
     * Löscht, was sich löschen lässt (GOVERNANCE mit Bypass); COMPLIANCE-Versionen bleiben bis zum Ablauf der kurzen
     * Testfrist im Wegwerf-Container. Was nicht gelöscht werden kann, steht mit Fehlercode im Log (nur Bucket und Codes).
     */
    static void aufraeumen(S3Client verwaltung, MandantCode code) {
        aufraeumen(verwaltung, bucket(code));
    }

    static void aufraeumen(S3Client verwaltung, String bucket) {
        Map<String, Integer> nichtGeloescht = new TreeMap<>();
        try {
            for (var seite : verwaltung.listObjectVersionsPaginator(b -> b.bucket(bucket))) {
                seite.versions().forEach(v -> loeschen(verwaltung, bucket, v.key(), v.versionId(), nichtGeloescht));
                seite.deleteMarkers().forEach(m -> loeschen(verwaltung, bucket, m.key(), m.versionId(), nichtGeloescht));
            }
            verwaltung.deleteBucket(b -> b.bucket(bucket));
        } catch (RuntimeException e) {
            LOG.warnf("Test-Bucket %s nicht aufgeräumt (%s); nicht gelöschte Versionen je Fehler: %s", bucket,
                    fehler(e), nichtGeloescht);
        }
    }

    private static void loeschen(S3Client verwaltung, String bucket, String key, String versionId,
                                 Map<String, Integer> nichtGeloescht) {
        try {
            verwaltung.deleteObject(b -> b.bucket(bucket).key(key).versionId(versionId).bypassGovernanceRetention(true));
        } catch (RuntimeException e) {
            // erwartet für COMPLIANCE-Versionen; gemeldet mit dem Bucket in aufraeumen
            nichtGeloescht.merge(fehler(e), 1, Integer::sum);
        }
    }

    private static String fehler(RuntimeException e) {
        if (e instanceof S3Exception s3 && s3.awsErrorDetails() != null) {
            return s3.statusCode() + " " + s3.awsErrorDetails().errorCode();
        }
        return e.getClass().getSimpleName();
    }
}
