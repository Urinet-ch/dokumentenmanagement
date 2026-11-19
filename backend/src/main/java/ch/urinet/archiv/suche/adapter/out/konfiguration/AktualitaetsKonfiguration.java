package ch.urinet.archiv.suche.adapter.out.konfiguration;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

import java.util.Map;

@ConfigMapping(prefix = "archiv.suche.aktualitaet")
public interface AktualitaetsKonfiguration {

    Map<String, Mandant> mandanten();

    interface Mandant {

        @WithDefault("false")
        boolean aktiviert();

        @WithDefault("0.05")
        double faktor();

        @WithDefault("365")
        int halbwertzeitTage();
    }
}
