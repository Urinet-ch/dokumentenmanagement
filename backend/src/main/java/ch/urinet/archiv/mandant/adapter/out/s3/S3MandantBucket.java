package ch.urinet.archiv.mandant.adapter.out.s3;

import ch.urinet.archiv.mandant.application.MandantBucket;
import jakarta.enterprise.context.ApplicationScoped;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.BucketAlreadyOwnedByYouException;
import software.amazon.awssdk.services.s3.model.BucketVersioningStatus;
import software.amazon.awssdk.services.s3.model.ObjectLockEnabled;

/**
 * Bucket mit Object Lock und Versionierung, ohne Standard-Aufbewahrung (sonst wäre {@code eingang/} gesperrt), und
 * Bucket Policy mit Deny auf {@code s3:BypassGovernanceRetention} (design.md «Ablage», entschieden 09.11.2026).
 */
@ApplicationScoped
public class S3MandantBucket implements MandantBucket {

    private final S3Client s3;

    public S3MandantBucket(S3Client s3) {
        this.s3 = s3;
    }

    @Override
    public void einrichten(String bucket) {
        try {
            s3.createBucket(b -> b.bucket(bucket).objectLockEnabledForBucket(true));
        } catch (BucketAlreadyOwnedByYouException e) {
            // erneuter Lauf nach einem Abbruch: die Prüfung unten entscheidet
        }
        var sperre = s3.getObjectLockConfiguration(b -> b.bucket(bucket)).objectLockConfiguration();
        if (sperre == null || sperre.objectLockEnabled() != ObjectLockEnabled.ENABLED) {
            throw new IllegalStateException("Bucket " + bucket + " ohne Object Lock");
        }
        // eine Standard-Aufbewahrung sperrte auch eingang/
        if (sperre.rule() != null) {
            throw new IllegalStateException("Bucket " + bucket + " hat eine Standard-Aufbewahrung");
        }
        s3.putBucketVersioning(b -> b.bucket(bucket).versioningConfiguration(v -> v.status(BucketVersioningStatus.ENABLED)));
        s3.putBucketPolicy(b -> b.bucket(bucket).policy(policy(bucket)));
    }

    static String policy(String bucket) {
        return """
                {"Version":"2012-10-17","Statement":[{"Sid":"KeinBypassGovernance","Effect":"Deny","Principal":"*",\
                "Action":"s3:BypassGovernanceRetention","Resource":"arn:aws:s3:::%s/*"}]}""".formatted(bucket);
    }
}
