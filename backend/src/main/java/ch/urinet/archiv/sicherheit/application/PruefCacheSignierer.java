package ch.urinet.archiv.sicherheit.application;

/** Driven Port: bildet ein Token-Geheimnis für den kurzlebigen Prüf-Cache mit einem geheimen Schlüssel ab. */
public interface PruefCacheSignierer {

    byte[] signieren(String geheimnis);
}
