package ch.urinet.archiv.integration.domain.webseite;

/**
 * Art einer Änderung im Lese-Feed (Spec 03, Req 14.1), gesehen mit der Stufe des Schlüssels: {@code SICHTBAR} (vorher
 * nicht sichtbar, nachher sichtbar), {@code GEAENDERT} (vorher und nachher sichtbar) und {@code NICHT_SICHTBAR} (vorher
 * sichtbar, nachher nicht, auch wenn das Dokument nun eine Stufe über dem Schlüssel hat). Der Code ist der Wert im
 * Vertrag ({@code x-erweiterbar}).
 */
public enum AenderungArt {
    SICHTBAR, GEAENDERT, NICHT_SICHTBAR
}
