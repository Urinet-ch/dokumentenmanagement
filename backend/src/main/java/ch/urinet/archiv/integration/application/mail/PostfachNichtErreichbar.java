package ch.urinet.archiv.integration.application.mail;

/**
 * Abruf des Postfachs gescheitert (Verbindung, TLS, Anmeldung, Ordner). Die Meldung nennt nur die Stelle, nie Host,
 * Benutzer oder Passwort (Req 16.7); die Ursache trägt die Ausnahme der Bibliothek.
 */
public class PostfachNichtErreichbar extends RuntimeException {

    private final String stelle;

    public PostfachNichtErreichbar(String stelle, Throwable ursache) {
        super(stelle, ursache);
        this.stelle = stelle;
    }

    public String stelle() {
        return stelle;
    }
}
