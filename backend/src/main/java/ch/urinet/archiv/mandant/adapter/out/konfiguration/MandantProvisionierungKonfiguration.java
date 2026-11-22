package ch.urinet.archiv.mandant.adapter.out.konfiguration;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

import java.util.List;
import java.util.Map;

@ConfigMapping(prefix = "archiv.provisionierung")
public interface MandantProvisionierungKonfiguration {

    Map<String, Eintrag> mandanten();

    interface Eintrag {
        @WithDefault("inhaber,mitarbeiter,treuhaender")
        List<String> verwaltungsRollen();
    }
}
