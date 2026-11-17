package ch.urinet.archiv.mandant.application;

import ch.urinet.archiv.mandant.domain.Branding;

import java.util.Optional;

/** Branding-Abfrage über eine registrierte Domain. */
public interface BrandingVerzeichnis {

    Optional<Branding> fuerDomain(String domain);
}
