package ch.urinet.archiv.ki.application.chat;

import java.util.List;

/**
 * Ein Werkzeug kann nicht antworten; der Adapter gibt dem Modell {@code {"fehler": meldung}} zurück, nie eine Ausnahme
 * (Spec 02 design.md «Error Handling»). Die Meldung nennt höchstens den Namen eines Parameters, nie seinen Wert, und
 * kommt so auch ins Log (tech.md, Logging). {@code arten} listet bei einer unbekannten Art die aktiven Codes.
 */
public class WerkzeugFehler extends RuntimeException {

    public static final String KEIN_KONTEXT = "kein Kontext";
    public static final String NICHT_VERFUEGBAR = "Werkzeug nicht verfügbar";
    public static final String NICHT_GEFUNDEN = "nicht gefunden";
    public static final String UNBEKANNTE_ART = "unbekannte Art";

    private final transient List<String> arten;

    public WerkzeugFehler(String meldung) {
        this(meldung, List.of());
    }

    public WerkzeugFehler(String meldung, List<String> arten) {
        super(meldung, null, false, false);
        this.arten = List.copyOf(arten);
    }

    /** Ungültiger Parameter: nur sein Name. */
    public static WerkzeugFehler ungueltig(String parameter) {
        return new WerkzeugFehler("ungültiger Parameter: " + parameter);
    }

    public List<String> arten() {
        return arten;
    }
}
