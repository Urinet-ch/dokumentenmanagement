package ch.urinet.archiv.benutzer.adapter.out.stepup;

import ch.urinet.archiv.benutzer.application.StepUp;
import ch.urinet.archiv.gemeinsam.domain.StepUpNoetig;

/** Testadapter für Fälle, in denen eine geschützte Aktion weiterhin abgewiesen werden soll. */
public class StepUpAusstehend implements StepUp {
    @Override
    public void pruefen() {
        throw new StepUpNoetig(300);
    }
}
