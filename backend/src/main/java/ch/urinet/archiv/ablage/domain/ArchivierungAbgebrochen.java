package ch.urinet.archiv.ablage.domain;

/**
 * Abweichende Prüfsumme oder nicht gesetzte Sperre (Req 6.4): sofort {@code ARCHIVIERUNG_FEHLER} ohne weiteren Versuch.
 * Netz- und 5xx-Fehler sind keine solche Ausnahme, sie wiederholt die Auftragsregel. Die Meldung nennt nur Schlüssel.
 */
public class ArchivierungAbgebrochen extends RuntimeException {

    public enum Grund {
        PRUEFSUMME,
        SPERRE
    }

    private final Grund grund;
    private final String schluessel;

    public ArchivierungAbgebrochen(Grund grund, String schluessel) {
        super("Archivierung abgebrochen (" + grund + "): " + schluessel);
        this.grund = grund;
        this.schluessel = schluessel;
    }

    public ArchivierungAbgebrochen(Grund grund, String schluessel, Throwable ursache) {
        super("Archivierung abgebrochen (" + grund + "): " + schluessel, ursache);
        this.grund = grund;
        this.schluessel = schluessel;
    }

    public Grund grund() {
        return grund;
    }

    public String schluessel() {
        return schluessel;
    }
}
