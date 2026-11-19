package ch.urinet.archiv.sicherheit;

import ch.urinet.archiv.gemeinsam.Testanmeldung;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.cucumber.java.de.Angenommen;
import io.cucumber.java.de.Dann;
import io.cucumber.java.de.Wenn;
import io.restassured.response.Response;

import java.io.IOException;
import java.net.HttpCookie;
import java.net.URI;
import java.net.URLEncoder;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
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

/** Ausführbare Google-Broker-Szenarien gegen den lokalen OIDC-Test-IdP. */
public class GoogleAnmeldungSchritte {

    private static final String REALM = "urinet-archiv";
    private static final String TEST_CLIENT = "archiv-test-google";
    private static final String PROVIDER = "archiv-test-oidc";
    private static final String REDIRECT_URI = "http://localhost/callback";
    private static final Pattern FORM = Pattern.compile("(?is)<form\\b([^>]*)>(.*?)</form>");
    private static final Pattern IDP_LINK = Pattern.compile(
            "(?is)href=\"([^\"]*/broker/" + PROVIDER + "/login[^\"]*)\"");
    private static final Pattern ATTRIBUT = Pattern.compile("(?i)\\b%s\\s*=\\s*['\"]([^'\"]*)['\"]");
    private static final Pattern INPUT = Pattern.compile("(?is)<input\\b[^>]*>");
    private static final ObjectMapper JSON = new ObjectMapper();

    private String adminToken;
    private String benutzerId;
    private String email;
    private String sub;
    private Browser browser;
    private URI rueckruf;
    private String seite;
    private String sitzungsToken;
    private String sitzungsTokenPayload;

    @Angenommen("ein unbekanntes Google-Testkonto wird angeboten")
    public void unbekanntesKonto() {
        identitaetVorbereiten("unbekannt");
    }

    @Angenommen("ein bestehender Archivbenutzer hat nur dieselbe E-Mail-Adresse wie das Google-Testkonto")
    public void nurEEmailPasst() {
        identitaetVorbereiten("email");
        benutzerAnlegen(false);
    }

    @Angenommen("ein Archivbenutzer ist mit der Google-Identität verknüpft")
    public void kontoIstVerknuepft() {
        identitaetVorbereiten("verknuepft");
        benutzerAnlegen(true);
        federierteIdentitaetAnlegen();
    }

    @Wenn("das Konto sich über den OIDC-Test-IdP anmeldet")
    public void ueberTestIdpAnmelden() throws IOException, InterruptedException {
        browser = new Browser();
        Seite ergebnis = browser.anmelden();
        rueckruf = ergebnis.rueckruf();
        seite = ergebnis.html();
    }

    @Wenn("der verknüpfte Benutzer die Anmeldung mit TOTP abschliesst")
    public void mitTotpAbschliessen() throws IOException, InterruptedException {
        assertThat(seite).as("Test-IdP: %s", ExportKeycloak.oidcTestIdp.diagnose()).contains("name=\"otp\"");
        Seite ergebnis = browser.totpAbschliessen(seite);
        rueckruf = ergebnis.rueckruf();
        seite = ergebnis.html();
        assertThat(rueckruf).as("Keycloak nach TOTP: %s", diagnoseSeite(seite)).isNotNull();
        sitzungsToken = browser.tokenTauschen(rueckruf);
        sitzungsTokenPayload = new String(Base64.getUrlDecoder().decode(sitzungsToken.split("\\.")[1]),
                StandardCharsets.UTF_8);
    }

    @Dann("weist Keycloak das unbekannte Konto ab")
    public void unbekanntAbgewiesen() {
        assertThat(rueckruf).isNull();
        assertThat(benutzerNachEmail(email)).isEmpty();
    }

    @Dann("wird das Konto trotz gleicher E-Mail nicht automatisch verknüpft")
    public void eEmailVerknuepftNicht() {
        assertThat(rueckruf).isNull();
        assertThat(federierteIdentitaeten()).isEmpty();
    }

    @Dann("der verknüpfte Benutzer erhält erst nach erfolgreichem TOTP einen Keycloak-Token")
    public void tokenErstNachTotp() throws IOException {
        assertThat(sitzungsToken).isNotBlank();
        JsonNode payload = JSON.readTree(sitzungsTokenPayload);
        List<String> rollen = new ArrayList<>();
        payload.path("realm_access").path("roles").forEach(rolle -> rollen.add(rolle.asText()));
        assertThat(rollen).contains("inhaber");
        assertThat(payload.path("mandant").asText()).isEqualTo("urinet");
    }

