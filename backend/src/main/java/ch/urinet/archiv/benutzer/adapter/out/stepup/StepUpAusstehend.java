package ch.urinet.archiv.benutzer.adapter.out.stepup;

import ch.urinet.archiv.benutzer.application.StepUp;
import ch.urinet.archiv.gemeinsam.domain.StepUpNoetig;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;

/** Bis zur Umsetzung von 19.10 sind Aktionen mit Step-up nicht freigegeben. */
@DefaultBean
@ApplicationScoped
public class StepUpAusstehend implements StepUp {
    @Override
    public void pruefen() {
        throw new StepUpNoetig(300);
    }
}
