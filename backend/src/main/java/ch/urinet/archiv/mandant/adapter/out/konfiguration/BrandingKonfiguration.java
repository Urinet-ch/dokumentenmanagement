package ch.urinet.archiv.mandant.adapter.out.konfiguration;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

import java.util.Map;
import java.util.Optional;

@ConfigMapping(prefix = "archiv.branding")
public interface BrandingKonfiguration {
    Map<String, Eintrag> mandanten();

    interface Eintrag {
        Optional<String> domain();
        String name();
        Optional<String> logoUrl();

        @WithDefault("#eeb111")
        String primaerfarbe();

        @WithDefault("de-CH")
        String sprache();
    }
}