    @Wenn("der verknüpfte Benutzer sich anmeldet, entkoppelt und abgemeldet wird")
    public void anmeldenEntkoppelnUndAbmelden() throws IOException, InterruptedException {
        ueberTestIdpAnmelden();
        mitTotpAbschliessen();
        assertThat(sitzungen()).isNotEmpty();
        given().auth().oauth2(adminToken()).delete(adminPfad("/users/{id}/federated-identity/{provider}"),
                benutzerId, PROVIDER).then().statusCode(204);
        given().auth().oauth2(adminToken()).post(adminPfad("/users/{id}/logout"), benutzerId).then().statusCode(204);
    }

    @Dann("stehen Verknüpfen und Lösen im Admin-Ereignisprotokoll und die Sitzung ist beendet")
    public void adminEreignisseUndSitzung() {
        List<Map<String, Object>> ereignisse = given().auth().oauth2(adminToken())
                .get(adminPfad("/admin-events?max=100")).then().statusCode(200).extract().jsonPath().getList("$");
        assertThat(ereignisse).anySatisfy(ereignis -> {
            assertThat(ereignis.get("operationType")).isEqualTo("CREATE");
            assertThat(ereignis.get("resourcePath").toString()).contains("federated-identity/" + PROVIDER);
        });
        assertThat(ereignisse).anySatisfy(ereignis -> {
            assertThat(ereignis.get("operationType")).isEqualTo("DELETE");
            assertThat(ereignis.get("resourcePath").toString()).contains("federated-identity/" + PROVIDER);
        });
        assertThat(sitzungen()).isEmpty();
    }

    private void identitaetVorbereiten(String kennung) {
        email = kennung + "-" + UUID.randomUUID() + "@example.invalid";
        sub = "oidc-" + kennung + "-" + UUID.randomUUID();
        ExportKeycloak.oidcTestIdp.identitaetSetzen(new OidcTestIdp.Identitaet(sub, email));
    }

    private void benutzerAnlegen(boolean mitTotp) {
        String benutzername = "google-test-" + UUID.randomUUID();
        List<Map<String, Object>> credentials = mitTotp
                ? List.of(Map.of("type", "password", "value", Testanmeldung.PASSWORT, "temporary", false),
                        Map.of("type", "otp", "userLabel", "Synthetischer Test",
                        "secretData", "{\"value\":\"" + Testanmeldung.OTP_SECRET + "\"}",
                        "credentialData", "{\"subType\":\"totp\",\"digits\":6,\"counter\":0,\"period\":30,\"algorithm\":\"HmacSHA1\"}"))
                : List.of();
        Map<String, Object> benutzer = Map.of(
                "username", benutzername,
                "email", email,
                "emailVerified", true,
                "enabled", true,
                "firstName", "Google-Test",
                "lastName", "Synthetisch",
                "attributes", Map.of("mandant", List.of("urinet")),
                "realmRoles", List.of("inhaber"),
                "requiredActions", List.of(),
                "credentials", credentials);
        given().auth().oauth2(adminToken()).contentType("application/json")
                .body(Map.of("ifResourceExists", "SKIP", "users", List.of(benutzer)))
                .post(adminPfad("/partialImport")).then().statusCode(200);
        benutzerId = given().auth().oauth2(adminToken())
                .get(adminPfad("/users?exact=true&username={username}"), benutzername)
                .then().statusCode(200).extract().path("[0].id");
    }

    private void federierteIdentitaetAnlegen() {
        Map<String, Object> identitaet = Map.of(
                "identityProvider", PROVIDER,
                "userId", sub,
                "userName", email);
        given().auth().oauth2(adminToken()).contentType("application/json").body(identitaet)
                .post(adminPfad("/users/{id}/federated-identity/{provider}"), benutzerId, PROVIDER)
                .then().statusCode(204);
    }

    private List<Map<String, Object>> federierteIdentitaeten() {
        return given().auth().oauth2(adminToken())
                .get(adminPfad("/users/{id}/federated-identity"), benutzerId)
                .then().statusCode(200).extract().jsonPath().getList("$");
    }

