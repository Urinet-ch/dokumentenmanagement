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

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Startprüfung der Secret-Dateien (tech.md Regel 7): fehlend, leer oder nicht aus einer Datei → Abbruch. */
class SecretPruefungTest {

    @TempDir
    Path verzeichnis;

    @Test
    void alleDateienVorhanden() throws IOException {
        dateien("archiv_db_app", "s3_app_zugang_id", "archiv_db_owner");
        assertThatCode(() -> SecretPruefung.pruefen(config(true, Map.of()))).doesNotThrowAnyException();
    }

    @Test
    void dateiFehlt() throws IOException {
        dateien("s3_app_zugang_id", "archiv_db_owner");
        assertThatThrownBy(() -> SecretPruefung.pruefen(config(true, Map.of())))
                .hasMessageContaining("Secret-Datei fehlt oder ist leer: archiv_db_app")
                .hasMessageContaining(verzeichnis.toString());
    }

    @Test
    void leereDatei() throws IOException {
        dateien("s3_app_zugang_id", "archiv_db_owner");
        Files.writeString(verzeichnis.resolve("archiv_db_app"), "\n");
        assertThatThrownBy(() -> SecretPruefung.pruefen(config(true, Map.of()))).hasMessageContaining("archiv_db_app");
    }

    @Test
    void wertNichtAusDatei() throws IOException {
        dateien("s3_app_zugang_id", "archiv_db_owner");
        assertThatThrownBy(() -> SecretPruefung.pruefen(config(true, Map.of("archiv_db_app", "aus-umgebung"))))
                .hasMessageContaining("archiv_db_app");
    }

    @Test
    void eigentuemerNurBeiMigration() throws IOException {
        dateien("archiv_db_app", "s3_app_zugang_id");
        assertThatCode(() -> SecretPruefung.pruefen(config(false, Map.of()))).as("Worker").doesNotThrowAnyException();
        assertThatThrownBy(() -> SecretPruefung.pruefen(config(true, Map.of()))).as("API")
                .hasMessageContaining("archiv_db_owner");
    }

    private void dateien(String... namen) throws IOException {
        for (String name : namen) {
            Files.writeString(verzeichnis.resolve(name), "attrappe\n");
        }
    }

    private SmallRyeConfig config(boolean migrieren, Map<String, String> weitere) {
        Map<String, String> werte = new HashMap<>(weitere);
        werte.put(SecretPruefung.PFLICHT, "archiv_db_app, s3_app_zugang_id");
        werte.put(SecretPruefung.PFLICHT_MIGRATION, "archiv_db_owner");
        werte.put("quarkus.liquibase.migrate-at-start", String.valueOf(migrieren));
        werte.put("smallrye.config.source.file.locations", verzeichnis.toString());
        return new SmallRyeConfigBuilder()
                .withSources(new PropertiesConfigSource(werte, "test", 250),
                        new FileSystemConfigSource(verzeichnis.toFile()))
                .build();
    }
}
