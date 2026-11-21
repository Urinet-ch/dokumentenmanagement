package ch.urinet.archiv.gemeinsam.domain;

/** Erforderliche erneute Anmeldung mit der zulässigen Höchstdauer in Sekunden. */
public final class StepUpNoetig extends FachFehler {
    private final long maxAlterSekunden;

    public StepUpNoetig(long maxAlterSekunden) {
        super(Fehlercode.STEP_UP_NOETIG);
        if (maxAlterSekunden < 1 || maxAlterSekunden > 900) {
            throw new IllegalArgumentException("Ungültige Step-up-Gültigkeit");
        }
        this.maxAlterSekunden = maxAlterSekunden;
    }

    public long maxAlterSekunden() {
        return maxAlterSekunden;
    }
}
