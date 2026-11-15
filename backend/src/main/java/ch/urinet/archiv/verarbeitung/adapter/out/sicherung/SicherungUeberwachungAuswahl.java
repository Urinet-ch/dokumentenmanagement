package ch.urinet.archiv.verarbeitung.adapter.out.sicherung;

import ch.urinet.archiv.gemeinsam.domain.PortAuswahl;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.Produces;

import java.util.Optional;

/** Meldet der Startprüfung, dass der Sicherungs-Port technisch angebunden ist. */
@ApplicationScoped
public class SicherungUeberwachungAuswahl {

    @Produces
    @Dependent
    public PortAuswahl auswahl() {
        return new PortAuswahl("SicherungUeberwachung", false, true, Optional.empty());
    }
}
