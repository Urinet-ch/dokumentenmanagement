package ch.urinet.archiv.sicherung.application;

/** Ungültige Kombination von Sicherungsart und Mandant. */
public class SicherungExportFehler extends RuntimeException {

    public SicherungExportFehler(String meldung) {
        super(meldung);
    }
}
