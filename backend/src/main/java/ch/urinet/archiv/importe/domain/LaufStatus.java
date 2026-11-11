package ch.urinet.archiv.importe.domain;

/** Status eines Import-Laufs ({@code import_lauf.status}, design.md «Data Models»). */
public enum LaufStatus {
    VORSCHAU_AUSSTEHEND,
    ZUORDNUNG_NOETIG,
    VORSCHAU,
    UEBERNOMMEN,
    ABGEBROCHEN
}
