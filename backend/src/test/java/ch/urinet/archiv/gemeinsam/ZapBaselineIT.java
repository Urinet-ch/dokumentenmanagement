package ch.urinet.archiv.gemeinsam;

import ch.urinet.archiv.mandant.application.MandantProvisionierung;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
@Tag("start-3")
@TestProfile(WissenssucheLeistungIT.Isoliert.class)
@EnabledIfSystemProperty(named = "archiv.zap-baseline", matches = "true")
class ZapBaselineIT {
    @Inject MandantProvisionierung provisionierung;
    @TestHTTPResource URI basis;

    @Test
    void testserverBisZumScanendeBereitstellen() throws Exception {
        String jdbc = ConfigProvider.getConfig().getValue("quarkus.datasource.jdbc.url", String.class);
        assertThat(jdbc).isEqualTo("jdbc:postgresql://127.0.0.1:5432/zap_baseline");
        provisionierung.anlegen("urinet", null, null);

        URI lokal = URI.create("http://127.0.0.1:" + basis.getPort() + "/");
        assertThat(basis.getPort()).isPositive();
        HttpRequest anfrage = HttpRequest.newBuilder(lokal.resolve("q/openapi")).timeout(Duration.ofSeconds(30)).build();
        assertThat(HttpClient.newHttpClient().send(anfrage, HttpResponse.BodyHandlers.discarding()).statusCode())
                .isEqualTo(200);
        Path verzeichnis = Path.of(System.getProperty("archiv.zap.verzeichnis"));
        Files.writeString(verzeichnis.resolve("bereit"), lokal.toString());
        Instant ende = Instant.now().plus(Duration.ofMinutes(15));
        while (!Files.exists(verzeichnis.resolve("abgeschlossen")) && Instant.now().isBefore(ende)) {
            Thread.sleep(500);
        }
        assertThat(Files.exists(verzeichnis.resolve("abgeschlossen"))).as("ZAP-Scan innerhalb von 15 Minuten")
                .isTrue();
    }
}
