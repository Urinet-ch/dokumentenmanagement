package ch.urinet.archiv.sicherheit;

import ch.urinet.archiv.gemeinsam.Testanmeldung;
import ch.urinet.archiv.mandant.application.MandantFehler;
import ch.urinet.archiv.mandant.application.MandantProvisionierung;
import ch.urinet.archiv.sicherheit.domain.ApiToken;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Ohne Anmeldung lässt sich der Heap nicht über Argon2 erschöpfen (Req 2.5, Review PR #37, Muss 1): Höchstens
 * {@code archiv.api-token.parallele-pruefungen} (2) Vergleiche laufen gleichzeitig, auch für unbekannte IDs; wer keinen
 * Platz erhält, bekommt sofort 503 {@code UEBERLASTET} mit {@code Retry-After}, unter {@code /public/} zusätzlich mit
 * {@code Cache-Control: no-store} (Spec 03, Req 15.2 und 15.4). Health-Check und OIDC bleiben erreichbar.
 * Den Höchststand zählt {@link ZaehlenderHasher}.
 */
@QuarkusTest
class ApiTokenUeberlastTest {

    private static final int ANFRAGEN = 50;
    private static final int PARALLELE_PRUEFUNGEN = 2;
    private static final Duration SCHNELL = Duration.ofSeconds(2);

    @TestHTTPResource("/")
    URI basis;

    private final SecureRandom zufall = new SecureRandom();
    private final HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5)).build();

    @Inject
    MandantProvisionierung provisionierung;

    @BeforeEach
    void mandantAnlegen() {
        try {
            provisionierung.anlegen("urinet", null, null);
        } catch (MandantFehler e) {
            assertThat(e.grund()).isEqualTo(MandantFehler.Grund.MANDANT_EXISTIERT);
        }
    }

    @AfterEach
    void freigeben() {
        ZaehlenderHasher.freigeben();
    }

    @Test
    void vieleParalleleAnfragenMitUnbekannterIdOhne500() throws Exception {
        String oidc = Testanmeldung.token("inhaber-urinet");
        ZaehlenderHasher.zuruecksetzen();
        ExecutorService ausfuehrer = Executors.newFixedThreadPool(ANFRAGEN);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<CompletableFuture<HttpResponse<String>>> antworten = new ArrayList<>();
            for (int i = 0; i < ANFRAGEN; i++) {
                String token = ApiToken.neu(zufall).text();
                antworten.add(CompletableFuture.supplyAsync(() -> {
                    warten(start);
                    return senden(anfrage("/api/v1/dokumente", token).POST(HttpRequest.BodyPublishers.noBody()));
                }, ausfuehrer));
            }
            start.countDown();
            long beginn = System.nanoTime();
            HttpResponse<String> benutzer = senden(anfrage("/api/v1/probe/ich", oidc).GET());
            Duration dauer = Duration.ofNanos(System.nanoTime() - beginn);
            HttpResponse<String> gesundheit = senden(HttpRequest.newBuilder(basis.resolve("/q/health/live")).GET());

            List<Integer> status = antworten.stream().map(a -> a.join().statusCode()).toList();
            assertThat(status).as("kein 500, nur abgelehnt oder ausgelastet").isNotEmpty().allMatch(
                    s -> s == 401 || s == 503);
            assertThat(ZaehlenderHasher.HOECHSTENS.get()).as("gleichzeitige Argon2-Vergleiche")
                    .isBetween(1, PARALLELE_PRUEFUNGEN);
            assertThat(benutzer.statusCode()).isEqualTo(200);
            assertThat(dauer).as("OIDC-Aufruf daneben").isLessThan(SCHNELL);
            assertThat(gesundheit.statusCode()).isEqualTo(200);
        } finally {
            ausfuehrer.shutdownNow();
        }
    }

    @Test
    void ohneFreienPlatzSofort503MitRetryAfterUndOhneArgon2() throws Exception {
        String oidc = Testanmeldung.token("inhaber-urinet");
        ZaehlenderHasher.anhalten();
        List<CompletableFuture<HttpResponse<String>>> belegt = new ArrayList<>();
        for (int i = 0; i < PARALLELE_PRUEFUNGEN; i++) {
            belegt.add(client.sendAsync(anfrage("/api/v1/dokumente", ApiToken.neu(zufall).text())
                    .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString()));
        }
        long frist = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (ZaehlenderHasher.LAUFEND.get() < PARALLELE_PRUEFUNGEN && System.nanoTime() < frist) {
            Thread.sleep(10);
        }
        assertThat(ZaehlenderHasher.LAUFEND.get()).isEqualTo(PARALLELE_PRUEFUNGEN);

        long beginn = System.nanoTime();
        HttpResponse<String> abgewiesen = senden(anfrage("/api/v1/dokumente", ApiToken.neu(zufall).text())
                .POST(HttpRequest.BodyPublishers.noBody()));
        Duration dauer = Duration.ofNanos(System.nanoTime() - beginn);
        HttpResponse<String> gesundheitMitToken = senden(anfrage("/q/health/live", ApiToken.neu(zufall).text()).GET());
        HttpResponse<String> gesundheit = senden(HttpRequest.newBuilder(basis.resolve("/q/health/live")).GET());
        HttpResponse<String> webseite = senden(anfrage("/public/v1/probe", ApiToken.neu(zufall).text()).GET());
        HttpResponse<String> benutzer = senden(anfrage("/api/v1/probe/ich", oidc).GET());

        assertThat(abgewiesen.statusCode()).as(abgewiesen.body()).isEqualTo(503);
        assertThat(abgewiesen.headers().firstValue("Retry-After")).contains("1");
        assertThat(abgewiesen.headers().firstValue("Content-Type")).contains("application/problem+json");
        assertThat(abgewiesen.body()).contains("\"fehlercode\":\"UEBERLASTET\"");
        assertThat(dauer).isLessThan(SCHNELL);
        assertThat(ZaehlenderHasher.LAUFEND.get()).as("kein weiterer Argon2-Vergleich")
                .isEqualTo(PARALLELE_PRUEFUNGEN);
        assertThat(gesundheitMitToken.statusCode()).isEqualTo(503);
        assertThat(gesundheitMitToken.headers().firstValue("Retry-After")).contains("1");
        assertThat(gesundheit.statusCode()).isEqualTo(200);
        assertThat(benutzer.statusCode()).isEqualTo(200);
        // Webseiten-Schnittstelle (Spec 03, Muss 5 und 6 der Aufgabe 11.6, Review PR #140): dieselbe 503 mit no-store
        assertThat(webseite.statusCode()).as(webseite.body()).isEqualTo(503);
        assertThat(webseite.headers().firstValue("Retry-After")).contains("1");
        assertThat(webseite.headers().allValues("Cache-Control")).containsExactly("no-store");
        assertThat(webseite.headers().firstValue("Content-Type")).contains("application/problem+json");
        assertThat(webseite.body()).contains("\"fehlercode\":\"UEBERLASTET\"");
        assertThat(ZaehlenderHasher.LAUFEND.get()).isEqualTo(PARALLELE_PRUEFUNGEN);

        ZaehlenderHasher.freigeben();
        for (CompletableFuture<HttpResponse<String>> antwort : belegt) {
            assertThat(antwort.get(10, TimeUnit.SECONDS).statusCode()).isEqualTo(401);
        }
    }

    private HttpRequest.Builder anfrage(String pfad, String token) {
        return HttpRequest.newBuilder(basis.resolve(pfad)).timeout(Duration.ofSeconds(20))
                .header("Authorization", "Bearer " + token);
    }

    private HttpResponse<String> senden(HttpRequest.Builder anfrage) {
        try {
            return client.send(anfrage.build(), HttpResponse.BodyHandlers.ofString());
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static void warten(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
