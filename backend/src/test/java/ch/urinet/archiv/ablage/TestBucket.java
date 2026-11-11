package ch.urinet.archiv.ablage;

import ch.urinet.archiv.mandant.domain.MandantCode;
import org.eclipse.microprofile.config.ConfigProvider;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.BucketVersioningStatus;

import java.net.URI;
import java.util.UUID;

/**
 * Test-Buckets in MinIO (Compose Dev Services) mit Object Lock und Versionierung, ohne Bucket Policy (sonst griffe
 * deren Deny auf den Bypass, T 6c). Der Verwaltungs-Client hat keinen Interceptor: Nur er sendet in Tests den
 * Bypass-Header (Gegenprobe zu COMPLIANCE, Aufräumen von GOVERNANCE-Sperren).
 */
final class TestBucket {

    static final String PRAEFIX = "archiv-test-";

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

    /** S3-Client ohne Interceptor mit dem Zugang von MinIO, nur für Tests. */
    static S3Client verwaltung() {
        var config = ConfigProvider.getConfig();
        return S3Client.builder()
                .endpointOverride(URI.create(config.getValue("quarkus.s3.endpoint-override", String.class)))
                .region(Region.of(config.getValue("quarkus.s3.aws.region", String.class)))
                .forcePathStyle(true)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(
                        config.getValue("quarkus.s3.aws.credentials.static-provider.access-key-id", String.class),
                        config.getValue("quarkus.s3.aws.credentials.static-provider.secret-access-key", String.class))))
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .build();
    }

    /** Löscht, was sich löschen lässt (GOVERNANCE mit Bypass); COMPLIANCE-Versionen bleiben bis zum Ablauf. */
    static void aufraeumen(S3Client verwaltung, MandantCode code) {
        aufraeumen(verwaltung, bucket(code));
    }

    static void aufraeumen(S3Client verwaltung, String bucket) {
        try {
            for (var seite : verwaltung.listObjectVersionsPaginator(b -> b.bucket(bucket))) {
                seite.versions().forEach(v -> loeschen(verwaltung, bucket, v.key(), v.versionId()));
                seite.deleteMarkers().forEach(m -> loeschen(verwaltung, bucket, m.key(), m.versionId()));
            }
            verwaltung.deleteBucket(b -> b.bucket(bucket));
        } catch (RuntimeException e) {
            // COMPLIANCE-Versionen: Der Bucket bleibt im Wegwerf-Container bis zum Ablauf der kurzen Testfrist
        }
    }

    private static void loeschen(S3Client verwaltung, String bucket, String key, String versionId) {
        try {
            verwaltung.deleteObject(b -> b.bucket(bucket).key(key).versionId(versionId).bypassGovernanceRetention(true));
        } catch (RuntimeException e) {
            // gesperrt in COMPLIANCE
        }
    }
}
