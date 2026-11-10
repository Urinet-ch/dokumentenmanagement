package ch.urinet.archiv.sicherheit.adapter.in.start;

import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.interceptor.Interceptor;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.ConfigValue;

import java.util.ArrayList;
import java.util.List;

/**
 * Fail closed für Secret-Dateien (tech.md Regel 7, design.md «Secrets und Konfiguration»): Jeder Schlüssel aus
 * {@code archiv.secrets.pflicht} muss aus einer Datei unter {@code smallrye.config.source.file.locations} kommen und darf
 * nicht leer sein, sonst bricht der Start ab. Ein leerer Default oder eine Umgebungsvariable zählt nicht. Den Zugang
 * {@code archiv.secrets.pflicht-migration} braucht nur, wer beim Start migriert (API, Admin-Kommando), der Worker nicht.
 */
@ApplicationScoped
public class SecretPruefung {

    static final String PFLICHT = "archiv.secrets.pflicht";
    static final String PFLICHT_MIGRATION = "archiv.secrets.pflicht-migration";
    private static final String DATEI_QUELLE = "FileSystemConfigSource[";

    void beimStart(@Observes @Priority(Interceptor.Priority.PLATFORM_BEFORE) StartupEvent start, Config config) {
        pruefen(config);
    }

    static void pruefen(Config config) {
        List<String> pflicht = new ArrayList<>(werte(config, PFLICHT));
        if (config.getOptionalValue("quarkus.liquibase.migrate-at-start", Boolean.class).orElse(false)) {
            pflicht.addAll(werte(config, PFLICHT_MIGRATION));
        }
        List<String> fehlend = pflicht.stream().filter(name -> !ausDatei(config.getConfigValue(name))).toList();
        if (!fehlend.isEmpty()) {
            throw new IllegalStateException("Secret-Datei fehlt oder ist leer: " + String.join(", ", fehlend)
                    + ". Datei unter " + config.getOptionalValue("smallrye.config.source.file.locations", String.class)
                    .orElse("(kein Verzeichnis konfiguriert)") + " ablegen und neu starten.");
        }
    }

    private static List<String> werte(Config config, String name) {
        return config.getOptionalValues(name, String.class).orElse(List.of()).stream()
                .map(String::trim).filter(wert -> !wert.isEmpty()).toList();
    }

    private static boolean ausDatei(ConfigValue wert) {
        return wert.getValue() != null && !wert.getValue().isBlank()
                && wert.getSourceName() != null && wert.getSourceName().startsWith(DATEI_QUELLE);
    }
}
