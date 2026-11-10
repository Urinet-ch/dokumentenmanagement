package ch.urinet.archiv.mandant.application;

/**
 * Driven Port: Bucket eines Mandanten mit Object Lock und Versionierung, ohne Standard-Aufbewahrung, mit Bucket Policy
 * (Deny auf {@code s3:BypassGovernanceRetention}) als Schutz gegen Versehen (design.md «Ablage»).
 */
public interface MandantBucket {

    void einrichten(String bucket);
}
