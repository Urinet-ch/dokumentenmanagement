package ch.urinet.archiv.gemeinsam.domain;

/**
 * 429 {@link Fehlercode#ZU_VIELE_ANFRAGEN} mit {@code Retry-After} in Sekunden (design.md «Error Handling»); die
 * REST-Schicht setzt den Header aus {@link #wartezeitSekunden()} und die Erweiterung {@code grund} aus {@link #grund()}.
 */
public class ZuVieleAnfragen extends FachFehler {

    /** Warum abgelehnt wird; die Oberfläche unterscheidet beide Fälle am Feld {@code grund} der Problem Details. */
    public enum Grund {
        /** Stundenlimit der Gespräche erreicht. */
        STUNDENLIMIT,
        /** Ein Gespräch des Benutzers (oder die Höchstzahl der Instanz) läuft noch. */
        LAEUFT,
        /** Grenze je Webseiten-Schlüssel (Spec 03, 11.11): Anfragen insgesamt. */
        ANFRAGEN,
        /** Grenze je Webseiten-Schlüssel: die teure Suche (Embedding und Reranker). */
        SUCHE,
        /** Grenze je Erfassungs-Schlüssel: Uploads je Stunde. */
        UPLOADS,
        /** Neue oder nicht im Prüf-Cache enthaltene API-Token-Prüfungen je vertrauenswürdiger Quelladresse. */
        SCHLUESSELPRUEFUNG,
        /** Grenze für Verwaltungsaktionen (Spec 06, 6.6). */
        VERWALTUNG
    }

    private final Grund grund;
    private final long wartezeitSekunden;

    public ZuVieleAnfragen(Grund grund, long wartezeitSekunden) {
        super(Fehlercode.ZU_VIELE_ANFRAGEN, Fehlercode.ZU_VIELE_ANFRAGEN.name());
        if (wartezeitSekunden < 1) {
            throw new IllegalArgumentException("Wartezeit unter 1 Sekunde");
        }
        this.grund = java.util.Objects.requireNonNull(grund);
        this.wartezeitSekunden = wartezeitSekunden;
    }

    public Grund grund() {
        return grund;
    }

    public long wartezeitSekunden() {
        return wartezeitSekunden;
    }
}
