package ch.urinet.archiv.mandant.application;

import ch.urinet.archiv.mandant.domain.Branding;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class BrandingAbfragen {

    private final BrandingVerzeichnis verzeichnis;
    private final MandantEinstellungen einstellungen;

    public BrandingAbfragen(BrandingVerzeichnis verzeichnis, MandantEinstellungen einstellungen) {
        this.verzeichnis = verzeichnis;
        this.einstellungen = einstellungen;
    }

    public Branding fuerDomain(String domain) {
        String gueltig = Branding.validierteDomain(domain);
        Branding standard = verzeichnis.fuerDomain(gueltig).orElse(Branding.STANDARD);
        return verzeichnis.mandantFuerDomain(gueltig)
                .flatMap(einstellungen::oeffentlichesBranding)
                .map(werte -> Branding.ausKonfiguration(
                        werte.name() == null ? standard.name() : werte.name(), standard.logoUrl(),
                        werte.primaerfarbe() == null ? standard.primaerfarbe() : werte.primaerfarbe(),
                        standard.sprache()))
                .orElse(standard);
    }
}
