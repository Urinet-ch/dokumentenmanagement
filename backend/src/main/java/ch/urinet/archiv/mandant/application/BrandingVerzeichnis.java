package ch.urinet.archiv.mandant.application;

import ch.urinet.archiv.mandant.domain.Branding;
import ch.urinet.archiv.mandant.domain.MandantCode;

import java.util.Optional;

/** Branding-Abfrage über eine registrierte Domain. */
public interface BrandingVerzeichnis {

    Optional<Branding> fuerDomain(String domain);

    Optional<Branding> fuerMandant(MandantCode mandant);
}