    private List<Map<String, Object>> sitzungen() {
        return given().auth().oauth2(adminToken())
                .get(adminPfad("/users/{id}/sessions"), benutzerId)
                .then().statusCode(200).extract().jsonPath().getList("$");
    }

    private List<Map<String, Object>> benutzerNachEmail(String wert) {
        return given().auth().oauth2(adminToken())
                .get(adminPfad("/users?email={email}&exact=true"), wert)
                .then().statusCode(200).extract().jsonPath().getList("$");
    }

    private String adminToken() {
        if (adminToken == null) {
            adminToken = given().formParam("grant_type", "password").formParam("client_id", "admin-cli")
                    .formParam("username", ExportKeycloak.ADMIN).formParam("password", ExportKeycloak.ADMIN_PASSWORT)
                    .post(ExportKeycloak.basisUrl + "/realms/master/protocol/openid-connect/token")
                    .then().statusCode(200).extract().path("access_token");
        }
        return adminToken;
    }

    private static String adminPfad(String pfad) {
        return ExportKeycloak.basisUrl + "/admin/realms/" + REALM + pfad;
    }

    private record Seite(URI rueckruf, String html, URI uri) {
    }

    private final class Browser {
        private final HttpClient client;
        private final Map<String, String> cookies = new HashMap<>();
        private final URI keycloak = URI.create(ExportKeycloak.basisUrl);

        private Browser() {
            client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
        }

        private Seite anmelden() throws IOException, InterruptedException {
            String url = ExportKeycloak.basisUrl + "/realms/" + REALM + "/protocol/openid-connect/auth"
                    + "?client_id=" + enc(TEST_CLIENT)
                    + "&redirect_uri=" + enc(REDIRECT_URI)
                    + "&response_type=code&scope=openid&state=" + enc(UUID.randomUUID().toString())
                    + "&nonce=" + enc(UUID.randomUUID().toString())
                    + "&kc_idp_hint=" + enc(PROVIDER);
            Seite seite = weiter(URI.create(url), null);
            if (seite.rueckruf() != null) {
                return seite;
            }
            Matcher idpLink = IDP_LINK.matcher(seite.html());
            if (!idpLink.find()) {
                return seite;
            }
            return weiter(seite.uri().resolve(idpLink.group(1).replace("&amp;", "&")), null);
        }

        private Seite totpAbschliessen(String html) throws IOException, InterruptedException {
            Form formular = formular(html);
            Map<String, String> felder = new HashMap<>(formular.felder());
            felder.put("otp", Testanmeldung.totp(Testanmeldung.OTP_SECRET, System.currentTimeMillis() / 1000));
            String body = felder.entrySet().stream()
                    .map(e -> enc(e.getKey()) + "=" + enc(e.getValue()))
                    .collect(java.util.stream.Collectors.joining("&"));
            HttpRequest.Builder anfrage = HttpRequest.newBuilder(formular.action())
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(body));
            HttpResponse<String> antwort = senden(formular.action(), anfrage);
            return weiter(formular.action(), antwort);
        }

        private String tokenTauschen(URI callback) {
            Map<String, String> parameter = query(callback.getRawQuery());
            Response antwort = given()
                    .formParam("grant_type", "authorization_code")
                    .formParam("client_id", TEST_CLIENT)
                    .formParam("code", parameter.get("code"))
                    .formParam("redirect_uri", REDIRECT_URI)
                    .post(ExportKeycloak.basisUrl + "/realms/" + REALM + "/protocol/openid-connect/token");
            assertThat(antwort.statusCode()).isEqualTo(200);
            return antwort.path("access_token");
        }

        private Seite weiter(URI start, HttpResponse<String> erste) throws IOException, InterruptedException {
            URI uri = start;
            HttpResponse<String> antwort = erste;
            for (int versuch = 0; versuch < 12; versuch++) {
                if (antwort == null) {
                    antwort = senden(uri, HttpRequest.newBuilder(uri).GET());
                }
                if (antwort.statusCode() >= 300 && antwort.statusCode() < 400) {
                    String location = antwort.headers().firstValue("location").orElseThrow();
                    URI ziel = uri.resolve(location);
                    if (REDIRECT_URI.equals(ziel.getScheme() + "://" + ziel.getAuthority() + ziel.getPath())) {
                        return new Seite(ziel, antwort.body(), uri);
                    }
                    uri = browserUri(ziel);
                    antwort = null;
                    continue;
                }
                return new Seite(null, antwort.body(), uri);
            }
            throw new IllegalStateException("OIDC-Testanmeldung hat zu viele Weiterleitungen");
        }

