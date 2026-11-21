package ch.urinet.archiv.benutzer.adapter.out.keycloak;

import ch.urinet.archiv.benutzer.application.IdentitaetsDienst.AnlageException;
import ch.urinet.archiv.benutzer.application.IdentitaetsDienst.KonfliktException;
import ch.urinet.archiv.benutzer.application.IdentitaetsDienst.Neuanlage;
import ch.urinet.archiv.gemeinsam.domain.FachFehler;
import ch.urinet.archiv.gemeinsam.domain.Fehlercode;
import ch.urinet.archiv.gemeinsam.LogMitschnitt;
import ch.urinet.archiv.sicherheit.domain.Rolle;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.absent;
import static com.github.tomakehurst.wiremock.client.WireMock.delete;
import static com.github.tomakehurst.wiremock.client.WireMock.deleteRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.put;
import static com.github.tomakehurst.wiremock.client.WireMock.putRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KeycloakAdminAdapterTest {

    private static final String TOKEN = "/realms/urinet-archiv/protocol/openid-connect/token";
    private static final String USERS = "/admin/realms/urinet-archiv/users";

    private WireMockServer server;
    private LogMitschnitt log;

    @BeforeEach
    void starten() {
        server = new WireMockServer(options().dynamicPort());
        server.start();
        log = LogMitschnitt.starten();
    }

    @AfterEach
    void stoppen() {
        log.close();
        server.stop();
    }

    @Test
    void fehlendeVerbindungsdatenMeldenDienstAlsNichtErreichbar() {
        KeycloakAdminAdapter ohneAdresse = new KeycloakAdminAdapter(new ObjectMapper(), null, "urinet-archiv",
                "archiv-verwaltung", "synthetic-client-secret", "archiv-pwa", "", Duration.ofSeconds(2),
                Duration.ofSeconds(1), Clock.systemUTC());
        KeycloakAdminAdapter ohneSecret = new KeycloakAdminAdapter(new ObjectMapper(), server.baseUrl(),
                "urinet-archiv", "archiv-verwaltung", "", "archiv-pwa", "", Duration.ofSeconds(2),
                Duration.ofSeconds(1), Clock.systemUTC());

        assertThatThrownBy(ohneAdresse::verbindungPruefen)
                .isInstanceOfSatisfying(FachFehler.class,
                        fehler -> assertThat(fehler.code()).isEqualTo(Fehlercode.IDENTITAETSDIENST_NICHT_ERREICHBAR));
        assertThatThrownBy(ohneSecret::verbindungPruefen)
                .isInstanceOfSatisfying(FachFehler.class,
                        fehler -> assertThat(fehler.code()).isEqualTo(Fehlercode.IDENTITAETSDIENST_NICHT_ERREICHBAR));
        server.verify(0, postRequestedFor(urlPathEqualTo(TOKEN)));
    }

    @Test
    void abgelehntesDienstkontoWirdAls502Abgebildet() {
        server.stubFor(post(urlPathEqualTo(TOKEN)).willReturn(aResponse().withStatus(401)
                .withBody("{\"error\":\"synthetic private detail\"}")));

        assertThatThrownBy(() -> adapter(Duration.ofSeconds(2), Duration.ofSeconds(1)).verbindungPruefen())
                .isInstanceOfSatisfying(FachFehler.class,
                        fehler -> assertThat(fehler.code()).isEqualTo(Fehlercode.IDENTITAETSDIENST_FEHLER));
        assertThat(log.volltext()).doesNotContain("synthetic private detail");
        server.verify(1, postRequestedFor(urlPathEqualTo(TOKEN)));
    }

    @Test
    void abgelehnteAdminAnfrageIst502UndEnthaeltKeineKeycloakMeldung() {
        tokenAntwort();
        server.stubFor(get(urlPathEqualTo(USERS + "/benutzer-1"))
                .willReturn(aResponse().withStatus(401).withBody("{\"error\":\"synthetic private detail\"}")));

        assertThatThrownBy(() -> adapter(Duration.ofSeconds(2), Duration.ofSeconds(1))
                .benutzerLesen("urinet", "benutzer-1"))
                .isInstanceOfSatisfying(FachFehler.class,
                        fehler -> assertThat(fehler.code()).isEqualTo(Fehlercode.IDENTITAETSDIENST_FEHLER));
        assertThat(log.volltext()).doesNotContain("synthetic private detail");
    }

    @Test
    void lesefehlerWirdGenauEinmalNach200MillisekundenWiederholt() {
        tokenAntwort();
        server.stubFor(get(urlPathEqualTo(USERS + "/benutzer-1"))
                .inScenario("einmalige-wiederholung")
                .whenScenarioStateIs("Started")
                .willReturn(aResponse().withStatus(503))
                .willSetStateTo("zweiter-versuch"));
        server.stubFor(get(urlPathEqualTo(USERS + "/benutzer-1"))
                .inScenario("einmalige-wiederholung")
                .whenScenarioStateIs("zweiter-versuch")
                .willReturn(aResponse().withStatus(404)));

        long start = System.nanoTime();
        assertThatThrownBy(() -> adapter(Duration.ofSeconds(2), Duration.ofSeconds(1))
                .benutzerLesen("urinet", "benutzer-1"))
                .isInstanceOfSatisfying(FachFehler.class,
                        fehler -> assertThat(fehler.code()).isEqualTo(Fehlercode.NICHT_GEFUNDEN));

        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)).isGreaterThanOrEqualTo(200);
        server.verify(2, getRequestedFor(urlPathEqualTo(USERS + "/benutzer-1")));
    }

    @Test
    void lesefehlerNachTransportwiederholungWirdNichtEinDrittesMalGesendet() {
        tokenAntwort();
        server.stubFor(get(urlPathEqualTo(USERS + "/benutzer-1"))
                .inScenario("transportfehler")
                .whenScenarioStateIs("Started")
                .willReturn(aResponse().withStatus(500).withFixedDelay(500))
                .willSetStateTo("zweiter-versuch"));
        server.stubFor(get(urlPathEqualTo(USERS + "/benutzer-1"))
                .inScenario("transportfehler")
                .whenScenarioStateIs("zweiter-versuch")
                .willReturn(aResponse().withStatus(503))
                .willSetStateTo("dritter-versuch"));
        server.stubFor(get(urlPathEqualTo(USERS + "/benutzer-1"))
                .inScenario("transportfehler")
                .whenScenarioStateIs("dritter-versuch")
                .willReturn(aResponse().withStatus(404)));

        assertThatThrownBy(() -> adapter(Duration.ofSeconds(1), Duration.ofMillis(100))
                .benutzerLesen("urinet", "benutzer-1"))
                .isInstanceOfSatisfying(FachFehler.class,
                        fehler -> assertThat(fehler.code())
                                .isEqualTo(Fehlercode.IDENTITAETSDIENST_NICHT_ERREICHBAR));

        server.verify(2, getRequestedFor(urlPathEqualTo(USERS + "/benutzer-1")));
    }

    @Test
    void schutzschalterOeffnetNachDreiFehlernUndVerhindertWeitereAufrufe() {
        tokenAntwort();
        server.stubFor(get(urlPathEqualTo(USERS + "/benutzer-1")).willReturn(aResponse().withStatus(503)));
        KeycloakAdminAdapter adapter = adapter(Duration.ofSeconds(2), Duration.ofSeconds(1));

        for (int i = 0; i < 3; i++) {
            assertThatThrownBy(() -> adapter.benutzerLesen("urinet", "benutzer-1"))
                    .isInstanceOfSatisfying(FachFehler.class,
                            fehler -> assertThat(fehler.code())
                                    .isEqualTo(Fehlercode.IDENTITAETSDIENST_NICHT_ERREICHBAR));
        }
        assertThatThrownBy(() -> adapter.benutzerLesen("urinet", "benutzer-1"))
                .isInstanceOfSatisfying(FachFehler.class,
                        fehler -> assertThat(fehler.code())
                                .isEqualTo(Fehlercode.IDENTITAETSDIENST_NICHT_ERREICHBAR));

        server.verify(6, getRequestedFor(urlPathEqualTo(USERS + "/benutzer-1")));
    }

    @Test
    void schreibaufrufWirdBei5xxNichtWiederholt() {
        tokenAntwort();
        server.stubFor(post(urlPathEqualTo(USERS)).willReturn(aResponse().withStatus(503)));
        KeycloakAdminAdapter adapter = adapter(Duration.ofSeconds(2), Duration.ofSeconds(1));

        assertThatThrownBy(() -> adapter.anlegen("urinet",
                new Neuanlage("synthetic-user", "user@example.invalid", "Ada", "Muster"),
                Rolle.MITARBEITER))
                .isInstanceOfSatisfying(FachFehler.class,
                        fehler -> assertThat(fehler.code())
                                .isEqualTo(Fehlercode.IDENTITAETSDIENST_NICHT_ERREICHBAR));

        server.verify(1, postRequestedFor(urlPathEqualTo(USERS)));
    }

    @Test
    void zeitueberschreitungDesTokenabrufsIst503OhneWiederholung() {
        server.stubFor(post(urlPathEqualTo(TOKEN)).willReturn(okJson("""
                {"access_token":"synthetic-token","expires_in":300}
                """).withFixedDelay(500)));

        assertThatThrownBy(() -> adapter(Duration.ofSeconds(1), Duration.ofMillis(100)).verbindungPruefen())
                .isInstanceOfSatisfying(FachFehler.class,
                        fehler -> assertThat(fehler.code())
                                .isEqualTo(Fehlercode.IDENTITAETSDIENST_NICHT_ERREICHBAR));

        server.verify(1, postRequestedFor(urlPathEqualTo(TOKEN)));
    }

    @Test
    void begrenztAufZweiGleichzeitigeAufrufeUndWartetFuerDenDritten200Millisekunden() throws Exception {
        tokenAntwort();
        CountDownLatch laufendeAnfragen = new CountDownLatch(2);
        server.addMockServiceRequestListener((request, response) -> {
            if (request.getUrl().startsWith(USERS + "/")) {
                laufendeAnfragen.countDown();
            }
        });
        server.stubFor(get(urlPathMatching(USERS + "/benutzer-[12]"))
                .willReturn(aResponse().withStatus(404).withFixedDelay(800)));

        KeycloakAdminAdapter adapter = adapter(Duration.ofSeconds(2), Duration.ofSeconds(1));
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            var erster = executor.submit(() -> {
                assertThatThrownBy(() -> adapter.benutzerLesen("urinet", "benutzer-1"))
                        .isInstanceOfSatisfying(FachFehler.class,
                                fehler -> assertThat(fehler.code()).isEqualTo(Fehlercode.NICHT_GEFUNDEN));
            });
            var zweiter = executor.submit(() -> {
                assertThatThrownBy(() -> adapter.benutzerLesen("urinet", "benutzer-2"))
                        .isInstanceOfSatisfying(FachFehler.class,
                                fehler -> assertThat(fehler.code()).isEqualTo(Fehlercode.NICHT_GEFUNDEN));
            });
            assertThat(laufendeAnfragen.await(2, TimeUnit.SECONDS)).isTrue();

            long start = System.nanoTime();
            assertThatThrownBy(() -> adapter.benutzerLesen("urinet", "benutzer-3"))
                    .isInstanceOfSatisfying(FachFehler.class,
                            fehler -> assertThat(fehler.code()).isEqualTo(Fehlercode.UEBERLASTET));

            assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)).isGreaterThanOrEqualTo(200);
            erster.get(2, TimeUnit.SECONDS);
            zweiter.get(2, TimeUnit.SECONDS);
            server.verify(2, getRequestedFor(urlPathMatching(USERS + "/benutzer-[12]")));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void benutzerAusFremdemMandantenWirdNichtWeiterverarbeitet() {
        tokenAntwort();
        server.stubFor(get(urlPathEqualTo(USERS)).willReturn(okJson("""
                [
                  {"id":"benutzer-1","username":"eins","enabled":true,
                   "attributes":{"mandant":["urinet"]},"requiredActions":[]},
                  {"id":"benutzer-2","username":"zwei","enabled":true,
                   "attributes":{"mandant":["vhf-gsk"]},"requiredActions":[]},
                  {"id":"benutzer-3","username":"drei","enabled":true,
                   "attributes":{"mandant":[]},"requiredActions":[]},
                  {"id":"benutzer-4","username":"vier","enabled":true,
                   "attributes":{"mandant":["urinet","vhf-gsk"]},"requiredActions":[]}
                ]
                """)));
        server.stubFor(get(urlPathEqualTo(USERS + "/benutzer-1/role-mappings/realm")).willReturn(okJson("[]")));
        server.stubFor(get(urlPathEqualTo(USERS + "/benutzer-1/credentials")).willReturn(okJson("[]")));
        server.stubFor(get(urlPathEqualTo(USERS + "/benutzer-1/federated-identity"))
                .willReturn(okJson("[]")));

        var benutzer = adapter(Duration.ofSeconds(2), Duration.ofSeconds(1))
                .benutzerSuchen("urinet", "", 0);

        assertThat(benutzer).hasSize(1);
        assertThat(benutzer.getFirst().id()).isEqualTo("benutzer-1");
        server.verify(0, getRequestedFor(urlPathEqualTo(USERS + "/benutzer-2/role-mappings/realm")));
        server.verify(0, getRequestedFor(urlPathEqualTo(USERS + "/benutzer-3/role-mappings/realm")));
        server.verify(0, getRequestedFor(urlPathEqualTo(USERS + "/benutzer-4/role-mappings/realm")));
    }

    @Test
    void benutzerSuchePrueftGrenzenUndFiltertVorDerPaginierung() throws Exception {
        tokenAntwort();
        server.stubFor(get(urlPathEqualTo(USERS)).willReturn(okJson("[]")));
        KeycloakAdminAdapter adapter = adapter(Duration.ofSeconds(2), Duration.ofSeconds(1));

        assertThat(adapter.benutzerSuchen("urinet", "probe", 2, 7)).isEmpty();
        server.verify(1, getRequestedFor(urlPathEqualTo(USERS))
                .withQueryParam("q", equalTo("mandant:urinet"))
                .withQueryParam("search", absent())
                .withQueryParam("first", equalTo("0"))
                .withQueryParam("max", equalTo("50")));
        assertThatThrownBy(() -> adapter.benutzerSuchen("urinet", "", 0, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> adapter.benutzerSuchen("urinet", "", 0, 51))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> adapter.benutzerSuchen("urinet", "", Integer.MAX_VALUE, 2))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> adapter.benutzerSuchen("urinet", "x".repeat(41), 0, 7))
                .isInstanceOf(IllegalArgumentException.class);
        server.verify(1, getRequestedFor(urlPathEqualTo(USERS)));

        var zuGross = new ObjectMapper().createArrayNode();
        for (int i = 0; i < 51; i++) {
            zuGross.addObject();
        }
        server.stubFor(get(urlPathEqualTo(USERS)).willReturn(okJson(zuGross.toString())));
        assertThatThrownBy(() -> adapter.benutzerSuchen("urinet", "", 0, 1))
                .isInstanceOfSatisfying(FachFehler.class,
                        fehler -> assertThat(fehler.code()).isEqualTo(Fehlercode.IDENTITAETSDIENST_FEHLER));
    }

    @Test
    void suchergebnisseWerdenMandantengefiltertUndSeitenuebergreifendPaginiert() {
        tokenAntwort();
        var json = new ObjectMapper();
        var erste = json.createArrayNode();
        for (int i = 0; i < 50; i++) {
            var user = erste.addObject();
            user.put("id", "benutzer-" + i);
            user.put("username", "synthetisch-" + i);
            user.put("enabled", true);
            user.putArray("requiredActions");
            user.putObject("attributes").putArray("mandant").add(i == 1 ? "fremd" : "urinet");
            if (i < 3) {
                user.put("firstName", "Sonne");
            }
        }
        var zweite = json.createArrayNode();
        for (int i = 50; i < 53; i++) {
            var user = zweite.addObject();
            user.put("id", "benutzer-" + i);
            user.put("username", "synthetisch-" + i);
            user.put("email", "sonne" + i + "@example.test");
            user.put("enabled", true);
            user.putArray("requiredActions");
            user.putObject("attributes").putArray("mandant").add("urinet");
        }
        server.stubFor(get(urlPathEqualTo(USERS)).withQueryParam("q", equalTo("mandant:urinet"))
                .withQueryParam("search", absent()).withQueryParam("first", equalTo("0"))
                .withQueryParam("max", equalTo("50")).willReturn(okJson(erste.toString())));
        server.stubFor(get(urlPathEqualTo(USERS)).withQueryParam("q", equalTo("mandant:urinet"))
                .withQueryParam("search", absent()).withQueryParam("first", equalTo("50"))
                .withQueryParam("max", equalTo("50")).willReturn(okJson(zweite.toString())));
        server.stubFor(get(urlPathMatching(USERS + "/benutzer-[0-9]+/role-mappings/realm"))
                .willReturn(okJson("[]")));
        server.stubFor(get(urlPathMatching(USERS + "/benutzer-[0-9]+/credentials"))
                .willReturn(okJson("[]")));
        server.stubFor(get(urlPathMatching(USERS + "/benutzer-[0-9]+/federated-identity"))
                .willReturn(okJson("[]")));

        var ergebnis = adapter(Duration.ofSeconds(2), Duration.ofSeconds(1))
                .benutzerSuchen("urinet", "SONNE", 1, 2);
        assertThat(ergebnis).extracting(b -> b.id()).containsExactly("benutzer-50", "benutzer-51");
        server.verify(0, getRequestedFor(urlPathEqualTo(USERS + "/benutzer-1/role-mappings/realm")));
        server.verify(2, getRequestedFor(urlPathEqualTo(USERS)).withQueryParam("search", absent()));
    }

    @Test
    void positivlisteLehntNichtFreigegebeneAufrufeAb() {
        KeycloakAdminAdapter adapter = adapter(Duration.ofSeconds(2), Duration.ofSeconds(1));

        assertThatThrownBy(() -> adapter.pruefePositivliste("POST", USERS + "/benutzer-1/reset-password"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> adapter.pruefePositivliste("GET", "/admin/realms/urinet-archiv/clients"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> adapter.pruefePositivliste("POST", "/admin/realms/master/users"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> adapter.pruefePositivliste("DELETE", USERS + "/benutzer-1/credentials"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> adapter.pruefePositivliste("GET", USERS + "/benutzer-1/credentials/credential-1"))
                .isInstanceOf(IllegalArgumentException.class);
        adapter.pruefePositivliste("GET", USERS + "/benutzer-1/credentials");
        adapter.pruefePositivliste("DELETE", USERS + "/benutzer-1/credentials/credential-1");
    }

    @Test
    void benutzerLesenErkenntGoogleUeberDieListeDerVerknuepfungen() {
        tokenAntwort();
        benutzerStub("benutzer-1", "urinet");
        server.stubFor(get(urlPathEqualTo(USERS + "/benutzer-1/role-mappings/realm")).willReturn(okJson("[]")));
        server.stubFor(get(urlPathEqualTo(USERS + "/benutzer-1/credentials")).willReturn(okJson("[]")));
        server.stubFor(get(urlPathEqualTo(USERS + "/benutzer-1/federated-identity")).willReturn(okJson("""
                [{"identityProvider":"github"},{"identityProvider":"google"}]
                """)));

        var benutzer = adapter(Duration.ofSeconds(2), Duration.ofSeconds(1)).benutzerLesen("urinet", "benutzer-1");

        assertThat(benutzer.google()).isTrue();
        server.verify(0, getRequestedFor(urlPathEqualTo(USERS + "/benutzer-1/federated-identity/google")));
    }

    @Test
    void benutzerLesenOhneGoogleVerknuepfungMeldetFalse() {
        tokenAntwort();
        benutzerStub("benutzer-1", "urinet");
        server.stubFor(get(urlPathEqualTo(USERS + "/benutzer-1/role-mappings/realm")).willReturn(okJson("[]")));
        server.stubFor(get(urlPathEqualTo(USERS + "/benutzer-1/credentials")).willReturn(okJson("[]")));
        server.stubFor(get(urlPathEqualTo(USERS + "/benutzer-1/federated-identity")).willReturn(okJson("""
                [{"identityProvider":"github"}]
                """)));

        assertThat(adapter(Duration.ofSeconds(2), Duration.ofSeconds(1))
                .benutzerLesen("urinet", "benutzer-1").google()).isFalse();
    }

    @Test
    void inhaberZaehlenPrueftMandantRolleUndErforderlicheAktionen() {
        tokenAntwort();
        server.stubFor(get(urlPathEqualTo(USERS))
                .withQueryParam("q", equalTo("mandant:urinet"))
                .withQueryParam("max", equalTo("50"))
                .willReturn(okJson("""
                        [{"id":"inhaber-1","username":"eins","enabled":true},
                         {"id":"inhaber-2","username":"zwei","enabled":true,"attributes":{"mandant":["urinet"]},"requiredActions":[]},
                         {"id":"inhaber-3","username":"drei","enabled":false,"attributes":{"mandant":["urinet"]},"requiredActions":[]},
                         {"id":"inhaber-4","username":"vier","enabled":true,"attributes":{"mandant":["vhf-gsk"]},"requiredActions":[]},
                         {"id":"inhaber-5","username":"fuenf","enabled":true,"attributes":{"mandant":["urinet"]},"requiredActions":["VERIFY_EMAIL"]},
                         {"id":"inhaber-6","username":"sechs","enabled":true,"attributes":{"mandant":["urinet"]}},
                         {"id":"mitarbeiter-1","enabled":true,"attributes":{"mandant":["urinet"]},"requiredActions":[]}]
                        """)));
        benutzerStub("inhaber-1", "urinet");
        server.stubFor(get(urlPathEqualTo(USERS + "/inhaber-6")).willReturn(okJson("""
                {"id":"inhaber-6","username":"sechs","enabled":true,"attributes":{"mandant":["urinet"]},
                 "requiredActions":["CONFIGURE_TOTP"]}
                """)));
        for (String id : List.of("inhaber-1", "inhaber-2", "inhaber-5", "inhaber-6")) {
            server.stubFor(get(urlPathEqualTo(USERS + "/" + id + "/role-mappings/realm"))
                    .willReturn(okJson("[{\"name\":\"inhaber\"}]")));
        }
        server.stubFor(get(urlPathEqualTo(USERS + "/mitarbeiter-1/role-mappings/realm"))
                .willReturn(okJson("[{\"name\":\"mitarbeiter\"}]")));

        long anzahl = adapter(Duration.ofSeconds(2), Duration.ofSeconds(1)).inhaberZaehlen("urinet");

        assertThat(anzahl).isEqualTo(2);
        server.verify(1, getRequestedFor(urlPathEqualTo(USERS + "/inhaber-1")));
        server.verify(1, getRequestedFor(urlPathEqualTo(USERS + "/inhaber-6")));
        server.verify(0, getRequestedFor(urlPathEqualTo(USERS + "/inhaber-4/role-mappings/realm")));
    }

    @Test
    void benutzerZaehlenPrueftMandantenAufAllenSeitenUndZaehltDeaktivierte() {
        tokenAntwort();
        String ersteSeite = java.util.stream.IntStream.range(0, 50)
                .mapToObj(i -> "{\"id\":\"u" + i + "\",\"attributes\":{\"mandant\":[\""
                        + (i == 49 ? "vhf-gsk" : "urinet") + "\"]}}")
                .collect(java.util.stream.Collectors.joining(",", "[", "]"));
        server.stubFor(get(urlPathEqualTo(USERS))
                .withQueryParam("q", equalTo("mandant:urinet")).withQueryParam("first", equalTo("0"))
                .withQueryParam("max", equalTo("50")).willReturn(okJson(ersteSeite)));
        server.stubFor(get(urlPathEqualTo(USERS))
                .withQueryParam("q", equalTo("mandant:urinet")).withQueryParam("first", equalTo("50"))
                .withQueryParam("max", equalTo("50")).willReturn(okJson("""
                        [{"id":"deaktiviert","enabled":false,"attributes":{"mandant":["urinet"]}},
                         {"id":"ohne-attribute"},{"id":"ohne-mandant","attributes":{}}]
                        """)));
        server.stubFor(get(urlPathEqualTo(USERS + "/ohne-attribute"))
                .willReturn(okJson("{\"id\":\"ohne-attribute\",\"attributes\":{\"mandant\":[\"vhf-gsk\"]}}")));
        server.stubFor(get(urlPathEqualTo(USERS + "/ohne-mandant"))
                .willReturn(okJson("{\"id\":\"ohne-mandant\",\"attributes\":{\"mandant\":[\"urinet\"]}}")));

        assertThat(adapter(Duration.ofSeconds(2), Duration.ofSeconds(1)).benutzerZaehlen("urinet")).isEqualTo(51);
        server.verify(1, getRequestedFor(urlPathEqualTo(USERS + "/ohne-attribute")));
        server.verify(1, getRequestedFor(urlPathEqualTo(USERS + "/ohne-mandant")));
        server.verify(0, getRequestedFor(urlPathEqualTo(USERS + "/deaktiviert/credentials")));
    }

    @Test
    void benutzerZaehlenBrichtBeiMehrdeutigemMandantenattributAb() {
        tokenAntwort();
        server.stubFor(get(urlPathEqualTo(USERS)).willReturn(okJson("""
                [{"id":"mehrdeutig","attributes":{"mandant":["urinet","vhf-gsk"]}}]
                """)));

        assertThatThrownBy(() -> adapter(Duration.ofSeconds(2), Duration.ofSeconds(1)).benutzerZaehlen("urinet"))
                .isInstanceOfSatisfying(FachFehler.class,
                        fehler -> assertThat(fehler.code()).isEqualTo(Fehlercode.IDENTITAETSDIENST_FEHLER));
    }

    @Test
    void inhaberZaehlenBrichtBeiUnvollstaendigenOderUnbekanntenAktionenAb() {
        tokenAntwort();
        server.stubFor(get(urlPathEqualTo(USERS))
                .willReturn(okJson("""
                        [{"id":"inhaber-1","enabled":true,"attributes":{"mandant":["urinet"]}}]
                        """)));
        server.stubFor(get(urlPathEqualTo(USERS + "/inhaber-1")).willReturn(okJson("""
                {"id":"inhaber-1","enabled":true,"attributes":{"mandant":["urinet"]}}
                """)));
        KeycloakAdminAdapter adapter = adapter(Duration.ofSeconds(2), Duration.ofSeconds(1));
        assertThatThrownBy(() -> adapter.inhaberZaehlen("urinet"))
                .isInstanceOfSatisfying(FachFehler.class,
                        fehler -> assertThat(fehler.code()).isEqualTo(Fehlercode.IDENTITAETSDIENST_FEHLER));

        server.stubFor(get(urlPathEqualTo(USERS + "/inhaber-1")).willReturn(okJson("""
                {"id":"inhaber-1","enabled":true,"attributes":{"mandant":["urinet"]},
                 "requiredActions":["SYNTHETISCHE_AKTION"]}
                """)));
        server.stubFor(get(urlPathEqualTo(USERS + "/inhaber-1/role-mappings/realm"))
                .willReturn(okJson("[{\"name\":\"inhaber\"}]")));
        assertThatThrownBy(() -> adapter.inhaberZaehlen("urinet"))
                .isInstanceOfSatisfying(FachFehler.class,
                        fehler -> assertThat(fehler.code()).isEqualTo(Fehlercode.IDENTITAETSDIENST_FEHLER));
    }

    @Test
    void inhaberZaehlenPrueftAuchDieNaechsteMandantenseite() {
        tokenAntwort();
        String inaktiv = "{\"id\":\"inaktiv\",\"enabled\":false,\"attributes\":{\"mandant\":[\"urinet\"]},"
                + "\"requiredActions\":[]}";
        server.stubFor(get(urlPathEqualTo(USERS)).withQueryParam("first", equalTo("0"))
                .willReturn(okJson("[" + String.join(",", java.util.Collections.nCopies(50, inaktiv)) + "]")));
        server.stubFor(get(urlPathEqualTo(USERS)).withQueryParam("first", equalTo("50"))
                .willReturn(okJson("""
                        [{"id":"inhaber-1","enabled":true,"attributes":{"mandant":["urinet"]},"requiredActions":[]}]
                        """)));
        server.stubFor(get(urlPathEqualTo(USERS + "/inhaber-1/role-mappings/realm"))
                .willReturn(okJson("[{\"name\":\"inhaber\"}]")));

        assertThat(adapter(Duration.ofSeconds(2), Duration.ofSeconds(1)).inhaberZaehlen("urinet")).isEqualTo(1);
        server.verify(1, getRequestedFor(urlPathEqualTo(USERS)).withQueryParam("first", equalTo("50")));
    }

    @Test
    void benutzerLesenVerwirftUnbekannteErforderlicheAktionenNicht() {
        tokenAntwort();
        server.stubFor(get(urlPathEqualTo(USERS + "/benutzer-1")).willReturn(okJson("""
                {"id":"benutzer-1","enabled":true,"attributes":{"mandant":["urinet"]},
                 "requiredActions":["SYNTHETISCHE_AKTION"]}
                """)));
        server.stubFor(get(urlPathEqualTo(USERS + "/benutzer-1/role-mappings/realm")).willReturn(okJson("[]")));
        assertThatThrownBy(() -> adapter(Duration.ofSeconds(2), Duration.ofSeconds(1))
                .benutzerLesen("urinet", "benutzer-1"))
                .isInstanceOfSatisfying(FachFehler.class,
                        fehler -> assertThat(fehler.code()).isEqualTo(Fehlercode.IDENTITAETSDIENST_FEHLER));
    }

    @Test
    void inhaberZaehlenMeldetFehlerStattNullWennBenutzerNichtLesbarIst() {
        tokenAntwort();
        server.stubFor(get(urlPathEqualTo(USERS))
                .willReturn(okJson("[{\"id\":\"inhaber-1\",\"enabled\":true}]")));
        server.stubFor(get(urlPathEqualTo(USERS + "/inhaber-1")).willReturn(aResponse().withStatus(404)));

        assertThatThrownBy(() -> adapter(Duration.ofSeconds(2), Duration.ofSeconds(1)).inhaberZaehlen("urinet"))
                .isInstanceOfSatisfying(FachFehler.class,
                        fehler -> assertThat(fehler.code()).isEqualTo(Fehlercode.IDENTITAETSDIENST_FEHLER));
    }

    @Test
    void dreiKonflikteOeffnenDenSchutzschalterNichtUndWerdenWeitergereicht() {
        tokenAntwort();
        server.stubFor(post(urlPathEqualTo(USERS)).willReturn(aResponse().withStatus(409)));
        KeycloakAdminAdapter adapter = adapter(Duration.ofSeconds(2), Duration.ofSeconds(1));

        for (int i = 0; i < 4; i++) {
            assertThatThrownBy(() -> adapter.anlegen("urinet",
                    new Neuanlage("synthetic-user", "user@example.invalid", "Ada", "Muster"), Rolle.MITARBEITER))
                    .isInstanceOf(KonfliktException.class);
        }

        server.verify(4, postRequestedFor(urlPathEqualTo(USERS)));
    }

    @Test
    void andere4xxZaehlenNichtFuerDenSchutzschalter() {
        tokenAntwort();
        server.stubFor(post(urlPathEqualTo(USERS)).willReturn(aResponse().withStatus(400)));
        KeycloakAdminAdapter adapter = adapter(Duration.ofSeconds(2), Duration.ofSeconds(1));

        for (int i = 0; i < 4; i++) {
            assertThatThrownBy(() -> adapter.anlegen("urinet",
                    new Neuanlage("synthetic-user", "user@example.invalid", "Ada", "Muster"), Rolle.MITARBEITER))
                    .isInstanceOfSatisfying(FachFehler.class,
                            fehler -> assertThat(fehler.code()).isEqualTo(Fehlercode.IDENTITAETSDIENST_FEHLER));
        }

        server.verify(4, postRequestedFor(urlPathEqualTo(USERS)));
    }

    @Test
    void dreiMal403DesDienstkontosOeffnenDenSchutzschalter() {
        tokenAntwort();
        server.stubFor(get(urlPathEqualTo(USERS + "/benutzer-1")).willReturn(aResponse().withStatus(403)));
        KeycloakAdminAdapter adapter = adapter(Duration.ofSeconds(2), Duration.ofSeconds(1));

        for (int i = 0; i < 3; i++) {
            assertThatThrownBy(() -> adapter.benutzerLesen("urinet", "benutzer-1"))
                    .isInstanceOfSatisfying(FachFehler.class,
                            fehler -> assertThat(fehler.code()).isEqualTo(Fehlercode.IDENTITAETSDIENST_FEHLER));
        }
        assertThatThrownBy(() -> adapter.benutzerLesen("urinet", "benutzer-1"))
                .isInstanceOfSatisfying(FachFehler.class,
                        fehler -> assertThat(fehler.code())
                                .isEqualTo(Fehlercode.IDENTITAETSDIENST_NICHT_ERREICHBAR));

        server.verify(3, getRequestedFor(urlPathEqualTo(USERS + "/benutzer-1")));
    }

    @Test
    void ungueltigerTokenWirdBei401VerworfenUndLesendEinmalMitNeuemTokenWiederholt() {
        server.stubFor(post(urlPathEqualTo(TOKEN)).inScenario("token").whenScenarioStateIs("Started")
                .willReturn(okJson("{\"access_token\":\"alt\",\"expires_in\":300}"))
                .willSetStateTo("neu"));
        server.stubFor(post(urlPathEqualTo(TOKEN)).inScenario("token").whenScenarioStateIs("neu")
                .willReturn(okJson("{\"access_token\":\"neu\",\"expires_in\":300}")));
        server.stubFor(get(urlPathEqualTo(USERS + "/benutzer-1"))
                .withHeader("Authorization", equalTo("Bearer alt")).willReturn(aResponse().withStatus(401)));
        benutzerStub("benutzer-1", "urinet", "neu");
        server.stubFor(get(urlPathEqualTo(USERS + "/benutzer-1/role-mappings/realm")).willReturn(okJson("[]")));
        server.stubFor(get(urlPathEqualTo(USERS + "/benutzer-1/credentials")).willReturn(okJson("[]")));
        server.stubFor(get(urlPathEqualTo(USERS + "/benutzer-1/federated-identity")).willReturn(okJson("[]")));

        var benutzer = adapter(Duration.ofSeconds(2), Duration.ofSeconds(1)).benutzerLesen("urinet", "benutzer-1");

        assertThat(benutzer.id()).isEqualTo("benutzer-1");
        server.verify(2, postRequestedFor(urlPathEqualTo(TOKEN)));
    }

    @Test
    void schreibaufrufWirdBei401NichtWiederholtAberTokenWirdVerworfen() {
        tokenAntwort();
        benutzerStub("benutzer-1", "urinet");
        server.stubFor(delete(urlPathEqualTo(USERS + "/benutzer-1")).willReturn(aResponse().withStatus(401)));
        KeycloakAdminAdapter adapter = adapter(Duration.ofSeconds(2), Duration.ofSeconds(1));

        assertThatThrownBy(() -> adapter.loeschen("urinet", "benutzer-1")).isInstanceOf(FachFehler.class);
        assertThatThrownBy(() -> adapter.loeschen("urinet", "benutzer-1")).isInstanceOf(FachFehler.class);

        server.verify(2, deleteRequestedFor(urlPathEqualTo(USERS + "/benutzer-1")));
        server.verify(2, postRequestedFor(urlPathEqualTo(TOKEN)));
    }

    @Test
    void tokenWirdWiederverwendetUndNachAblaufMinusPufferNeuGeholt() {
        tokenAntwort();
        benutzerStub("benutzer-1", "urinet");
        server.stubFor(delete(urlPathEqualTo(USERS + "/benutzer-1")).willReturn(aResponse().withStatus(204)));
        SteuerbareUhr uhr = new SteuerbareUhr();
        KeycloakAdminAdapter adapter = adapter(Duration.ofSeconds(2), Duration.ofSeconds(1), uhr);

        adapter.loeschen("urinet", "benutzer-1");
        uhr.vor(Duration.ofSeconds(200));
        adapter.loeschen("urinet", "benutzer-1");
        server.verify(1, postRequestedFor(urlPathEqualTo(TOKEN)));

        uhr.vor(Duration.ofSeconds(71));
        adapter.loeschen("urinet", "benutzer-1");
        server.verify(2, postRequestedFor(urlPathEqualTo(TOKEN)));
    }

    @Test
    void verbindungPruefenErzwingtTrotzGueltigemCacheEinenEchtenTokenabruf() {
        tokenAntwort();
        KeycloakAdminAdapter adapter = adapter(Duration.ofSeconds(2), Duration.ofSeconds(1));

        adapter.verbindungPruefen();
        adapter.verbindungPruefen();

        server.verify(2, postRequestedFor(urlPathEqualTo(TOKEN)));
    }

    @Test
    void schutzschalterSchliesstNachDreissigSekundenWieder() {
        tokenAntwort();
        server.stubFor(get(urlPathEqualTo(USERS + "/benutzer-1")).willReturn(aResponse().withStatus(503)));
        SteuerbareUhr uhr = new SteuerbareUhr();
        KeycloakAdminAdapter adapter = adapter(Duration.ofSeconds(2), Duration.ofSeconds(1), uhr);
        for (int i = 0; i < 3; i++) {
            assertThatThrownBy(() -> adapter.benutzerLesen("urinet", "benutzer-1")).isInstanceOf(FachFehler.class);
        }
        assertThatThrownBy(() -> adapter.benutzerLesen("urinet", "benutzer-1")).isInstanceOf(FachFehler.class);
        server.verify(6, getRequestedFor(urlPathEqualTo(USERS + "/benutzer-1")));

        uhr.vor(Duration.ofSeconds(31));
        assertThatThrownBy(() -> adapter.benutzerLesen("urinet", "benutzer-1")).isInstanceOf(FachFehler.class);

        server.verify(8, getRequestedFor(urlPathEqualTo(USERS + "/benutzer-1")));
    }

    @Test
    void fehlerfensterBeginntNachDreissigSekundenNeu() {
        tokenAntwort();
        server.stubFor(get(urlPathEqualTo(USERS + "/benutzer-1")).willReturn(aResponse().withStatus(503)));
        SteuerbareUhr uhr = new SteuerbareUhr();
        KeycloakAdminAdapter adapter = adapter(Duration.ofSeconds(2), Duration.ofSeconds(1), uhr);
        for (int i = 0; i < 2; i++) {
            assertThatThrownBy(() -> adapter.benutzerLesen("urinet", "benutzer-1")).isInstanceOf(FachFehler.class);
        }
        uhr.vor(Duration.ofSeconds(31));
        assertThatThrownBy(() -> adapter.benutzerLesen("urinet", "benutzer-1")).isInstanceOf(FachFehler.class);
        assertThatThrownBy(() -> adapter.benutzerLesen("urinet", "benutzer-1")).isInstanceOf(FachFehler.class);

        server.verify(8, getRequestedFor(urlPathEqualTo(USERS + "/benutzer-1")));
    }

    @Test
    void schreibaktionenAufBenutzerEinesFremdenMandantenWerdenNichtAusgefuehrt() {
        tokenAntwort();
        benutzerStub("fremd-1", "vhf-gsk");
        KeycloakAdminAdapter adapter = adapter(Duration.ofSeconds(2), Duration.ofSeconds(1));

        for (Runnable aufruf : new Runnable[] {
                () -> adapter.loeschen("urinet", "fremd-1"),
                () -> adapter.aktivieren("urinet", "fremd-1", false),
                () -> adapter.rolleSetzen("urinet", "fremd-1", Rolle.INHABER),
                () -> adapter.otpEntfernen("urinet", "fremd-1"),
                () -> adapter.googleLoesen("urinet", "fremd-1"),
                () -> adapter.benutzerLesen("urinet", "fremd-1")}) {
            assertThatThrownBy(aufruf::run).isInstanceOfSatisfying(FachFehler.class,
                    fehler -> assertThat(fehler.code()).isEqualTo(Fehlercode.NICHT_GEFUNDEN));
        }

        server.verify(0, deleteRequestedFor(urlPathMatching(".*")));
        server.verify(0, putRequestedFor(urlPathMatching(".*")));
        server.verify(0, postRequestedFor(urlPathMatching("/admin/.*")));
    }

    @Test
    void aktivierenSendetVollstaendigeDarstellungMitMandantenattribut() {
        tokenAntwort();
        benutzerStub("benutzer-1", "urinet");
        server.stubFor(put(urlPathEqualTo(USERS + "/benutzer-1")).willReturn(aResponse().withStatus(204)));

        adapter(Duration.ofSeconds(2), Duration.ofSeconds(1)).aktivieren("urinet", "benutzer-1", false);

        server.verify(1, putRequestedFor(urlPathEqualTo(USERS + "/benutzer-1"))
                .withRequestBody(com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath("$.enabled",
                        equalTo("false"))));
    }

    @Test
    void anlegenSetztErforderlicheAktionenSchonBeimErstellen() {
        tokenAntwort();
        server.stubFor(post(urlPathEqualTo(USERS)).willReturn(aResponse().withStatus(201)
                .withHeader("Location", server.baseUrl() + USERS + "/neu-1")));
        benutzerStub("neu-1", "urinet");
        server.stubFor(get(urlPathEqualTo(USERS + "/neu-1/role-mappings/realm")).willReturn(okJson("[]")));
        server.stubFor(get(urlPathEqualTo("/admin/realms/urinet-archiv/roles/mitarbeiter"))
                .willReturn(okJson("{\"id\":\"r1\",\"name\":\"mitarbeiter\"}")));
        server.stubFor(post(urlPathEqualTo(USERS + "/neu-1/role-mappings/realm"))
                .willReturn(aResponse().withStatus(204)));

        assertThat(adapter(Duration.ofSeconds(2), Duration.ofSeconds(1)).anlegen("urinet",
                new Neuanlage("synthetic-user", "user@example.invalid", "Ada", "Muster"), Rolle.MITARBEITER))
                .isEqualTo("neu-1");

        server.verify(1, postRequestedFor(urlPathEqualTo(USERS)).withRequestBody(equalToJson("""
                {"username":"synthetic-user","email":"user@example.invalid","firstName":"Ada","lastName":"Muster",
                 "enabled":true,"attributes":{"mandant":["urinet"]},
                 "requiredActions":["VERIFY_EMAIL","UPDATE_PASSWORD","CONFIGURE_TOTP"]}
                """)));
    }

    @Test
    void anlegenLoeschtBenutzerBeiFehlerEvenBeiOffenemSchutzschalter() {
        tokenAntwort();
        server.stubFor(post(urlPathEqualTo(USERS)).willReturn(aResponse().withStatus(201)
                .withHeader("Location", server.baseUrl() + USERS + "/neu-1")));
        benutzerStub("neu-1", "urinet");
        server.stubFor(get(urlPathEqualTo(USERS + "/neu-1/role-mappings/realm")).willReturn(aResponse().withStatus(503)));
        server.stubFor(get(urlPathEqualTo("/admin/realms/urinet-archiv/roles/mitarbeiter"))
                .willReturn(okJson("{\"id\":\"r1\",\"name\":\"mitarbeiter\"}")));
        server.stubFor(delete(urlPathEqualTo(USERS + "/neu-1")).willReturn(aResponse().withStatus(204)));

        assertThatThrownBy(() -> adapter(Duration.ofSeconds(2), Duration.ofSeconds(1)).anlegen("urinet",
                new Neuanlage("synthetic-user", "user@example.invalid", "Ada", "Muster"), Rolle.MITARBEITER))
                .isInstanceOfSatisfying(AnlageException.class, fehler -> {
                    assertThat(fehler.ziel()).isEqualTo("neu-1");
                    assertThat(fehler.entfernt()).isTrue();
                    assertThat(fehler.getCause()).isInstanceOfSatisfying(FachFehler.class,
                            ursache -> assertThat(ursache.code())
                                    .isEqualTo(Fehlercode.IDENTITAETSDIENST_NICHT_ERREICHBAR));
                });

        server.verify(1, deleteRequestedFor(urlPathEqualTo(USERS + "/neu-1")));
    }

    @Test
    void anlegenMeldetZielUndUnklareKompensation() {
        tokenAntwort();
        server.stubFor(post(urlPathEqualTo(USERS)).willReturn(aResponse().withStatus(201)
                .withHeader("Location", server.baseUrl() + USERS + "/neu-1")));
        benutzerStub("neu-1", "urinet");
        server.stubFor(get(urlPathEqualTo(USERS + "/neu-1/role-mappings/realm")).willReturn(aResponse().withStatus(503)));
        server.stubFor(delete(urlPathEqualTo(USERS + "/neu-1")).willReturn(aResponse().withStatus(503)));

        assertThatThrownBy(() -> adapter(Duration.ofSeconds(2), Duration.ofSeconds(1)).anlegen("urinet",
                new Neuanlage("synthetic-user", "user@example.invalid", "Ada", "Muster"), Rolle.MITARBEITER))
                .isInstanceOfSatisfying(AnlageException.class, fehler -> {
                    assertThat(fehler.ziel()).isEqualTo("neu-1");
                    assertThat(fehler.entfernt()).isFalse();
                    assertThat(fehler.getSuppressed()).hasSize(1);
                });

        server.verify(1, deleteRequestedFor(urlPathEqualTo(USERS + "/neu-1")));
    }

    @Test
    void anlegenWertetAngenommeneAberNichtAbgeschlosseneLoeschungAlsUnklar() {
        tokenAntwort();
        server.stubFor(post(urlPathEqualTo(USERS)).willReturn(aResponse().withStatus(201)
                .withHeader("Location", server.baseUrl() + USERS + "/neu-1")));
        benutzerStub("neu-1", "urinet");
        server.stubFor(get(urlPathEqualTo(USERS + "/neu-1/role-mappings/realm")).willReturn(aResponse().withStatus(503)));
        server.stubFor(delete(urlPathEqualTo(USERS + "/neu-1")).willReturn(aResponse().withStatus(202)));

        assertThatThrownBy(() -> adapter(Duration.ofSeconds(2), Duration.ofSeconds(1)).anlegen("urinet",
                new Neuanlage("synthetic-user", "user@example.invalid", "Ada", "Muster"), Rolle.MITARBEITER))
                .isInstanceOfSatisfying(AnlageException.class, fehler -> {
                    assertThat(fehler.ziel()).isEqualTo("neu-1");
                    assertThat(fehler.entfernt()).isFalse();
                    assertThat(fehler.getSuppressed()).hasSize(1);
                });
    }

    @Test
    void rolleSetzenBehaeltBeiFehlerDerZielrolleDieBisherigeRolle() {
        tokenAntwort();
        benutzerStub("benutzer-1", "urinet");
        server.stubFor(get(urlPathEqualTo(USERS + "/benutzer-1/role-mappings/realm"))
                .willReturn(okJson("[{\"id\":\"r0\",\"name\":\"inhaber\"}]")));
        server.stubFor(get(urlPathEqualTo("/admin/realms/urinet-archiv/roles/mitarbeiter"))
                .willReturn(aResponse().withStatus(503)));

        assertThatThrownBy(() -> adapter(Duration.ofSeconds(2), Duration.ofSeconds(1))
                .rolleSetzen("urinet", "benutzer-1", Rolle.MITARBEITER)).isInstanceOf(FachFehler.class);

        server.verify(0, deleteRequestedFor(urlPathEqualTo(USERS + "/benutzer-1/role-mappings/realm")));
    }

    @Test
    void rolleSetzenFuegtZielrolleHinzuUndEntferntDanachDieUebrigen() {
        tokenAntwort();
        benutzerStub("benutzer-1", "urinet");
        server.stubFor(get(urlPathEqualTo(USERS + "/benutzer-1/role-mappings/realm")).willReturn(okJson("""
                [{"id":"r0","name":"inhaber"},{"id":"r9","name":"offline_access"}]
                """)));
        server.stubFor(get(urlPathEqualTo("/admin/realms/urinet-archiv/roles/mitarbeiter"))
                .willReturn(okJson("{\"id\":\"r1\",\"name\":\"mitarbeiter\"}")));
        server.stubFor(post(urlPathEqualTo(USERS + "/benutzer-1/role-mappings/realm"))
                .inScenario("rollenfolge").whenScenarioStateIs(STARTED).willSetStateTo("zugewiesen")
                .willReturn(aResponse().withStatus(204)));
        server.stubFor(delete(urlPathEqualTo(USERS + "/benutzer-1/role-mappings/realm"))
                .inScenario("rollenfolge").whenScenarioStateIs("zugewiesen")
                .willReturn(aResponse().withStatus(204)));

        adapter(Duration.ofSeconds(2), Duration.ofSeconds(1)).rolleSetzen("urinet", "benutzer-1", Rolle.MITARBEITER);

        server.verify(1, postRequestedFor(urlPathEqualTo(USERS + "/benutzer-1/role-mappings/realm")));
        server.verify(1, deleteRequestedFor(urlPathEqualTo(USERS + "/benutzer-1/role-mappings/realm"))
                .withRequestBody(com.github.tomakehurst.wiremock.client.WireMock.containing("inhaber"))
                .withRequestBody(com.github.tomakehurst.wiremock.client.WireMock.notMatching(".*offline_access.*")));
    }

    @Test
    void punktSegmenteWerdenAbgelehnt() {
        KeycloakAdminAdapter adapter = adapter(Duration.ofSeconds(2), Duration.ofSeconds(1));

        assertThatThrownBy(() -> adapter.benutzerLesen("urinet", "..")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> adapter.loeschen("urinet", ".")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> adapter.sperreAufheben("urinet", "..")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> adapter.pruefePositivliste("DELETE", USERS + "/.."))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> adapter.pruefePositivliste("DELETE", USERS + "/benutzer-1/credentials/.."))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> adapter.pruefePositivliste("GET", USERS + "/%2e%2e"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> KeycloakAdminAdapter.segment("..")).isInstanceOf(IllegalArgumentException.class);
        assertThat(KeycloakAdminAdapter.segment("a.b-1")).isEqualTo("a.b-1");
        server.verify(0, getRequestedFor(urlPathMatching(".*")));
    }

    @Test
    void positivlisteErlaubtNurDenListenAbrufDerVerknuepfungenZumLesen() {
        KeycloakAdminAdapter adapter = adapter(Duration.ofSeconds(2), Duration.ofSeconds(1));

        adapter.pruefePositivliste("GET", USERS + "/benutzer-1/federated-identity");
        adapter.pruefePositivliste("DELETE", USERS + "/benutzer-1/federated-identity/google");
        assertThatThrownBy(() -> adapter.pruefePositivliste("GET", USERS + "/benutzer-1/federated-identity/google"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> adapter.pruefePositivliste("DELETE", USERS + "/benutzer-1/federated-identity"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private void benutzerStub(String id, String mandant) {
        benutzerStub(id, mandant, null);
    }

    private void benutzerStub(String id, String mandant, String token) {
        var stub = get(urlPathEqualTo(USERS + "/" + id));
        if (token != null) {
            stub = stub.withHeader("Authorization", equalTo("Bearer " + token));
        }
        server.stubFor(stub.willReturn(okJson("{\"id\":\"" + id + "\",\"username\":\"u\",\"enabled\":true,"
                + "\"attributes\":{\"mandant\":[\"" + mandant + "\"]},\"requiredActions\":[]}")));
    }

    private static final class SteuerbareUhr extends Clock {
        private Instant jetzt = Instant.parse("2026-11-20T10:00:00Z");

        void vor(Duration dauer) {
            jetzt = jetzt.plus(dauer);
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return jetzt;
        }
    }

    private void tokenAntwort() {
        server.stubFor(post(urlPathEqualTo(TOKEN)).willReturn(okJson("""
                {"access_token":"synthetic-token","expires_in":300}
                """)));
    }

    private KeycloakAdminAdapter adapter(Duration verbindung, Duration anfrage) {
        return adapter(verbindung, anfrage, Clock.systemUTC());
    }

    private KeycloakAdminAdapter adapter(Duration verbindung, Duration anfrage, Clock uhr) {
        return new KeycloakAdminAdapter(new ObjectMapper(), server.baseUrl(), "urinet-archiv",
                "archiv-verwaltung", "synthetic-client-secret", "archiv-pwa", "", verbindung, anfrage,
                uhr);
    }
}
