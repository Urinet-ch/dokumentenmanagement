package ch.urinet.archiv.benutzer.adapter.out.keycloak;

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
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
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
        server.stubFor(get(urlPathEqualTo(USERS + "/benutzer-1/federated-identity/google"))
                .willReturn(aResponse().withStatus(404)));

        var benutzer = adapter(Duration.ofSeconds(2), Duration.ofSeconds(1))
                .benutzerSuchen("urinet", "", 0);

        assertThat(benutzer).hasSize(1);
        assertThat(benutzer.getFirst().id()).isEqualTo("benutzer-1");
        server.verify(0, getRequestedFor(urlPathEqualTo(USERS + "/benutzer-2/role-mappings/realm")));
        server.verify(0, getRequestedFor(urlPathEqualTo(USERS + "/benutzer-3/role-mappings/realm")));
        server.verify(0, getRequestedFor(urlPathEqualTo(USERS + "/benutzer-4/role-mappings/realm")));
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

    private void tokenAntwort() {
        server.stubFor(post(urlPathEqualTo(TOKEN)).willReturn(okJson("""
                {"access_token":"synthetic-token","expires_in":300}
                """)));
    }

    private KeycloakAdminAdapter adapter(Duration verbindung, Duration anfrage) {
        return new KeycloakAdminAdapter(new ObjectMapper(), server.baseUrl(), "urinet-archiv",
                "archiv-verwaltung", "synthetic-client-secret", "archiv-pwa", "", verbindung, anfrage,
                Clock.systemUTC());
    }
}