        private URI browserUri(URI ziel) {
            if ("host.testcontainers.internal".equals(ziel.getHost())) {
                return URI.create("http://localhost:" + ExportKeycloak.oidcTestIdp.port()
                        + ziel.getRawPath() + (ziel.getRawQuery() == null ? "" : "?" + ziel.getRawQuery()));
            }
            return ziel;
        }

        private HttpResponse<String> senden(URI uri, HttpRequest.Builder anfrage)
                throws IOException, InterruptedException {
            if (keycloak.getHost().equals(uri.getHost()) && keycloak.getPort() == uri.getPort() && !cookies.isEmpty()) {
                anfrage.header("Cookie", cookies.entrySet().stream()
                        .map(cookie -> cookie.getKey() + "=" + cookie.getValue())
                        .collect(java.util.stream.Collectors.joining("; ")));
            }
            HttpResponse<String> antwort = client.send(anfrage.build(), HttpResponse.BodyHandlers.ofString());
            if (keycloak.getHost().equals(uri.getHost()) && keycloak.getPort() == uri.getPort()) {
                antwort.headers().allValues("set-cookie").stream()
                        .flatMap(setCookie -> HttpCookie.parse(setCookie).stream())
                        .forEach(cookie -> {
                            if (cookie.hasExpired()) {
                                cookies.remove(cookie.getName());
                            } else {
                                cookies.put(cookie.getName(), cookie.getValue());
                            }
                        });
            }
            return antwort;
        }

        private Form formular(String html) {
            Matcher forms = FORM.matcher(html);
            while (forms.find()) {
                String action = attribut(forms.group(1), "action");
                String markup = forms.group(2);
                if (markup.contains("name=\"otp\"") || markup.contains("name='otp'")) {
                    Map<String, String> felder = new HashMap<>();
                    Matcher inputs = INPUT.matcher(markup);
                    while (inputs.find()) {
                        String name = attribut(inputs.group(), "name");
                        if (name != null) {
                            String value = attribut(inputs.group(), "value");
                            felder.put(name, value == null ? "" : value);
                        }
                    }
                    return new Form(URI.create(action.replace("&amp;", "&")), felder);
                }
            }
            throw new AssertionError("TOTP-Formular fehlt in der Keycloak-Antwort");
        }

        private String attribut(String markup, String name) {
            Matcher matcher = Pattern.compile(String.format(ATTRIBUT.pattern(), name)).matcher(markup);
            return matcher.find() ? matcher.group(1) : null;
        }

        private String enc(String wert) {
            return URLEncoder.encode(wert, StandardCharsets.UTF_8);
        }
    }

    private record Form(URI action, Map<String, String> felder) {
    }

    private static Map<String, String> query(String kodiert) {
        Map<String, String> parameter = new HashMap<>();
        if (kodiert != null) {
            for (String paar : kodiert.split("&")) {
                String[] teile = paar.split("=", 2);
                parameter.put(URLDecoder.decode(teile[0], StandardCharsets.UTF_8),
                        teile.length > 1 ? URLDecoder.decode(teile[1], StandardCharsets.UTF_8) : "");
            }
        }
        return parameter;
    }

    private static String enc(String wert) {
        return URLEncoder.encode(wert, StandardCharsets.UTF_8);
    }

    private static String diagnoseSeite(String html) {
        Matcher meldung = Pattern.compile("(?is)<p class=\"instruction\">(.*?)</p>").matcher(html);
        String text = meldung.find() ? meldung.group(1).replaceAll("<[^>]+>", "").replace("&quot;", "\"")
                : "keine angezeigte Fehlermeldung";
        Matcher seitenId = Pattern.compile("data-page-id=\"([^\"]+)\"").matcher(html);
        Matcher titel = Pattern.compile("(?is)<title>(.*?)</title>").matcher(html);
        return "page=" + (seitenId.find() ? seitenId.group(1) : "unbekannt")
                + ", otp=" + html.contains("name=\"otp\"")
                + ", title=" + (titel.find() ? titel.group(1).trim() : "unbekannt")
                + ", text=" + text;
    }
}
