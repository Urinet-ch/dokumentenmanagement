package ch.urinet.archiv.integration.adapter.in.rest.webseite;

import ch.urinet.archiv.integration.domain.webseite.Abkuendigung;
import ch.urinet.archiv.integration.domain.webseite.Hauptversion;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import io.vertx.ext.web.Router;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link WebseitenRoute} an einem echten Vert.x-Router ohne Quarkus (Spec 03, Aufgabe 11.6): Reroute auf den internen
 * Pfad, Sperre des internen Pfads, {@code Cache-Control: no-store} (Req 15.4) und – mit gesetztem
 * {@code archiv.webseite.v1.sunset} – {@code Deprecation}, {@code Sunset} und {@code Link} auch auf Fehlern (Req 11.5).
 * Das Szenario {@code @req-11.5} prüft die Variante ohne Eigenschaft in der App.
 */
class WebseitenRouteTest {

    private static final Abkuendigung V1 = new Abkuendigung(new Hauptversion(1), LocalDate.of(2027, 7, 1));

    private final HttpClient client = HttpClient.newHttpClient();
    private Vertx vertx;

    @AfterEach
    void schliessen() throws Exception {
        if (vertx != null) {
            vertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        }
    }

    @Test
    void mitSunsetTragenAntwortUndFehlerDieDreiHeader() throws Exception {
        int port = starten(Map.of(1, V1));
        for (String pfad : List.of("/public/v1/ok?seite=2", "/public/v1/fehler", "/public/v1/gibt-es-nicht",
                "/public/v1")) {
            HttpResponse<String> antwort = abrufen(port, pfad);
            assertThat(antwort.headers().allValues("cache-control")).as(pfad).containsExactly("no-store");
            assertThat(antwort.headers().allValues("deprecation")).as(pfad).containsExactly("@1798761600");
            assertThat(antwort.headers().allValues("sunset")).as(pfad).containsExactly("Thu, 01 Jul 2027 00:00:00 GMT");
            assertThat(antwort.headers().allValues("link")).as(pfad)
                    .containsExactly("</public/v2>; rel=\"successor-version\"");
        }
        HttpResponse<String> ok = abrufen(port, "/public/v1/ok?seite=2");
        assertThat(ok.statusCode()).isEqualTo(200);
        assertThat(ok.body()).isEqualTo("intern /api/v1/public/v1/ok seite=2");
        assertThat(abrufen(port, "/public/v1/fehler").statusCode()).isEqualTo(500);
        assertThat(abrufen(port, "/public/v1/gibt-es-nicht").statusCode()).isEqualTo(404);
    }

    @Test
    void ohneSunsetNurNoStore() throws Exception {
        int port = starten(Map.of());
        for (String pfad : List.of("/public/v1/ok", "/public/v1/fehler", "/public/x")) {
            HttpResponse<String> antwort = abrufen(port, pfad);
            assertThat(antwort.headers().allValues("cache-control")).as(pfad).containsExactly("no-store");
            assertThat(antwort.headers().map().keySet()).as(pfad).doesNotContain("deprecation", "sunset", "link");
        }
    }

    @Test
    void abkuendigungGiltNurFuerIhreHauptversion() throws Exception {
        int port = starten(Map.of(1, V1));
        HttpResponse<String> v2 = abrufen(port, "/public/v2/ok");
        assertThat(v2.headers().allValues("cache-control")).containsExactly("no-store");
        assertThat(v2.headers().map().keySet()).doesNotContain("deprecation", "sunset", "link");
    }

    @Test
    void internerPfadIstVonAussenGesperrt() throws Exception {
        int port = starten(Map.of(1, V1));
        for (String pfad : List.of("/api/v1/public/v1/ok", "/api/v1//public/v1/ok", "/api/v1/public",
                "/api/v1/%70ublic/v1/ok", "/api/v1/public;x=1/v1/ok", "/api/v1;x/public/v1/ok",
                "/api/v1/public/v1;x=1/ok")) {
            HttpResponse<String> antwort = abrufen(port, pfad);
            assertThat(antwort.statusCode()).as(pfad).isEqualTo(404);
            assertThat(antwort.headers().firstValue("content-type")).as(pfad).contains("application/problem+json");
            assertThat(antwort.body()).as(pfad).contains("\"fehlercode\":\"NICHT_GEFUNDEN\"").doesNotContain("intern");
        }
        HttpResponse<String> andere = abrufen(port, "/api/v1/anderes");
        assertThat(andere.body()).isEqualTo("app");
        assertThat(andere.headers().map().keySet()).doesNotContain("cache-control", "deprecation");
    }

