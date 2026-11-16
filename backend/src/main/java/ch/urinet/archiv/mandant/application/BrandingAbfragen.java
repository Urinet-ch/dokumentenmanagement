package ch.urinet.archiv.mandant.application;

import ch.urinet.archiv.mandant.domain.Branding;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class BrandingAbfragen {

    private final BrandingVerzeichnis verzeichnis;

    public BrandingAbfragen(BrandingVerzeichnis verzeichnis) {
        this.verzeichnis = verzeichnis;
    }

    public Branding fuerDomain(String domain) {
        return verzeichnis.fuerDomain(Branding.validierteDomain(domain)).orElse(Branding.STANDARD);
    }
}
