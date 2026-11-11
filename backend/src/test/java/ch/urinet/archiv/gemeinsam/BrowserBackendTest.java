package ch.urinet.archiv.gemeinsam;

import ch.urinet.archiv.mandant.application.MandantProvisionierung;
import io.agroal.api.AgroalDataSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Echtes Backend für die Browser-Szenarien (frontend/e2e): startet das Archiv im Testmodus mit Dev Services
 * (PostgreSQL, Keycloak mit Test-Realm, MinIO) und dem Test-Handler für {@code VERARBEITEN}, legt die Mandanten
 * {@code urinet} und {@code vhf-gsk} an und schreibt {@code target/browser-backend.json} mit den Adressen von API und
 * Keycloak. Danach wartet er, bis {@code target/browser-backend.stop} erscheint (höchstens 60 Minuten).
 * <p>
 * Nur mit {@code -Darchiv.browser-backend=true}; in {@code mvn verify} läuft er nicht.
 */
@QuarkusTest
@EnabledIfSystemProperty(named = "archiv.browser-backend", matches = "true")
class BrowserBackendTest {

    private static final Path BEREIT = Path.of("target", "browser-backend.json");
    private static final Path STOPP = Path.of("target", "browser-backend.stop");

    @Inject
    MandantProvisionierung provisionierung;

    @Inject
    AgroalDataSource datenbank;

    @Test
    void bereitstellen() throws Exception {
        for (String code : List.of("urinet", "vhf-gsk")) {
            if (!imRegister(code)) {
                provisionierung.anlegen(code, null, null);
            }
        }
        var config = ConfigProvider.getConfig();
        String api = "http://localhost:" + config.getValue("quarkus.http.test-port", Integer.class);
        String keycloak = config.getValue("quarkus.oidc.auth-server-url", String.class);
        Files.deleteIfExists(STOPP);
        Files.writeString(BEREIT, "{\"api\":\"" + api + "\",\"keycloak\":\"" + keycloak + "\"}\n");
        try {
            Instant ende = Instant.now().plus(Duration.ofMinutes(60));
            while (!Files.exists(STOPP) && Instant.now().isBefore(ende)) {
                Thread.sleep(500);
            }
        } finally {
            Files.deleteIfExists(BEREIT);
            Files.deleteIfExists(STOPP);
        }
    }

    private boolean imRegister(String code) throws Exception {
        try (Connection verbindung = datenbank.getConnection(); PreparedStatement abfrage =
                verbindung.prepareStatement("select 1 from public.mandant where code = ?")) {
            abfrage.setString(1, code);
            try (ResultSet zeile = abfrage.executeQuery()) {
                return zeile.next();
            }
        }
    }
}
