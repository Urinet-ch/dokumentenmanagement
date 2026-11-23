package ch.urinet.archiv.mandant.adapter.out.konfiguration;

import ch.urinet.archiv.mandant.application.BrandingVerzeichnis;
import ch.urinet.archiv.mandant.domain.Branding;
import ch.urinet.archiv.mandant.domain.MandantCode;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

@ApplicationScoped
public class KonfiguriertesBrandingVerzeichnis implements BrandingVerzeichnis {

    private final Map<String, Branding> brandings;
    private final Map<String, Branding> mandanten;

    public KonfiguriertesBrandingVerzeichnis(BrandingKonfiguration einstellungen) {
        Map<String, Branding> domains = new HashMap<>();
        Map<String, Branding> nachMandant = new HashMap<>();
        for (Map.Entry<String, BrandingKonfiguration.Eintrag> eintrag : einstellungen.mandanten().entrySet()) {
            BrandingKonfiguration.Eintrag mandant = eintrag.getValue();
            Branding branding = Branding.ausKonfiguration(mandant.name(), mandant.logoUrl().orElse(null),
                    mandant.primaerfarbe(), mandant.sprache());
            nachMandant.put(eintrag.getKey(), branding);
            String konfiguriert = mandant.domain().orElse("");
            if (konfiguriert.isBlank()) {
                continue;
            }
            String domain = Branding.domain(konfiguriert)
                    .orElseThrow(() -> new IllegalStateException("Ungültige Branding-Domain in der Konfiguration"));
            if (domains.putIfAbsent(domain, branding) != null) {
                throw new IllegalStateException("Branding-Domain ist mehrfach konfiguriert");
            }
        }
        this.brandings = Map.copyOf(domains);
        this.mandanten = Map.copyOf(nachMandant);
    }

    @Override
    public Optional<Branding> fuerDomain(String domain) {
        return Optional.ofNullable(brandings.get(domain));
    }

    @Override
    public Optional<Branding> fuerMandant(MandantCode mandant) {
        return Optional.ofNullable(mandanten.get(mandant.wert()));
    }
}
