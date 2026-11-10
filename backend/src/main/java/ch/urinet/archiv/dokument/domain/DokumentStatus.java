package ch.urinet.archiv.dokument.domain;

import java.util.Set;

/** Lebenslauf eines Dokuments (design.md «Data Models», structure.md «Namen»); als Text gespeichert. */
public enum DokumentStatus {
    NEU,
    VERARBEITUNG,
    EINGANGSKORB,
    ARCHIVIERUNG,
    ARCHIVIERUNG_FEHLER,
    ARCHIVIERT,
    FEHLER,
    VERWORFEN;

    /** Status im Eingangskorb (design.md «Dokument», {@code GET /eingangskorb}). */
    public static final Set<DokumentStatus> EINGANGSKORB_STATUS = Set.of(EINGANGSKORB, FEHLER, ARCHIVIERUNG_FEHLER);
}
