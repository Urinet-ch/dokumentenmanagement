package ch.urinet.archiv.mandant.adapter.out.konfiguration;

import ch.urinet.archiv.mandant.application.BrandingVerzeichnis;
import ch.urinet.archiv.mandant.domain.Branding;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

@ApplicationScoped
public class KonfiguriertesBrandingVerzeichnis implements BrandingVerzeichnis {

    private final Map<String, Branding> brandings;

    public KonfiguriertesBrandingVerzeichnis(BrandingKonfiguration einstellungen) {
        Map<String, Branding> domains = new HashMap<>();
        for (BrandingKonfiguration.Eintrag mandant : einstellungen.mandanten().values()) {
            String konfiguriert = mandant.domain().orElse("");
            if (konfiguriert.isBlank()) {
                continue;
            }
            String domain = Branding.domain(konfiguriert)
                    .orElseThrow(() -> new IllegalStateException("Ungültige Branding-Domain in der Konfiguration"));
            Branding branding = Branding.ausKonfiguration(mandant.name(), mandant.logoUrl().orElse(null),
                    mandant.primaerfarbe(), mandant.sprache());
            if (domains.putIfAbsent(domain, branding) != null) {
                throw new IllegalStateException("Branding-Domain ist mehrfach konfiguriert");
            }
        }
        this.brandings = Map.copyOf(domains);
    }

    @Override
    public Optional<Branding> fuerDomain(String domain) {
        return Optional.ofNullable(brandings.get(domain));
    }
}
