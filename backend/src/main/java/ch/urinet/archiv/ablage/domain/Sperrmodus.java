package ch.urinet.archiv.ablage.domain;

/** Object-Lock-Modus: GOVERNANCE für {@code archiv/}, COMPLIANCE für {@code manifest/} (ADR-007, Option B). */
public enum Sperrmodus {
    GOVERNANCE,
    COMPLIANCE
}
