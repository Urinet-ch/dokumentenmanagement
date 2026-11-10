package ch.urinet.archiv.mandant.application;

/** Fachlicher Fehler beim Anlegen oder Migrieren eines Mandanten; die Meldung nennt nur Codes. */
public class MandantFehler extends RuntimeException {

    public enum Grund { UNGUELTIG, MANDANT_EXISTIERT, SEED_FEHLT, SEED_UNGUELTIG, SCHEMA_FEHLT, SCHEMA_ABWEICHUNG }

    private final Grund grund;

    public MandantFehler(Grund grund, String meldung) {
        super(meldung);
        this.grund = grund;
    }

    public MandantFehler(Grund grund, String meldung, Throwable ursache) {
        super(meldung, ursache);
        this.grund = grund;
    }

    public Grund grund() {
        return grund;
    }
}
