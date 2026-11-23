package ch.urinet.archiv.sicherheit;

import ch.urinet.archiv.gemeinsam.Testanmeldung;
import ch.urinet.archiv.mandant.application.MandantProvisionierung;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agroal.api.AgroalDataSource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;

import java.net.HttpCookie;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Req 2.1 gegen den in Keycloak importierten Realm-Export (Stufe A und B): ohne Passkey oder TOTP kein Token über
 * irgendeinen Client oder Ablauf, und die API nimmt nur Tokens mit Audience {@code archiv-api} an. Gegenprobe:
 * {@code mvn verify -Dtest=RealmExportKeycloakTest -Darchiv.realm-export=<alter Export>} muss rot werden.
 */
@Tag("start-3")
@QuarkusTest
@TestProfile(RealmExportKeycloakTest.MitExport.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RealmExportKeycloakTest {

    public static class MitExport implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("quarkus.keycloak.devservices.enabled", "false",
                    "archiv.verwaltung.step-up-gueltigkeit", "PT1M");
        }

        @Override
        public List<TestResourceEntry> testResources() {
            return List.of(new TestResourceEntry(ExportKeycloak.class));
        }
    }

    private static final String REALM = "/admin/realms/urinet-archiv";
    private static final String PASSWORT = "Probe-Passwort-1";
    // Pfad unter /api/, den es nie geben wird: angemeldet ergibt immer 404
    private static final String PROBE_PFAD = "/api/v1/anmeldung-probe";

    private String admin;

    @Inject
    AgroalDataSource datenbank;

    @Inject
    MandantProvisionierung provisionierung;

    @BeforeAll
    void vorbereiten() throws SQLException {
        admin = given().formParam("grant_type", "password").formParam("client_id", "admin-cli")
                .formParam("username", ExportKeycloak.ADMIN).formParam("password", ExportKeycloak.ADMIN_PASSWORT)
                .post(ExportKeycloak.basisUrl + "/realms/master/protocol/openid-connect/token").path("access_token");
        try (Connection verbindung = datenbank.getConnection();
             PreparedStatement abfrage = verbindung.prepareStatement(
                     "select 1 from public.mandant where code = 'urinet' and aktiv");
             ResultSet zeile = abfrage.executeQuery()) {
            if (!zeile.next()) {
                provisionierung.anlegen("urinet", null, null);
            }
        }
        // Benutzer und Probe-Clients entstehen erst nach dem Import, wie es ein Administrator täte
        importieren(Map.of("ifResourceExists", "SKIP",
                "users", List.of(benutzer("ohne-totp", false), benutzer("mit-totp-1", true),
                        benutzer("mit-totp-2", true), benutzer("step-up", true)),
                "clients", List.of(probeClient("probe-fremd", false), probeClient("probe-mit-audience", true))));
        String stepUpBenutzer = adminGet(REALM + "/users?exact=true&username=step-up").path("[0].id");
        Map<String, Object> inhaberRolle = adminGet(REALM + "/roles/inhaber").as(Map.class);
        given().auth().oauth2(admin).contentType(ContentType.JSON).body(List.of(inhaberRolle))
                .post(ExportKeycloak.basisUrl + REALM + "/users/" + stepUpBenutzer + "/role-mappings/realm")
                .then().statusCode(204);
        // Ohne offene Pflichtaktion: geprüft wird die Flow-Bindung, nicht CONFIGURE_TOTP als Standardaktion
        String id = adminGet(REALM + "/users?exact=true&username=ohne-totp").path("[0].id");
        given().auth().oauth2(admin).contentType(ContentType.JSON).body(Map.of("requiredActions", List.of()))
                .put(ExportKeycloak.basisUrl + REALM + "/users/" + id).then().statusCode(204);
    }

    @Test
    @Order(1)
    void positivkontrolleMitEingebautemDirectGrant() {
        String eingebaut = null;
        for (Map<String, Object> flow : adminGet(REALM + "/authentication/flows").jsonPath().<Map<String, Object>>getList("$")) {
            if ("direct grant".equals(flow.get("alias"))) {
                eingebaut = (String) flow.get("id");
            }
        }
        assertThat(eingebaut).as("eingebauter Ablauf «direct grant»").isNotNull();
        Map<String, Object> client = new HashMap<>(probeClient("probe-override", false));
        client.put("authenticationFlowBindingOverrides", Map.of("direct_grant", eingebaut));
        importieren(Map.of("ifResourceExists", "SKIP", "clients", List.of(client)));
        try {
            // Der Test erkennt ein Token, wo es eines gibt, und die Prüfung je Client schlägt an
            Response antwort = Testanmeldung.anmelden(realmUrl(), "probe-override", "ohne-totp", PASSWORT, null);
            assertThat(antwort.statusCode()).isEqualTo(200);
            assertThat(ungeschuetzteClients()).containsExactly("probe-override");
        } finally {
            loeschen("/clients/" + clientId("probe-override"));
        }
    }

    @Test
    @Order(2)
    void jedeFlowBindungVerlangtTotpOderPasskey() {
        Map<String, Object> realm = adminGet(REALM).as(Map.class);
        List<Map<String, Object>> browser = schritte((String) realm.get("browserFlow"));
        assertThat(browser).hasSize(6);
        assertThat(browser.get(4).get("authenticationConfig")).isInstanceOf(String.class);
        String configId = (String) browser.get(4).get("authenticationConfig");
        Map<String, String> bedingung = adminGet(REALM + "/authentication/config/{id}", configId).path("config");
        assertThat(OtpPruefung.passkeyOderOtp(browser, bedingung)).isTrue();
        for (String bindung : List.of("directGrantFlow", "resetCredentialsFlow")) {
            String alias = (String) realm.get(bindung);
            assertThat(OtpPruefung.otpOhneBedingung(schritte(alias))).as(bindung + " = " + alias).isTrue();
        }
    }

    @Test
    @Order(2)
    void passkeyEinstellungenUndKontoScopeWerdenImportiert() {
        Map<String, Object> realm = adminGet(REALM).as(Map.class);
        assertThat(realm).containsEntry("webAuthnPolicyPasswordlessRpId", "localhost")
                .containsEntry("webAuthnPolicyPasswordlessAttestationConveyancePreference", "none")
                .containsEntry("webAuthnPolicyPasswordlessResidentKey", "required")
                .containsEntry("webAuthnPolicyPasswordlessUserVerificationRequirement", "required")
                .containsEntry("webAuthnPolicyPasswordlessPasskeysEnabled", true)
                .containsEntry("webAuthnPolicyPasswordlessMediation", "conditional");
        List<String> kontoRollen = adminGet(REALM + "/clients/{id}/scope-mappings/clients/{account}",
                clientId("account-console"), clientId("account")).jsonPath().getList("name");
        assertThat(kontoRollen).containsExactlyInAnyOrder("manage-account", "view-profile");
        for (String alias : List.of("webauthn-register-passwordless", "delete_credential")) {
            Response aktion = adminGet(REALM + "/authentication/required-actions/{alias}", alias);
            assertThat(aktion.jsonPath().getBoolean("enabled")).isTrue();
            assertThat(aktion.jsonPath().getBoolean("defaultAction")).isFalse();
        }
    }

    @Test
    @Order(2)
    void keinClientUeberschreibtDieFlowBindung() {
        assertThat(ungeschuetzteClients()).isEmpty();
    }

    @Test
    @Order(2)
    void googleProviderWirdMitTestSecretImportiert() {
        Map<String, Object> google = adminGet(REALM + "/identity-provider/instances/google").as(Map.class);
        assertThat(google.get("alias")).isEqualTo("google");
        assertThat(google.get("providerId")).isEqualTo("google");
        assertThat(google.get("enabled")).isEqualTo(false);
        assertThat(google.get("trustEmail")).isEqualTo(false);
        assertThat(google.get("storeToken")).isEqualTo(false);
        assertThat(google.get("firstBrokerLoginFlowAlias")).isEqualTo("google-nur-verknuepft");
        assertThat(google.get("postBrokerLoginFlowAlias")).isEqualTo("google-totp-pflicht");
        Map<String, String> googleConfig = (Map<String, String>) google.get("config");
        assertThat(googleConfig.get("syncMode")).isEqualTo("IMPORT");
        assertThat(googleConfig.get("clientId")).isEqualTo("synthetische-google-client-id.apps.test");
        assertThat(googleConfig.get("clientSecret")).isEqualTo("**********");
        Object mappers = google.get("mappers");
        assertThat(mappers == null || mappers.equals(List.of())).isTrue();
        assertThat(identityProvider()).containsExactlyInAnyOrder("google", "archiv-test-oidc");
        String oidcProviderId = adminGet(REALM + "/identity-provider/instances/archiv-test-oidc").path("providerId");
        assertThat(oidcProviderId).isEqualTo("oidc");
    }

    @Test
    @Order(2)
    void pwaAdressenWerdenAusDerKonfigurationImportiert() {
        Response pwa = adminGet(REALM + "/clients/{id}", clientId("archiv-pwa"));
        assertThat(pwa.jsonPath().<String>getList("redirectUris"))
                .containsExactly(ExportKeycloak.PWA_TEST_URL + "/*");
        assertThat(pwa.jsonPath().<String>getList("webOrigins"))
                .containsExactly(ExportKeycloak.PWA_TEST_URL);
        assertThat(pwa.jsonPath().getString("attributes.'post.logout.redirect.uris'"))
                .isEqualTo(ExportKeycloak.PWA_TEST_URL + "/*");
    }

    @Test
    @Order(2)
    void verknuepfungIstNichtTeilDerBenutzerrolleUndEreignisseSindAktiv() {
        List<Map<String, Object>> composites = adminGet(
                REALM + "/clients/{id}/roles/manage-account/composites", clientId("account"))
                .jsonPath().getList("$");
        assertThat(composites).noneMatch(role -> "manage-account-links".equals(role.get("name")));

        Map<String, Object> realm = adminGet(REALM).as(Map.class);
        assertThat(realm.get("eventsEnabled")).isEqualTo(true);
        assertThat(realm.get("adminEventsEnabled")).isEqualTo(true);
        assertThat(realm.get("adminEventsDetailsEnabled")).isEqualTo(false);
        assertThat(realm.get("eventsExpiration")).isEqualTo(34560000);
        assertThat(((Map<String, Object>) realm.get("attributes")).get("adminEventsExpiration")).isEqualTo("34560000");
        assertThat(realm.get("accessTokenLifespan")).isEqualTo(300);
        assertThat(realm.get("actionTokenGeneratedByAdminLifespan")).isEqualTo(86400);
        assertThat(realm.get("passwordPolicy")).isEqualTo("length(12) and notUsername and notEmail");
        assertThat((List<String>) realm.get("enabledEventTypes"))
                .contains("LOGIN_ERROR", "IDENTITY_PROVIDER_LOGIN", "IDENTITY_PROVIDER_LOGIN_ERROR",
                        "IDENTITY_PROVIDER_FIRST_LOGIN_ERROR", "FEDERATED_IDENTITY_LINK",
                        "REMOVE_FEDERATED_IDENTITY");
    }

    @Test
    @Order(2)
    void verwaltungDienstkontoHatNurVorgeseheneRechte() throws Exception {
        String client = clientId("archiv-verwaltung");
        String konto = adminGet(REALM + "/clients/{id}/service-account-user", client).path("id");
        String realmManagement = clientId("realm-management");
        List<String> rollen = adminGet(REALM + "/users/{user}/role-mappings/clients/{client}", konto, realmManagement)
                .jsonPath().getList("name");
        assertThat(rollen).containsExactlyInAnyOrder("query-users", "view-users", "manage-users");
        assertThat(adminGet(REALM + "/users/{user}/role-mappings/realm", konto).jsonPath().<String>getList("name"))
                .doesNotContain("realm-admin");

        String testSecret = adminGet(REALM + "/clients/{id}/client-secret", client).path("value");
        assertThat(testSecret != null && !testSecret.isBlank() && !testSecret.contains("${")).isTrue();
        String token = given().formParam("grant_type", "client_credentials")
                .formParam("client_id", "archiv-verwaltung")
                .formParam("client_secret", testSecret)
                .post(realmUrl() + "/protocol/openid-connect/token")
                .then().statusCode(200).extract().path("access_token");
        JsonNode claims = new ObjectMapper().readTree(Base64.getUrlDecoder().decode(token.split("\\.")[1]));
        assertThat(claims.path("scope").asText()).contains("kc-admin-api");
        assertThat(claims.path("resource_access").path("realm-management").path("roles").toString())
                .contains("query-users", "view-users", "manage-users")
                .doesNotContain("realm-admin", "manage-realm", "manage-clients", "impersonation");
        given().auth().oauth2(token).get(ExportKeycloak.basisUrl + REALM + "/users?max=1").then().statusCode(200);
        given().auth().oauth2(token).get(ExportKeycloak.basisUrl + REALM + "/clients").then().statusCode(403);
    }

    @Test
    @Order(2)
    void einladungOhnePasswortErreichtMailpit() {
        assertThat(adminGet(REALM).jsonPath().getString("emailTheme")).isEqualTo("urinet-archiv");
        assertThat(adminGet(REALM).jsonPath().getString("loginTheme")).isEqualTo("urinet-archiv");
        String name = "einladung-test";
        Map<String, Object> benutzer = Map.of("username", name, "enabled", true,
                "email", "einladung@example.invalid", "firstName", "Probe", "lastName", "Einladung",
                "attributes", Map.of("mandant", List.of("urinet")));
        given().auth().oauth2(admin).contentType(ContentType.JSON).body(benutzer)
                .post(ExportKeycloak.basisUrl + REALM + "/users").then().statusCode(201);
        String id = adminGet(REALM + "/users?exact=true&username={name}", name).path("[0].id");
        try {
            given().auth().oauth2(admin).contentType(ContentType.JSON)
                    .body(List.of("VERIFY_EMAIL", "UPDATE_PASSWORD", "CONFIGURE_TOTP"))
                    .queryParam("lifespan", 86400)
                    .put(ExportKeycloak.basisUrl + REALM + "/users/{id}/execute-actions-email", id)
                    .then().statusCode(204);
            List<String> ids = given().queryParam("query", "to:einladung@example.invalid")
                    .get(ExportKeycloak.mailpitUrl + "/api/v1/search")
                    .then().statusCode(200).extract().jsonPath().getList("messages.ID");
            assertThat(ids).hasSize(1);
            Response mail = given().get(ExportKeycloak.mailpitUrl + "/api/v1/message/" + ids.getFirst())
                    .then().statusCode(200).extract().response();
            assertThat(mail.jsonPath().getString("Subject"))
                    .isEqualTo("UriNet Archiv: Zugang einrichten oder aktualisieren");
            assertThat(mail.jsonPath().getString("Text"))
                    .contains("Einmal-Link", "1 Tag", "angezeigten Schritte", "wenden Sie sich an die Verwaltung");
            assertThat(mail.jsonPath().getString("HTML")).contains("Zugang einrichten oder aktualisieren", "1 Tag");
            given().auth().oauth2(admin).contentType(ContentType.JSON)
                    .body(List.of("VERIFY_EMAIL", "UPDATE_PASSWORD", "CONFIGURE_TOTP"))
                    .queryParam("lifespan", 5400)
                    .put(ExportKeycloak.basisUrl + REALM + "/users/{id}/execute-actions-email", id)
                    .then().statusCode(204);
            List<String> beideIds = given().queryParam("query", "to:einladung@example.invalid")
                    .get(ExportKeycloak.mailpitUrl + "/api/v1/search")
                    .then().statusCode(200).extract().jsonPath().getList("messages.ID");
            assertThat(beideIds).hasSize(2);
            assertThat(beideIds.stream().map(mailId -> given()
                    .get(ExportKeycloak.mailpitUrl + "/api/v1/message/" + mailId)
                    .then().statusCode(200).extract().jsonPath().getString("Text")).toList())
                    .anySatisfy(text -> assertThat(text).contains("90 Minuten"));
        } finally {
            loeschen("/users/" + id);
        }
    }

    @Test
    @Order(5)
    void stepUpVerlangtFrischeAuthentifizierungUndPromptLoginErneuertAuthTime() throws Exception {
        String client = clientId("archiv-pwa");
        String erlaubteUmleitung = adminGet(REALM + "/clients/{id}", client)
                .jsonPath().<String>getList("redirectUris").getFirst();
        String rueckruf = erlaubteUmleitung.replace("*", "test-callback");
        var browser = new PwaBrowser(rueckruf);

        PwaBrowser.Anmeldung zuerst = browser.anmelden(false, true);
        assertThat(zuerst.claims().path("auth_time").isIntegralNumber()).isTrue();
        assertThat(zuerst.claims().path("mandant").asText()).isEqualTo("urinet");
        assertThat(zuerst.claims().path("realm_access").path("roles").toString()).contains("inhaber");
        long ersteZeit = zuerst.claims().path("auth_time").asLong();
        assertThat(ersteZeit).isPositive();
        Response frischeEinstellung = riskanteEinstellung(zuerst.accessToken());
        assertThat(frischeEinstellung.statusCode()).as(frischeEinstellung.asString()).isEqualTo(422);
        assertThat(frischeEinstellung.jsonPath().getString("fehlercode")).isEqualTo("KONFIGURATION_UNGUELTIG");
        gueltigeRiskanteEinstellung(zuerst.accessToken());

        PwaBrowser.Anmeldung sso = browser.anmelden(false, false);
        assertThat(sso.claims().path("auth_time").asLong()).isEqualTo(ersteZeit);

        Thread.sleep(61_000);
        Response zuAlt = stepUpAnfrage(zuerst.accessToken());
        assertThat(zuAlt.statusCode()).as(zuAlt.asString()).isEqualTo(401);
        assertThat(zuAlt.jsonPath().getString("fehlercode")).isEqualTo("STEP_UP_NOETIG");
        assertThat(zuAlt.header("WWW-Authenticate")).contains("insufficient_user_authentication", "max_age=\"60\"");
        Response alteEinstellung = riskanteEinstellung(zuerst.accessToken());
        assertThat(alteEinstellung.statusCode()).as(alteEinstellung.asString()).isEqualTo(401);
        assertThat(alteEinstellung.jsonPath().getString("fehlercode")).isEqualTo("STEP_UP_NOETIG");
        Response cookieAllein = stepUpAnfrage(sso.accessToken());
        assertThat(cookieAllein.statusCode()).isEqualTo(401);
        assertThat(cookieAllein.jsonPath().getString("fehlercode")).isEqualTo("STEP_UP_NOETIG");

        // Keycloak lässt denselben TOTP-Code nicht zweimal zu.
        Thread.sleep((31 - (System.currentTimeMillis() / 1000) % 30) * 1000);
        PwaBrowser.Anmeldung erneut = browser.anmelden(true, true);
        assertThat(erneut.claims().path("auth_time").isIntegralNumber()).isTrue();
        assertThat(erneut.claims().path("auth_time").asLong()).isGreaterThan(ersteZeit);
        Response frisch = stepUpAnfrage(erneut.accessToken());
        assertThat(frisch.statusCode()).isEqualTo(400);
        assertThat(frisch.jsonPath().getString("fehlercode")).isEqualTo("ANFRAGE_UNGUELTIG");
    }

    private Response stepUpAnfrage(String token) {
        return given().auth().oauth2(token).contentType(ContentType.JSON).body("{").post("/api/v1/benutzer");
    }

    private Response riskanteEinstellung(String token) {
        String pfad = "/api/v1/mandant/einstellungen";
        Response stand = given().auth().oauth2(token).get(pfad);
        assertThat(stand.statusCode()).as(stand.asString()).isEqualTo(200);
        return given().auth().oauth2(token).header("If-Match", stand.header("ETag"))
                .queryParam("bestaetigt", "chat.aufbewahrungMonate")
                .contentType("application/merge-patch+json").body("{\"chat\":{\"aufbewahrungMonate\":0}}")
                .patch(pfad);
    }

    private void gueltigeRiskanteEinstellung(String token) {
        String pfad = "/api/v1/mandant/einstellungen";
        Response ausgang = given().auth().oauth2(token).get("/api/v1/konfiguration");
        assertThat(ausgang.statusCode()).as(ausgang.asString()).isEqualTo(200);
        String bisher = ausgang.jsonPath().getString("einstellungen.geschaeftsjahrEnde");
        String neu = "06-30".equals(bisher) ? "09-30" : "06-30";
        Response geaendert = given().auth().oauth2(token).header("If-Match", ausgang.header("ETag"))
                .queryParam("bestaetigt", "geschaeftsjahrEnde")
                .contentType("application/merge-patch+json").body("{\"geschaeftsjahrEnde\":\"" + neu + "\"}")
                .patch(pfad);
        try {
            assertThat(geaendert.statusCode()).as(geaendert.asString()).isEqualTo(200);
            assertThat(geaendert.jsonPath().getBoolean("geaendert")).isTrue();
        } finally {
            if (geaendert.statusCode() == 200) {
                Response wiederhergestellt = given().auth().oauth2(token).header("If-Match", geaendert.header("ETag"))
                        .queryParam("bestaetigt", "geschaeftsjahrEnde").contentType(ContentType.JSON)
                        .body("{\"version\":" + ausgang.jsonPath().getInt("basisVersion") + "}")
                        .post(pfad + "/zuruecksetzen");
                assertThat(wiederhergestellt.statusCode()).as(wiederhergestellt.asString()).isEqualTo(200);
            }
        }
    }

    private static final class PwaBrowser {
        private static final Pattern FORM = Pattern.compile("(?is)<form\\b([^>]*)>(.*?)</form>");
        private static final Pattern ACTION = Pattern.compile("(?i)\\baction\\s*=\\s*['\"]([^'\"]*)['\"]");
        private final HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
        private final Map<String, String> cookies = new HashMap<>();
        private final String rueckruf;
        private final String verifier = "synthetischer-pkce-verifier-" + UUID.randomUUID();

        private PwaBrowser(String rueckruf) {
            this.rueckruf = rueckruf;
        }

        private record Anmeldung(JsonNode claims, String accessToken) {
        }

        private Anmeldung anmelden(boolean prompt, boolean interaktiv) throws Exception {
            String challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(
                    MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
            String url = realmUrl() + "/protocol/openid-connect/auth?client_id=archiv-pwa&response_type=code"
                    + "&scope=openid&redirect_uri=" + enc(rueckruf)
                    + "&code_challenge_method=S256&code_challenge=" + enc(challenge)
                    + "&state=" + enc(UUID.randomUUID().toString())
                    + (prompt ? "&prompt=login" : "");
            HttpResponse<String> antwort = senden(HttpRequest.newBuilder(URI.create(url)).GET());
            if (interaktiv) {
                assertThat(antwort.statusCode()).isEqualTo(200);
                Matcher css = Pattern.compile("href=\"([^\"]*css/urinet-archiv\\.css)\"").matcher(antwort.body());
                assertThat(css.find()).isTrue();
                HttpResponse<String> branding = senden(HttpRequest.newBuilder(
                        antwort.uri().resolve(css.group(1).replace("&amp;", "&"))).GET());
                assertThat(branding.statusCode()).isEqualTo(200);
                assertThat(branding.body()).contains("UriNet Archiv", "#eeb111");
                assertThat(antwort.body().contains("name=\"password\"")).as("Passwortformular vorhanden").isTrue();
                antwort = absenden(antwort, "password", Map.of("username", "step-up", "password", PASSWORT));
                assertThat(antwort.statusCode()).as("Passwort-Anmeldung: Sitzungsfehler=%s, Zugangsdatenfehler=%s",
                        antwort.body().contains("session has expired"), antwort.body().contains("Invalid username or password"))
                        .isEqualTo(200);
                assertThat(antwort.body().contains("name=\"otp\"")).as("TOTP-Formular vorhanden").isTrue();
                antwort = absenden(antwort, "otp", Map.of("otp",
                        Testanmeldung.totp(Testanmeldung.OTP_SECRET, System.currentTimeMillis() / 1000)));
            }
            for (int i = 0; i < 10; i++) {
                assertThat(antwort.statusCode()).isBetween(300, 399);
                URI ziel = antwort.uri().resolve(antwort.headers().firstValue("location").orElseThrow());
                if (ziel.toString().startsWith(rueckruf + "?")) {
                    String code = java.util.Arrays.stream(ziel.getRawQuery().split("&"))
                            .filter(paar -> paar.startsWith("code=")).findFirst().orElseThrow().substring(5);
                    String token = given().formParam("grant_type", "authorization_code")
                            .formParam("client_id", "archiv-pwa").formParam("redirect_uri", rueckruf)
                            .formParam("code_verifier", verifier)
                            .formParam("code", java.net.URLDecoder.decode(code, StandardCharsets.UTF_8))
                            .post(realmUrl() + "/protocol/openid-connect/token")
                            .then().statusCode(200).extract().path("access_token");
                    JsonNode claims = new ObjectMapper().readTree(Base64.getUrlDecoder().decode(token.split("\\.")[1]));
                    return new Anmeldung(claims, token);
                }
                antwort = senden(HttpRequest.newBuilder(ziel).GET());
            }
            throw new IllegalStateException("Zu viele Weiterleitungen bei der PWA-Anmeldung");
        }

        private HttpResponse<String> absenden(HttpResponse<String> seite, String feld, Map<String, String> werte)
                throws Exception {
            Matcher form = FORM.matcher(seite.body());
            while (form.find()) {
                if (form.group(2).contains("name=\"" + feld + "\"")) {
                    Matcher action = ACTION.matcher(form.group(1));
                    if (!action.find()) {
                        throw new IllegalStateException("Anmeldeformular ohne Ziel");
                    }
                    URI ziel = seite.uri().resolve(action.group(1).replace("&amp;", "&"));
                    String body = werte.entrySet().stream().map(e -> enc(e.getKey()) + "=" + enc(e.getValue()))
                            .collect(java.util.stream.Collectors.joining("&"));
                    return senden(HttpRequest.newBuilder(ziel)
                                    .header("Content-Type", "application/x-www-form-urlencoded")
                                    .POST(HttpRequest.BodyPublishers.ofString(body)));
                }
            }
            throw new IllegalStateException("Anmeldeformular fehlt");
        }

        private HttpResponse<String> senden(HttpRequest.Builder anfrage) throws Exception {
            if (!cookies.isEmpty()) {
                anfrage.header("Cookie", cookies.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue())
                        .collect(java.util.stream.Collectors.joining("; ")));
            }
            HttpResponse<String> antwort = client.send(anfrage.build(), HttpResponse.BodyHandlers.ofString());
            antwort.headers().allValues("set-cookie").stream().flatMap(c -> HttpCookie.parse(c).stream())
                    .forEach(c -> {
                        if (c.hasExpired()) {
                            cookies.remove(c.getName());
                        } else {
                            cookies.put(c.getName(), c.getValue());
                        }
                    });
            return antwort;
        }

        private static String enc(String wert) {
            return URLEncoder.encode(wert, StandardCharsets.UTF_8);
        }
    }

    @Test
    @Order(2)
    void oidcTestProviderKannNebenGoogleBestehen() {
        Map<String, Object> oidc = adminGet(REALM + "/identity-provider/instances/archiv-test-oidc").as(Map.class);
        assertThat(oidc.get("providerId")).isEqualTo("oidc");
        assertThat(((Map<String, String>) oidc.get("config")).get("authorizationUrl"))
                .startsWith("http://host.testcontainers.internal:");
    }

    @Test
    @Order(2)
    void googleFlowsVerweigernUnbekannteKontenUndVerlangenTotp() {
        List<Map<String, Object>> unbekannt = schritte("google-nur-verknuepft");
        assertThat(unbekannt).hasSize(1);
        assertThat(unbekannt.getFirst().get("providerId")).isEqualTo("deny-access-authenticator");
        assertThat(unbekannt.getFirst().get("requirement")).isEqualTo("REQUIRED");

        assertThat(OtpPruefung.otpOhneBedingung(schritte("google-totp-pflicht"))).isTrue();
    }

    @Test
    @Order(3)
    void ohneTotpKeinTokenUeberIrgendeinenClient() {
        for (Map<String, Object> client : adminGet(REALM + "/clients").jsonPath().<Map<String, Object>>getList("$")) {
            String clientId = (String) client.get("clientId");
            Response antwort = Testanmeldung.anmelden(realmUrl(), clientId, "ohne-totp", PASSWORT, null);
            assertThat(antwort.statusCode()).as(clientId).isNotEqualTo(200);
            assertThat(antwort.body().asString()).as(clientId).doesNotContain("access_token");
        }
    }

    @Test
    @Order(3)
    void mitTotpAberOhneCodeKeinToken() {
        Response antwort = Testanmeldung.anmelden(realmUrl(), "probe-mit-audience", "mit-totp-1", PASSWORT, null);
        assertThat(antwort.statusCode()).isNotEqualTo(200);
        assertThat(antwort.body().asString()).doesNotContain("access_token");
    }

    @Test
    @Order(3)
    void apiNimmtNurTokensMitAudienceArchivApi() {
        String fremd = Testanmeldung.anmelden(realmUrl(), "probe-fremd", "mit-totp-1", PASSWORT,
                Testanmeldung.OTP_SECRET).path("access_token");
        String eigen = Testanmeldung.anmelden(realmUrl(), "probe-mit-audience", "mit-totp-2", PASSWORT,
                Testanmeldung.OTP_SECRET).path("access_token");
        assertThat(fremd).isNotNull();
        assertThat(eigen).isNotNull();
        given().auth().oauth2(fremd).get(PROBE_PFAD).then().statusCode(401);
        given().auth().oauth2(eigen).get(PROBE_PFAD).then().statusCode(404);
    }

    @Test
    @Order(4)
    void clientSecretRotationErlaubtUeberlappungUndWiderruf() {
        String vorherigeProfile = adminGet(REALM + "/client-policies/profiles").asString();
        String vorherigeRegeln = adminGet(REALM + "/client-policies/policies").asString();
        String clientName = "rotation-probe";
        String alterTestwert = UUID.randomUUID().toString();
        String client = null;
        try {
            adminPut("/client-policies/profiles", Map.of("profiles", List.of(Map.of(
                    "name", "rotation-probe",
                    "executors", List.of(Map.of("executor", "secret-rotation", "configuration", Map.of(
                            "expiration-period", 3600, "rotated-expiration-period", 600,
                            "remaining-rotation-period", 0)))))));
            adminPut("/client-policies/policies", Map.of("policies", List.of(Map.of(
                    "name", "rotation-probe", "enabled", true, "profiles", List.of("rotation-probe"),
                    "conditions", List.of(Map.of("condition", "client-access-type",
                            "configuration", Map.of("type", List.of("confidential"))))))));
            given().auth().oauth2(admin).contentType(ContentType.JSON).body(Map.of(
                    "clientId", clientName, "enabled", true, "protocol", "openid-connect",
                    "publicClient", false, "serviceAccountsEnabled", true, "standardFlowEnabled", false,
                    "directAccessGrantsEnabled", false, "secret", alterTestwert))
                    .post(ExportKeycloak.basisUrl + REALM + "/clients").then().statusCode(201);
            client = clientId(clientName);
            assertThat(clientTokenStatus(clientName, alterTestwert)).isEqualTo(200);
            Response rotation = given().auth().oauth2(admin)
                    .post(ExportKeycloak.basisUrl + REALM + "/clients/{id}/client-secret", client);
            assertThat(rotation.statusCode()).isEqualTo(200);
            String neuerTestwert = rotation.path("value");
            assertThat(neuerTestwert != null && !neuerTestwert.equals(alterTestwert)).isTrue();
            assertThat(clientTokenStatus(clientName, alterTestwert)).isEqualTo(200);
            assertThat(clientTokenStatus(clientName, neuerTestwert)).isEqualTo(200);
            given().auth().oauth2(admin)
                    .delete(ExportKeycloak.basisUrl + REALM + "/clients/{id}/client-secret/rotated", client)
                    .then().statusCode(204);
            assertThat(clientTokenStatus(clientName, alterTestwert)).isEqualTo(401);
            assertThat(clientTokenStatus(clientName, neuerTestwert)).isEqualTo(200);
        } finally {
            try {
                if (client != null) {
                    loeschen("/clients/" + client);
                }
            } finally {
                try {
                    adminPut("/client-policies/policies", vorherigeRegeln);
                } finally {
                    adminPut("/client-policies/profiles", vorherigeProfile);
                }
            }
        }
    }

    private void adminPut(String pfad, Object inhalt) {
        given().auth().oauth2(admin).contentType(ContentType.JSON).body(inhalt)
                .put(ExportKeycloak.basisUrl + REALM + pfad).then().statusCode(204);
    }

    private int clientTokenStatus(String client, String testwert) {
        return given().formParam("grant_type", "client_credentials").formParam("client_id", client)
                .formParam("client_secret", testwert)
                .post(realmUrl() + "/protocol/openid-connect/token").statusCode();
    }

    /** Clients, deren eigene Flow-Bindung keinen OTP-Schritt ohne Bedingung verlangt. */
    private List<String> ungeschuetzteClients() {
        List<String> ungeschuetzt = new ArrayList<>();
        for (Map<String, Object> client : adminGet(REALM + "/clients").jsonPath().<Map<String, Object>>getList("$")) {
            Map<String, String> bindungen = (Map<String, String>) client.getOrDefault("authenticationFlowBindingOverrides", Map.of());
            for (String flowId : bindungen.values()) {
                String alias = adminGet(REALM + "/authentication/flows/{id}", flowId).path("alias");
                if (!OtpPruefung.otpOhneBedingung(schritte(alias))) {
                    ungeschuetzt.add((String) client.get("clientId"));
                }
            }
        }
        return ungeschuetzt;
    }

    private List<Map<String, Object>> schritte(String alias) {
        return adminGet(REALM + "/authentication/flows/{alias}/executions", alias).jsonPath().getList("$");
    }

    private List<String> identityProvider() {
        return adminGet(REALM + "/identity-provider/instances").jsonPath().getList("alias");
    }

    private String clientId(String clientId) {
        return adminGet(REALM + "/clients?clientId={c}", clientId).path("[0].id");
    }

    private void loeschen(String pfad) {
        given().auth().oauth2(admin).delete(ExportKeycloak.basisUrl + REALM + pfad).then().statusCode(204);
    }

    private static Map<String, Object> benutzer(String name, boolean mitTotp) {
        List<Map<String, Object>> zugang = mitTotp
                ? List.of(passwort(), Map.of("type", "otp", "userLabel", "Probe",
                        "secretData", "{\"value\":\"" + Testanmeldung.OTP_SECRET + "\"}",
                        "credentialData", "{\"subType\":\"totp\",\"digits\":6,\"counter\":0,\"period\":30,\"algorithm\":\"HmacSHA1\"}"))
                : List.of(passwort());
        return Map.of("username", name, "enabled", true, "email", name + "@example.invalid", "emailVerified", true,
                "firstName", "Probe", "lastName", name, "attributes", Map.of("mandant", List.of("urinet")),
                "requiredActions", List.of(), "credentials", zugang);
    }

    private static Map<String, Object> passwort() {
        return Map.of("type", "password", "value", PASSWORT, "temporary", false);
    }

    /** Ein Client mit Direct Grant, wie ihn ein Administrator nachträglich anlegen könnte. */
    private static Map<String, Object> probeClient(String clientId, boolean mitAudience) {
        List<Map<String, Object>> mapper = mitAudience
                ? List.of(Map.of("name", "audience-archiv-api", "protocol", "openid-connect",
                    "protocolMapper", "oidc-audience-mapper",
                    "config", Map.of("included.client.audience", "archiv-api", "access.token.claim", "true")))
                : List.of();
        return Map.of("clientId", clientId, "enabled", true, "publicClient", true, "standardFlowEnabled", false,
                "directAccessGrantsEnabled", true, "protocolMappers", mapper);
    }

    private void importieren(Map<String, Object> inhalt) {
        given().auth().oauth2(admin).contentType(ContentType.JSON).body(inhalt)
                .post(ExportKeycloak.basisUrl + REALM + "/partialImport").then().statusCode(200);
    }

    private Response adminGet(String pfad, Object... parameter) {
        Response antwort = given().auth().oauth2(admin).get(ExportKeycloak.basisUrl + pfad, parameter);
        assertThat(antwort.statusCode()).as(pfad).isEqualTo(200);
        return antwort;
    }

    private static String realmUrl() {
        return ExportKeycloak.basisUrl + "/realms/urinet-archiv";
    }
}
