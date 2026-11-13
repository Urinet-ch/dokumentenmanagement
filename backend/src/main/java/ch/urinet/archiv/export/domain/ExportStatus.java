package ch.urinet.archiv.export.domain;

/**
 * Status eines Jahresexports in {@code export} (design.md «Jahresexport»): {@code OFFEN} bis der Auftrag
 * {@code EXPORT_JAHR} das ZIP abgelegt hat, dann {@code FERTIG}; nach dem letzten Fehlversuch {@code FEHLER}.
 * «Abgelaufen» ist kein gespeicherter Status, sondern folgt aus {@link Exportfrist}.
 */
public enum ExportStatus {
    OFFEN, FERTIG, FEHLER
}
