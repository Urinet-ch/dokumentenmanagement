package ch.urinet.archiv.verarbeitung.domain;

import java.util.Objects;

/**
 * Die Verarbeitung ist gescheitert; der Auftrag zählt das als Fehlversuch (Req 5.4, 5.6). Die Meldung nennt nur Art,
 * Werkzeug und Exit-Code, nie Inhalte oder die Ausgabe der Werkzeuge (Req 16.7).
 */
public class OcrFehlgeschlagen extends RuntimeException {

    public enum Art {
        /** Das Zeitbudget des Laufs ist abgelaufen; der Prozess wurde beendet (Req 5.6). */
        ZEITUEBERSCHREITUNG,
        /** Ein Werkzeug endete mit einem Fehler. */
        WERKZEUG,
        /** Lesen oder Schreiben im Arbeitsverzeichnis ist gescheitert. */
        EIN_AUSGABE
    }

    private final Art art;

    public OcrFehlgeschlagen(Art art, String meldung) {
        super(meldung);
        this.art = Objects.requireNonNull(art, "art");
    }

    public OcrFehlgeschlagen(Art art, String meldung, Throwable ursache) {
        super(meldung, ursache);
        this.art = Objects.requireNonNull(art, "art");
    }

    public Art art() {
        return art;
    }
}
