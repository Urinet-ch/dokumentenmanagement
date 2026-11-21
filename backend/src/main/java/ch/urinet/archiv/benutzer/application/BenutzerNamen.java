package ch.urinet.archiv.benutzer.application;

/** Namenssperre des Benutzerverzeichnisses des aktuellen Mandanten. */
public interface BenutzerNamen {
    boolean vergeben(String benutzername);
}
