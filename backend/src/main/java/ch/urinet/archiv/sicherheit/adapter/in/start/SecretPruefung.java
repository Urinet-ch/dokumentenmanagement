package ch.urinet.archiv.sicherheit.adapter.in.start;

import io.quarkus.runtime.ExecutionMode;
import io.smallrye.config.ConfigSourceInterceptor;
import io.smallrye.config.ConfigSourceInterceptorContext;
import io.smallrye.config.ConfigValue;
import io.smallrye.config.Priorities;
import jakarta.annotation.Priority;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

/**
 * Fail closed für Secret-Dateien (tech.md Regel 7, design.md «Secrets und Konfiguration»), schon in der
 * Konfigurationsphase: Liest Quarkus in RUNTIME_INIT einen Zugang aus {@link #ZIELE} (Agroal, Liquibase, S3), prüft der
 * Interceptor vorher alle Pflicht-Dateien. Jeder Schlüssel aus {@code archiv.secrets.pflicht} muss aus einer Datei unter
 * {@code smallrye.config.source.file.locations} kommen und darf nicht leer sein; {@code archiv.secrets.pflicht-migration}
 * nur, wenn beim Start migriert wird (API, Admin-Kommando), der Worker nicht. Zusätzlich muss der Zugang selbst aus der
 * Datei stammen: Ein Wert aus Umgebungsvariable oder Systemproperty statt {@code ${<datei>}} zählt nicht. Sonst bricht
 * der Start ab, bevor Liquibase, Agroal oder der S3-Client etwas tun; die Meldung nennt nur Dateinamen, keine Werte.
 */
@Priority(Priorities.APPLICATION + 100)
public class SecretPruefung implements ConfigSourceInterceptor {

    static final String PFLICHT = "archiv.secrets.pflicht";
    static final String PFLICHT_MIGRATION = "archiv.secrets.pflicht-migration";
    static final String MIGRIEREN = "quarkus.liquibase.migrate-at-start";
    /** Zugang → Secret-Datei, aus der er in {@code prod} kommen muss. */
    static final Map<String, String> ZIELE = Map.of(
            "quarkus.datasource.password", "archiv_db_app",
            "quarkus.liquibase.password", "archiv_db_owner",
            "quarkus.s3.aws.credentials.static-provider.access-key-id", "s3_app_zugang_id",
            "quarkus.s3.aws.credentials.static-provider.secret-access-key", "s3_app_zugang_geheimnis");
    private static final String DATEI_QUELLE = "FileSystemConfigSource[";

    private final BooleanSupplier aktiv;

    public SecretPruefung() {
        this(SecretPruefung::laufzeit);
    }

    SecretPruefung(BooleanSupplier aktiv) {
        this.aktiv = aktiv;
    }

    @Override
    public ConfigValue getValue(ConfigSourceInterceptorContext context, String name) {
        if (ZIELE.containsKey(name) && aktiv.getAsBoolean()) {
            pruefen(context::proceed);
        }
        return context.proceed(name);
    }

    /** Nur zur Laufzeit; beim Build (Augmentation) gibt es {@code /run/secrets} nicht. */
    private static boolean laufzeit() {
        ExecutionMode modus = ExecutionMode.current();
        return modus == ExecutionMode.RUNTIME_INIT || modus == ExecutionMode.RUNNING;
    }

    static void pruefen(Function<String, ConfigValue> lesen) {
        List<String> pflicht = new ArrayList<>(werte(lesen, PFLICHT));
        ConfigValue migrieren = lesen.apply(MIGRIEREN);
        if (migrieren != null && Boolean.parseBoolean(migrieren.getValue())) {
            pflicht.addAll(werte(lesen, PFLICHT_MIGRATION));
        }
        if (pflicht.isEmpty()) {
            return;
        }
        Set<String> fehlend = new LinkedHashSet<>();
        pflicht.stream().filter(datei -> !ausDatei(lesen.apply(datei))).forEach(fehlend::add);
        ZIELE.forEach((ziel, datei) -> {
            if (pflicht.contains(datei) && !ausDatei(datei, lesen.apply(ziel))) {
                fehlend.add(datei);
            }
        });
        if (!fehlend.isEmpty()) {
            ConfigValue ort = lesen.apply("smallrye.config.source.file.locations");
            throw new IllegalStateException("Secret-Datei fehlt, ist leer oder wird übersteuert: "
                    + String.join(", ", fehlend.stream().sorted().toList()) + ". Datei unter "
                    + (ort == null ? "(kein Verzeichnis konfiguriert)" : ort.getValue())
                    + " ablegen, Umgebungsvariablen und Systemproperties für den Zugang entfernen und neu starten.");
        }
    }

    private static List<String> werte(Function<String, ConfigValue> lesen, String name) {
        ConfigValue wert = lesen.apply(name);
        if (wert == null || wert.getValue() == null) {
            return List.of();
        }
        return Arrays.stream(wert.getValue().split(",")).map(String::trim).filter(teil -> !teil.isEmpty()).toList();
    }

    private static boolean ausDatei(ConfigValue wert) {
        return wert != null && wert.getValue() != null && !wert.getValue().isBlank()
                && wert.getSourceName() != null && wert.getSourceName().startsWith(DATEI_QUELLE);
    }

    /** Der Zugang stammt aus der Datei: direkt oder genau als {@code ${<datei>}}, egal aus welcher Quelle der Verweis kommt. */
    private static boolean ausDatei(String datei, ConfigValue ziel) {
        if (ziel == null) {
            return false;
        }
        String roh = ziel.getRawValue() != null ? ziel.getRawValue() : ziel.getValue();
        return ausDatei(ziel) || ("${" + datei + "}").equals(roh == null ? null : roh.trim());
    }
}
