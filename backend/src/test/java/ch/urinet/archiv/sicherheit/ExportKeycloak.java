package ch.urinet.archiv.sicherheit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import io.smallrye.config.PropertiesConfigSource;
import io.smallrye.config.SmallRyeConfigBuilder;
import org.testcontainers.Testcontainers;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.MountableFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Keycloak mit dem Realm-Export für Stufe A und B ({@code deploy/keycloak/realm-export.json}), nicht mit dem Test-Realm.
 * Andere Datei für die Gegenprobe: {@code -Darchiv.realm-export=<pfad>}.
 */
public class ExportKeycloak implements QuarkusTestResourceLifecycleManager {

    static final String ADMIN = "admin";
    static final String ADMIN_PASSWORT = "admin-nur-test";
    static final String VERWALTUNG_TESTWERT = "verwaltung-nur-testwert";
    static String basisUrl;
    static String mailpitUrl;
    static OidcTestIdp oidcTestIdp;

    private GenericContainer<?> keycloak;
    private GenericContainer<?> mailpit;
    private Network netz;
    private Path exportTemporaer;

    @Override
    public Map<String, String> start() {
        Path export = Path.of(System.getProperty("archiv.realm-export", "../deploy/keycloak/realm-export.json"));
        netz = Network.newNetwork();
        mailpit = new GenericContainer<>("axllent/mailpit:v1.33.1@sha256:aef2aacc11f075ec0fb6874275ee4a421e688e11a96ebb296134d1735c7761ce")
                .withNetwork(netz).withNetworkAliases("mailpit").withExposedPorts(8025);
        mailpit.start();
        mailpitUrl = "http://" + mailpit.getHost() + ":" + mailpit.getMappedPort(8025);
        try {
            oidcTestIdp = new OidcTestIdp();
            oidcTestIdp.start();
            Testcontainers.exposeHostPorts(oidcTestIdp.port());
            exportTemporaer = exportMitOidcTestIdp(export);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        keycloak = new GenericContainer<>(image())
                .withNetwork(netz)
                .withEnv("KC_BOOTSTRAP_ADMIN_USERNAME", ADMIN)
                .withEnv("KC_BOOTSTRAP_ADMIN_PASSWORD", ADMIN_PASSWORT)
                .withEnv("GOOGLE_CLIENT_ID", "synthetische-google-client-id.apps.test")
                .withEnv("GOOGLE_CLIENT_SECRET", "synthetischer-google-testwert")
                .withEnv("KEYCLOAK_VERWALTUNG_SECRET", VERWALTUNG_TESTWERT)
                .withCopyFileToContainer(MountableFile.forHostPath(exportTemporaer.toAbsolutePath()),
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
        if (mailpit != null) {
            mailpit.stop();
        }
        if (netz != null) {
            netz.close();
        }
        if (oidcTestIdp != null) {
            oidcTestIdp.close();
            oidcTestIdp = null;
        }
        if (exportTemporaer != null) {
            try {
                Files.deleteIfExists(exportTemporaer);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    /** Dasselbe Image wie die Dev Services, eine Quelle: {@code quarkus.keycloak.devservices.image-name}. */
    static String image() {
        try {
            return new SmallRyeConfigBuilder()
                    .addDefaultSources()
                    .addDefaultInterceptors()
                    .withProfile("test")
                    .withSources(new PropertiesConfigSource(
                            Path.of("src/main/resources/application.properties").toUri().toURL()))
                    .build()
                    .getValue("quarkus.keycloak.devservices.image-name", String.class);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private Path exportMitOidcTestIdp(Path quelle) throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode realm = (ObjectNode) mapper.readTree(quelle.toFile());
        realm.set("smtpServer", mapper.valueToTree(Map.of("host", "mailpit", "port", "1025",
                "from", "archiv@example.invalid", "fromDisplayName", "UriNet Archiv",
                "auth", "false", "ssl", "false", "starttls", "false")));
        ArrayNode provider = realm.withArray("identityProviders");
        for (int i = provider.size() - 1; i >= 0; i--) {
            if ("archiv-test-oidc".equals(provider.get(i).path("alias").asText())) {
                provider.remove(i);
            }
        }
        String basis = oidcTestIdp.basisUrl();
        Map<String, String> oidcConfig = Map.ofEntries(
                Map.entry("authorizationUrl", basis + "/authorize"),
                Map.entry("tokenUrl", basis + "/token"),
                Map.entry("userInfoUrl", basis + "/userinfo"),
                Map.entry("issuer", basis),
                Map.entry("clientId", "archiv-test-oidc"),
                Map.entry("clientSecret", "synthetischer-oidc-testwert"),
                Map.entry("clientAuthMethod", "client_secret_post"),
                Map.entry("defaultScope", "openid email profile"),
                Map.entry("validateSignature", "true"),
                Map.entry("jwksUrl", basis + "/jwks"),
                Map.entry("useJwksUrl", "true"));
        JsonNode google = null;
        for (JsonNode knoten : provider) {
            if ("google".equals(knoten.path("alias").asText())) {
                google = knoten;
            }
        }
        if (google == null) {
            throw new IllegalStateException("Provider google fehlt im Realm-Export");
        }
        // Flows, trustEmail und syncMode stammen aus dem Export, damit der Test-IdP dem Google-Provider folgt.
        Map<String, String> config = new java.util.HashMap<>(oidcConfig);
        config.put("syncMode", google.path("config").path("syncMode").asText());
        provider.add(mapper.valueToTree(Map.of(
                "alias", "archiv-test-oidc",
                "displayName", "Lokaler OIDC-Test-IdP",
                "providerId", "oidc",
                "enabled", true,
                "trustEmail", google.path("trustEmail").asBoolean(),
                "storeToken", false,
                "firstBrokerLoginFlowAlias", google.path("firstBrokerLoginFlowAlias").asText(),
                "postBrokerLoginFlowAlias", google.path("postBrokerLoginFlowAlias").asText(),
                "config", config)));

        ArrayNode clients = realm.withArray("clients");
        clients.add(mapper.valueToTree(Map.of(
                "clientId", "archiv-test-google",
                "name", "Nur Entwicklung und Tests: OIDC-Broker-Anmeldung",
                "enabled", true,
                "protocol", "openid-connect",
                "publicClient", true,
                "standardFlowEnabled", true,
                "implicitFlowEnabled", false,
                "directAccessGrantsEnabled", false,
                "redirectUris", List.of("http://localhost/callback"),
                "protocolMappers", List.of(
                        Map.of("name", "mandant", "protocol", "openid-connect",
                                "protocolMapper", "oidc-usermodel-attribute-mapper",
                                "config", Map.of("user.attribute", "mandant", "claim.name", "mandant",
                                        "jsonType.label", "String", "id.token.claim", "true",
                                        "access.token.claim", "true", "userinfo.token.claim", "true")),
                        Map.of("name", "realm-roles", "protocol", "openid-connect",
                                "protocolMapper", "oidc-usermodel-realm-role-mapper",
                                "config", Map.of("multivalued", "true", "claim.name", "realm_access.roles",
                                        "jsonType.label", "String", "id.token.claim", "true",
                                        "access.token.claim", "true", "userinfo.token.claim", "true"))))));

        exportTemporaer = Files.createTempFile("urinet-realm-oidc-test-", ".json");
        mapper.writeValue(exportTemporaer.toFile(), realm);
        Files.setPosixFilePermissions(exportTemporaer, PosixFilePermissions.fromString("rw-r--r--"));
        return exportTemporaer;
    }
}
