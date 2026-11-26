package ch.urinet.archiv.gemeinsam;

import ch.urinet.archiv.mandant.application.MandantProvisionierung;
import ch.urinet.archiv.sicherheit.application.WebseitenSchluesselVerwaltung;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.ConfigProvider;
import org.jose4j.jwk.RsaJsonWebKey;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
@QuarkusTestResource(FakeKeycloak.class)
@Tag("start-3")
@TestProfile(WissenssucheLeistungIT.Isoliert.class)
@EnabledIfSystemProperty(named = "archiv.zap-baseline", matches = "true")
class ZapBaselineIT {
    @Inject MandantProvisionierung provisionierung;
    @Inject WebseitenSchluesselVerwaltung webseitenSchluessel;
    @TestHTTPResource URI basis;

    @Test
    void testserverBisZumScanendeBereitstellen() throws Exception {
        String jdbc = ConfigProvider.getConfig().getValue("quarkus.datasource.jdbc.url", String.class);
        assertThat(jdbc).isEqualTo("jdbc:postgresql://127.0.0.1:5432/zap_baseline");
        provisionierung.anlegen("urinet", null, null);

        URI lokal = URI.create("http://127.0.0.1:" + basis.getPort() + "/");
        assertThat(basis.getPort()).isPositive();
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest anfrage = HttpRequest.newBuilder(lokal.resolve("q/openapi")).timeout(Duration.ofSeconds(30)).build();
        assertThat(client.send(anfrage, HttpResponse.BodyHandlers.discarding()).statusCode())
                .isEqualTo(200);
        Path verzeichnis = Path.of(System.getProperty("archiv.zap.verzeichnis"));
        Path zugang = verzeichnis.resolve("zugang");
        Files.createDirectory(zugang, PosixFilePermissions.asFileAttribute(
                PosixFilePermissions.fromString("rwx------")));

        String issuer = ConfigProvider.getConfig().getValue("quarkus.oidc.auth-server-url", String.class);
        RsaJsonWebKey schluessel = FakeKeycloak.schluesselDerRessource();
        String apiToken = FakeKeycloak.signiertesJwt(schluessel, issuer, "zap-baseline",
                List.of("inhaber", "mitarbeiter"));
        zugangSchreiben(zugang.resolve("api-token"), apiToken);
        HttpRequest apiAnfrage = HttpRequest.newBuilder(lokal.resolve("api/v1/api-tokens"))
                .timeout(Duration.ofSeconds(30)).header("Authorization", "Bearer " + apiToken).GET().build();
        assertThat(client.send(apiAnfrage, HttpResponse.BodyHandlers.discarding()).statusCode())
                .as("Fake-Keycloak-Testtoken mit Inhaber- und Mitarbeiterrolle")
                .isEqualTo(200);

        for (String stufe : List.of("oeffentlich", "mitglieder", "vorstand")) {
            String token = webseitenSchluessel.erstellen("urinet", "zap-baseline-lokal-" + stufe, "lesen", stufe,
                    false).token().text();
            zugangSchreiben(zugang.resolve("webseite-" + stufe), token);
            HttpRequest webAnfrage = HttpRequest.newBuilder(lokal.resolve("public/v1/dokumente"))
                    .timeout(Duration.ofSeconds(30)).header("Authorization", "Bearer " + token)
                    .header("X-Archiv-Zugriff", stufe).GET().build();
            assertThat(client.send(webAnfrage, HttpResponse.BodyHandlers.discarding()).statusCode())
                    .as("Webseiten-Lese-Schlüssel mit Stufe " + stufe)
                    .isEqualTo(200);
        }

        Files.writeString(verzeichnis.resolve("bereit"), lokal.toString());
        Instant ende = Instant.now().plus(Duration.ofMinutes(40));
        while (!Files.exists(verzeichnis.resolve("abgeschlossen")) && Instant.now().isBefore(ende)) {
            Thread.sleep(500);
        }
        assertThat(Files.exists(verzeichnis.resolve("abgeschlossen"))).as("ZAP-Scans innerhalb von 40 Minuten")
                .isTrue();
    }

    private static void zugangSchreiben(Path datei, String token) throws Exception {
        Files.createFile(datei, PosixFilePermissions.asFileAttribute(
                PosixFilePermissions.fromString("rw-------")));
        Files.writeString(datei, token);
    }
}
