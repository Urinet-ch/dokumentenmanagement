package ch.urinet.archiv.mandant.adapter.out.konfiguration;

import ch.urinet.archiv.mandant.application.MandantVerwaltungsrollenSeed;
import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.mandant.domain.MandantVerwaltungsrollen;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class KonfigurierterMandantVerwaltungsrollenSeed implements MandantVerwaltungsrollenSeed {

    private final MandantProvisionierungKonfiguration konfiguration;

    public KonfigurierterMandantVerwaltungsrollenSeed(MandantProvisionierungKonfiguration konfiguration) {
        this.konfiguration = konfiguration;
    }

    @Override
    public MandantVerwaltungsrollen fuer(MandantCode code) {
        MandantProvisionierungKonfiguration.Eintrag eintrag = konfiguration.mandanten().get(code.wert());
        return eintrag == null ? MandantVerwaltungsrollen.STANDARD
                : new MandantVerwaltungsrollen(eintrag.verwaltungsRollen());
    }
}
