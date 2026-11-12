package ch.urinet.archiv.sicherheit.adapter.in.start;

import io.smallrye.config.PropertiesConfigSource;
import io.smallrye.config.SmallRyeConfig;
import io.smallrye.config.SmallRyeConfigBuilder;
import io.smallrye.config.source.file.FileSystemConfigSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Secret-Prüfung in der Konfigurationsphase (tech.md Regel 7): Liest Quarkus einen Zugang, obwohl eine Datei fehlt, leer
 * ist oder der Zugang aus Umgebungsvariable/Systemproperty kommt, scheitert schon das Lesen – mit Dateinamen, ohne Wert.
 */
class SecretPruefungTest {

    private static final String DB_APP = "quarkus.datasource.password";
    private static final String DB_OWNER = "quarkus.liquibase.password";

    @TempDir
    Path verzeichnis;

    @Test
    void alleDateienVorhanden() throws IOException {
        alleDateien();
        SmallRyeConfig config = config("api", Map.of());
        assertThat(config.getValue(DB_APP, String.class)).isEqualTo("attrappe-archiv_db_app");
        assertThat(config.getValue(DB_OWNER, String.class)).isEqualTo("attrappe-archiv_db_owner");
    }

    @Test
    void dateiFehlt() throws IOException {
        dateien("s3_app_zugang_id", "s3_app_zugang_geheimnis", "archiv_db_owner");
        assertThatThrownBy(() -> config("api", Map.of()).getValue(DB_OWNER, String.class))
                .hasMessageContaining("Secret-Datei fehlt, ist leer oder wird übersteuert: archiv_db_app.")
                .hasMessageContaining(verzeichnis.toString());
    }

    @Test
    void leereDatei() throws IOException {
        alleDateien();
        Files.writeString(verzeichnis.resolve("archiv_db_app"), "\n");
        assertThatThrownBy(() -> config("api", Map.of()).getValue(DB_APP, String.class))
                .hasMessageContaining("archiv_db_app");
    }

    @Test
    void dateischluesselAusUmgebung() throws IOException {
        alleDateien();
        assertThatThrownBy(() -> config("api", Map.of("archiv_db_app", "aus-umgebung")).getValue(DB_APP, String.class))
                .hasMessageContaining("archiv_db_app").hasMessageNotContaining("aus-umgebung");
    }

    @Test
    void zugangAusUmgebungOderSystemproperty() throws IOException {
        alleDateien();
        for (var ziel : SecretPruefung.ZIELE.entrySet()) {
            assertThatThrownBy(() -> config("api", Map.of(ziel.getKey(), "geheim-aus-umgebung")).getValue(DB_APP, String.class))
                    .as(ziel.getKey()).hasMessageContaining(ziel.getValue()).hasMessageNotContaining("geheim-aus-umgebung");
        }
    }

    @Test
    void eigentuemerNurBeiMigration() throws IOException {
        dateien("archiv_db_app", "s3_app_zugang_id", "s3_app_zugang_geheimnis", "archiv_manifest_key");
        assertThatCode(() -> config("worker", Map.of()).getValue(DB_APP, String.class)).as("Worker")
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> config("api", Map.of()).getValue(DB_APP, String.class)).as("API")
                .hasMessageContaining("archiv_db_owner");
    }

    @Test
    void ohnePflichtlisteKeinePruefung() {
        SmallRyeConfig config = builder("api", Map.of()).withProfile("dev").build();
        assertThatCode(() -> config.getOptionalValue(DB_APP, String.class)).doesNotThrowAnyException();
    }

    private void alleDateien() throws IOException {
        dateien("archiv_db_app", "archiv_db_owner", "s3_app_zugang_id", "s3_app_zugang_geheimnis", "archiv_manifest_key");
    }

    private void dateien(String... namen) throws IOException {
        for (String name : namen) {
            Files.writeString(verzeichnis.resolve(name), "attrappe-" + name + "\n");
        }
    }

    private SmallRyeConfig config(String rolle, Map<String, String> uebersteuert) {
        return builder(rolle, uebersteuert).withProfile("prod").build();
    }

    /** Wie application.properties: Pflichtlisten und Zugänge nur in {@code %prod}; Übersteuerung mit Ordinal 300 (Umgebung). */
    private SmallRyeConfigBuilder builder(String rolle, Map<String, String> uebersteuert) {
        Map<String, String> werte = new HashMap<>();
        werte.put("archiv.rolle", rolle);
        werte.put("%prod." + SecretPruefung.PFLICHT, "archiv_db_app,s3_app_zugang_id,s3_app_zugang_geheimnis,archiv_manifest_key");
        werte.put("%prod." + SecretPruefung.PFLICHT_MIGRATION, "archiv_db_owner");
        werte.put(SecretPruefung.MIGRIEREN, "true");
        werte.put("%prod.smallrye.config.source.file.locations", verzeichnis.toString());
        SecretPruefung.ZIELE.forEach((ziel, datei) -> werte.put("%prod." + ziel, "${" + datei + "}"));
        return new SmallRyeConfigBuilder()
                .addDefaultInterceptors()
                .addDiscoveredInterceptors()
                .withInterceptors(new SecretPruefung(() -> true))
                .withSources(new PropertiesConfigSource(werte, "application.properties", 250),
                        new PropertiesConfigSource(uebersteuert, "EnvConfigSource", 300),
                        new FileSystemConfigSource(verzeichnis.toFile()));
    }
}
