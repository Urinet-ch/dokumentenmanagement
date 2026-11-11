package ch.urinet.archiv.gemeinsam.domain;

import java.util.List;

/**
 * 422 {@code PFLICHTFELD_FEHLT} mit den Namen der fehlenden Pflichtfelder (Req 8.3); die Antwort nennt sie in der
 * Erweiterung {@code felder}. Nur Feldnamen, nie Werte.
 */
public class PflichtfelderFehlen extends FachFehler {

    private final List<String> felder;

    public PflichtfelderFehlen(List<String> felder) {
        super(Fehlercode.PFLICHTFELD_FEHLT);
        this.felder = List.copyOf(felder);
    }

    public List<String> felder() {
        return felder;
    }
}
