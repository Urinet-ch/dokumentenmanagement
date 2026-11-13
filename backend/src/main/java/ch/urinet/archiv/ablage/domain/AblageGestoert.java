package ch.urinet.archiv.ablage.domain;

/**
 * Der Object Storage ist vorübergehend gestört (Netz, Zeitüberschreitung des SDK, 5xx). Ein späterer Versuch kann
 * gelingen; der Prüflauf wiederholt darum den Teil, statt die Datei als Abweichung zu melden (Req 15.5). Die Meldung
 * nennt nur Schlüssel, die Ursache bleibt angehängt.
 */
public class AblageGestoert extends RuntimeException {

    public AblageGestoert(String schluessel, Throwable ursache) {
        super("Object Storage vorübergehend gestört: " + schluessel, ursache);
    }
}