    /** Quarkus REST entfernt Matrix-Parameter vor der Zuordnung; unter /public/ weist die Route sie ab (Review PR #140). */
    @Test
    void matrixParameterUnterPublicErgeben404MitDenHeadern() throws Exception {
        int port = starten(Map.of(1, V1));
        for (String pfad : List.of("/public/v1;x=1/ok", "/public;x/v1/ok", "/public/v1/ok;x", "/public/v1/ok;")) {
            HttpResponse<String> antwort = abrufen(port, pfad);
            assertThat(antwort.statusCode()).as(pfad).isEqualTo(404);
            assertThat(antwort.body()).as(pfad).contains("\"fehlercode\":\"NICHT_GEFUNDEN\"").doesNotContain("intern");
            assertThat(antwort.headers().allValues("cache-control")).as(pfad).containsExactly("no-store");
            assertThat(antwort.headers().allValues("deprecation")).as(pfad).containsExactly("@1798761600");
            assertThat(antwort.headers().allValues("sunset")).as(pfad).containsExactly("Thu, 01 Jul 2027 00:00:00 GMT");
            assertThat(antwort.headers().allValues("link")).as(pfad).hasSize(1);
        }
    }

    @Test
    void sperreNachDerZuordnungNurFuerPublicOhneReroute() {
        assertThat(WebseitenSperre.gesperrt("/public/v1/probe", false)).isTrue();
        assertThat(WebseitenSperre.gesperrt("public/v1/probe", false)).isTrue();
        assertThat(WebseitenSperre.gesperrt("/public", false)).isTrue();
        assertThat(WebseitenSperre.gesperrt("/public/v1/probe", true)).isFalse();
        assertThat(WebseitenSperre.gesperrt("/publicx/v1", false)).isFalse();
        assertThat(WebseitenSperre.gesperrt("/dokumente", false)).isFalse();
    }

    @Test
    void konfigurationNimmtIsoDatenUndBrichtSonstAb() {
        Map<String, String> werte = Map.of("archiv.webseite.v1.sunset", " 2027-07-01 ", "archiv.webseite.v2.sunset", "",
                "archiv.webseite.v3.sunset", "2028-01-31");
        Map<Integer, Abkuendigung> gelesen = WebseitenRoute.abkuendigungen(
                List.of("archiv.webseite.v1.sunset", "archiv.webseite.v2.sunset", "ARCHIV_WEBSEITE_V3_SUNSET"),
                s -> Optional.ofNullable(werte.get(s)));
        assertThat(gelesen).containsOnlyKeys(1, 3);
        assertThat(gelesen.get(1)).isEqualTo(V1);
        assertThat(gelesen.get(3).sunset()).isEqualTo(LocalDate.of(2028, 1, 31));

        assertThat(WebseitenRoute.abkuendigungen(List.of(), s -> Optional.empty())).isEmpty();
        assertThatThrownBy(() -> WebseitenRoute.abkuendigungen(List.of(),
                s -> Optional.of("01.07.2027").filter(w -> s.equals("archiv.webseite.v1.sunset"))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("archiv.webseite.v1.sunset")
                .hasMessageNotContaining("01.07.2027");
        assertThatThrownBy(() -> WebseitenRoute.abkuendigungen(List.of("archiv.webseite.v4.sunset"),
                s -> Optional.of("2027-02-30").filter(w -> s.equals("archiv.webseite.v4.sunset"))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("archiv.webseite.v4.sunset");
    }

    /** Router wie in der App: die Webseiten-Route, dahinter die «Resources» unter dem internen Pfad. */
    private int starten(Map<Integer, Abkuendigung> abkuendigungen) throws Exception {
        vertx = Vertx.vertx();
        Router router = Router.router(vertx);
        new WebseitenRoute("/api/v1/", abkuendigungen).einrichten(router);
        router.get("/api/v1/public/v1/ok").handler(k -> k.response()
                .end("intern " + k.normalizedPath() + " " + k.request().query()));
        router.get("/api/v1/public/v2/ok").handler(k -> k.response().end("v2"));
        router.get("/api/v1/public/v1/fehler").handler(k -> k.fail(new IllegalStateException("Probe")));
        router.get("/api/v1/anderes").handler(k -> k.response().end("app"));
        HttpServer server = vertx.createHttpServer().requestHandler(router).listen(0, "127.0.0.1")
                .toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        return server.actualPort();
    }

    private HttpResponse<String> abrufen(int port, String pfad) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + pfad)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
