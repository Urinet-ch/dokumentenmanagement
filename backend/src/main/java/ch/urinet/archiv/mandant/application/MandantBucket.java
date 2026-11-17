package ch.urinet.archiv.mandant.application;

/**
 * Driven Port: Bucket eines Mandanten mit Object Lock und Versionierung, ohne Standard-Aufbewahrung, mit Bucket Policy
 * (Deny auf {@code s3:BypassGovernanceRetention}) als Schutz gegen Versehen (design.md «Ablage»).
 */
public interface MandantBucket {

    void einrichten(String bucket);

    /** Löscht nur einen leeren Mandanten-Bucket. {@code false}, falls noch Versionen oder Löschmarken enthalten sind. */
    boolean entfernenWennLeer(String bucket);
}
