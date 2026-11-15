package ch.urinet.archiv.verarbeitung.application;

/** Driven Port: veröffentlicht die Auslastung eines Speicherbereichs als Kennzahl, ohne Mandanten (Req 16.4). */
public interface SpeicherKennzahl {

    void setzen(String pool, double prozent);
}
