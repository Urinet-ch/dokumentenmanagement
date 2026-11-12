package ch.urinet.archiv.protokoll.adapter.out.tsa;

import io.quarkus.runtime.StartupEvent;
import io.smallrye.config.SmallRyeConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.ConfigProvider;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.util.Arrays;
import java.util.List;

/**
 * Startprüfung der Zeitstempeldienste in {@code prod} (Aufgabe 5.2; Req 15.3, 15.11; 4.9-E1): Ohne Dienst in
 * {@code archiv.tsa.dienste} oder mit einem unvollständig konfigurierten Dienst bricht der Start ab, weil sich ein
 * Zeitstempel nicht rückwirkend nachholen lässt; mit nur einem Dienst warnt jeder Start (Übergang nach Wegfall des
 * Hauptdiensts). Admin-Kommandos ({@code archiv.rolle=kommando}) erzeugen keine Manifeste und sind ausgenommen.
 * Stufe P (Req 16.24) gibt es noch nicht; sie kommt mit 4.9-E5 und wird dann hier ausgenommen.
 */
@ApplicationScoped
public class TsaStartpruefung {

    private static final Logger LOG = Logger.getLogger(TsaStartpruefung.class);

    void beimStart(@Observes StartupEvent ereignis, @ConfigProperty(name = "archiv.rolle") String rolle) {
        SmallRyeConfig config = ConfigProvider.getConfig().unwrap(SmallRyeConfig.class);
        boolean prod = config.getProfiles().contains("prod");
        boolean kommando = Arrays.stream(rolle.split(",")).map(String::trim).allMatch("kommando"::equals);
        if (!prod || kommando) {
            return;
        }
        List<String> dienste = pruefen(config);
        if (dienste.size() == 1) {
            LOG.warnf("Nur ein Zeitstempeldienst konfiguriert (%s); Req 15.11 verlangt zwei (archiv.tsa.dienste)",
                    dienste.getFirst());
        }
    }

    /**
     * Lädt jeden Dienst aus {@code archiv.tsa.dienste} (Adresse, Policy-OIDs, CA-Datei, Prüfdaten-Adresse).
     *
     * @return die Kennungen der Dienste, der Hauptdienst zuerst
     * @throws IllegalStateException ohne Dienst oder bei unvollständiger Konfiguration
     */
    public static List<String> pruefen(Config config) {
        List<String> dienste = TsaDienst.kennungen(config);
        if (dienste.isEmpty()) {
            throw new IllegalStateException("Kein Zeitstempeldienst konfiguriert (" + TsaDienst.DIENSTE
                    + "); ohne Zeitstempel entsteht kein gültiges Manifest (Req 15.3)");
        }
        dienste.forEach(kennung -> TsaDienst.laden(config, kennung));
        return dienste;
    }
}
