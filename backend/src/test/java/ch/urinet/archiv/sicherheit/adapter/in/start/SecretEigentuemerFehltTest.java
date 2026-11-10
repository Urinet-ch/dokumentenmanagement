package ch.urinet.archiv.sicherheit.adapter.in.start;

import ch.urinet.archiv.ArchivMain;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.junit.main.Launch;
import io.quarkus.test.junit.main.LaunchResult;
import io.quarkus.test.junit.main.QuarkusMainTest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fehlt in {@code prod} die Datei {@code archiv_db_owner}, bricht der Start schon in der Konfigurationsphase ab
 * (SecretPruefung als Interceptor): Liquibase migriert {@code db/public} nicht ersatzweise mit dem App-Zugang, in
 * {@code public} entsteht kein {@code databasechangelog}. Mit DB-Devservice; die Prüfung vorher nur beim
 * {@code StartupEvent} liess Liquibase zuerst laufen (Gegenprobe im PR).
 */
@QuarkusMainTest
@TestProfile(SecretEigentuemerFehltTest.ProdOhneDbOwner.class)
class SecretEigentuemerFehltTest {

    private static final String PORT = "archiv.test.secret-db-port";
    private static final String APP = "archiv_app";
    private static final String APP_PASSWORT = "attrappe-app";

    public static class ProdOhneDbOwner implements QuarkusTestProfile {
        @Override
        public String getConfigProfile() {
            return "prod";
        }

        @Override
        public Map<String, String> getConfigOverrides() {
            Map<String, String> werte = new HashMap<>();
            werte.put("smallrye.config.source.file.locations",
                    Path.of("src/test/resources/secrets-test/ohne-db-owner").toAbsolutePath().toString());
            // Den App-Zugang setzt der DB-Devservice selbst; geprüft wird hier nur der Eigentümer-Zugang
            werte.put("%prod.archiv.secrets.pflicht", "s3_app_zugang_id,s3_app_zugang_geheimnis");
            werte.put("quarkus.datasource.devservices.username", APP);
            werte.put("quarkus.datasource.devservices.password", APP_PASSWORT);
            werte.put("quarkus.datasource.devservices.db-name", "quarkus");
            werte.put("quarkus.datasource.devservices.port", port());
            werte.put("quarkus.oidc.tenant-enabled", "false");
            werte.put("quarkus.quinoa", "false");
            werte.put("quarkus.compose.devservices.enabled", "false");
            return werte;
        }
    }

    /** Fester freier Port, damit der Test nach dem Abbruch selbst in die Datenbank schauen kann. */
    private static synchronized String port() {
        String port = System.getProperty(PORT);
        if (port == null) {
            try (ServerSocket frei = new ServerSocket(0)) {
                port = String.valueOf(frei.getLocalPort());
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            System.setProperty(PORT, port);
        }
        return port;
    }

    @AfterAll
    static void kommandoEinstellungenEntfernen() {
        ArchivMain.KOMMANDO.keySet().forEach(System::clearProperty);
        System.clearProperty(PORT);
    }

    @Test
    @Launch(value = {"mandant", "anlegen", "probe-owner"}, exitCode = 3)
    void abbruchVorLiquibase(LaunchResult ergebnis) throws SQLException {
        assertThat(ergebnis.getErrorOutput()).contains("Start fehlgeschlagen", "archiv_db_owner")
                .doesNotContain("attrappe");
        try (Connection verbindung = DriverManager.getConnection(
                "jdbc:postgresql://localhost:" + port() + "/quarkus", APP, APP_PASSWORT);
             Statement befehl = verbindung.createStatement();
             ResultSet tabelle = befehl.executeQuery(
                     "select to_regclass('public.databasechangelog') is null, to_regclass('public.mandant') is null")) {
            tabelle.next();
            assertThat(tabelle.getBoolean(1)).as("public.databasechangelog fehlt").isTrue();
            assertThat(tabelle.getBoolean(2)).as("public.mandant fehlt").isTrue();
        }
    }
}
