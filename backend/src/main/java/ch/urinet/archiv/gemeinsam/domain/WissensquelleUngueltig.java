package ch.urinet.archiv.gemeinsam.domain;

public final class WissensquelleUngueltig extends FachFehler {
    public enum Grund { NICHT_ARCHIVIERT, PERSONAL, KEIN_VOLLTEXT, KEINE_PDFA }

    private final Grund grund;

    public WissensquelleUngueltig(Grund grund) {
        super(Fehlercode.KEINE_WISSENSQUELLE);
        this.grund = java.util.Objects.requireNonNull(grund);
    }

    public Grund grund() {
        return grund;
    }
}
