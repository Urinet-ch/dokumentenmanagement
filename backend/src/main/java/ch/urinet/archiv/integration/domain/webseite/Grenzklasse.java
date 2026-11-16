package ch.urinet.archiv.integration.domain.webseite;

/**
 * Art einer Anfrage für die Grenzen je Schlüssel (Spec 03, Req 15.1): {@link #LESEN} jede lesende Anfrage,
 * {@link #SUCHE} zusätzlich die teure Suche (Embedding und Reranker), {@link #ERFASSUNG} jede Anfrage eines
 * Erfassungs-Schlüssels, {@link #UPLOAD} zusätzlich ein Upload.
 */
public enum Grenzklasse {
    LESEN, SUCHE, ERFASSUNG, UPLOAD
}
