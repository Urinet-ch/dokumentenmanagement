package ch.urinet.archiv.sicherheit;

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.MountableFile;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

/**
 * Keycloak mit dem Realm-Export für Stufe A und B ({@code deploy/keycloak/realm-export.json}), nicht mit dem Test-Realm.
 * Andere Datei für die Gegenprobe: {@code -Darchiv.realm-export=<pfad>}.
 */
public class ExportKeycloak implements QuarkusTestResourceLifecycleManager {

    static final String ADMIN = "admin";
    static final String ADMIN_PASSWORT = "admin-nur-test";
    static String basisUrl;

    private GenericContainer<?> keycloak;

    @Override
    public Map<String, String> start() {
        Path export = Path.of(System.getProperty("archiv.realm-export", "../deploy/keycloak/realm-export.json"));
        keycloak = new GenericContainer<>("quay.io/keycloak/keycloak:26.8.2")
                .withEnv("KC_BOOTSTRAP_ADMIN_USERNAME", ADMIN)
                .withEnv("KC_BOOTSTRAP_ADMIN_PASSWORD", ADMIN_PASSWORT)
                .withCopyFileToContainer(MountableFile.forHostPath(export.toAbsolutePath()),
                        "/opt/keycloak/data/import/realm-export.json")
                .withCommand("start-dev", "--import-realm")
                .withExposedPorts(8080)
                .waitingFor(Wait.forHttp("/realms/urinet-archiv").forPort(8080)
                        .withStartupTimeout(Duration.ofMinutes(3)));
        keycloak.start();
        basisUrl = "http://" + keycloak.getHost() + ":" + keycloak.getMappedPort(8080);
        return Map.of("quarkus.oidc.auth-server-url", basisUrl + "/realms/urinet-archiv");
    }

    @Override
    public void stop() {
        if (keycloak != null) {
            keycloak.stop();
        }
    }
}
