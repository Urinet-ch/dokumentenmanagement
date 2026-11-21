package ch.urinet.archiv.benutzer.application;

/** Mandantenübergreifende Namenssperre. */
public interface BenutzerNamen {
    boolean vergeben(String benutzername);

    void sperren(String benutzername);

    void vorMandantenabbauSichern(String schema);
}
