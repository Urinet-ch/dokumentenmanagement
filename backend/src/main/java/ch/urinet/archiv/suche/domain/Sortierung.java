package ch.urinet.archiv.suche.domain;

/**
 * Reihenfolge der Treffer (Req 14.3): {@code RELEVANZ} setzt einen exakten Treffer der Belegnummer zuerst (Req 14.7),
 * dann den Rang {@code ts_rank_cd}; {@code DATUM} sortiert nach Belegdatum, neueste zuerst, ohne Datum zuletzt. Bei
 * Gleichstand entscheiden Eingang und ID, damit das Paging stabil ist.
 */
public enum Sortierung {
    RELEVANZ,
    DATUM
}
