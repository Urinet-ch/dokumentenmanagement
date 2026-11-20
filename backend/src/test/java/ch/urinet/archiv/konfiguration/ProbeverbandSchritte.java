package ch.urinet.archiv.konfiguration;

import ch.urinet.archiv.ablage.TestAblage;
import ch.urinet.archiv.dokument.Testdokumente;
import ch.urinet.archiv.gemeinsam.Testanmeldung;
import ch.urinet.archiv.mandant.application.MandantBucket;
import ch.urinet.archiv.mandant.application.MandantProvisionierung;
import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.verarbeitung.AuftragTestHilfe;
import ch.urinet.archiv.verarbeitung.VerarbeitungTestHilfe;
import ch.urinet.archiv.verarbeitung.adapter.out.ocr.OcrTestdateien;
import ch.urinet.archiv.verarbeitung.adapter.out.ocr.WerkzeugImage;
import io.agroal.api.AgroalDataSource;
import io.cucumber.java.After;
import io.cucumber.java.de.Angenommen;
import io.cucumber.java.de.Dann;
import io.cucumber.java.de.Wenn;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.ConfigProvider;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.net.URI;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

public class ProbeverbandSchritte {

    private static final String MANDANT = "probe-verband";
    private static final MandantCode CODE = new MandantCode(MANDANT);

    @Inject
    MandantProvisionierung provisionierung;
    @Inject
    MandantBucket buckets;
    @Inject
    AuftragTestHilfe auftraege;
    @Inject
    VerarbeitungTestHilfe verarbeitung;
    @Inject
    Instance<AgroalDataSource> datenbank;

    @ConfigProperty(name = "archiv.ablage.bucket-praefix")
    String bucketPraefix;

    private String admin;
    private String adminPfad;
    private String benutzerId;
    private String benutzer;
    private UUID dokument;
    private UUID duplikat;

    @Angenommen("der synthetische Probeverband ist für die Abnahme bereit")
    public void bereit() throws Exception {
        String realmUrl = ConfigProvider.getConfig().getValue("quarkus.oidc.auth-server-url", String.class);
        URI realm = URI.create(realmUrl);
        if (!"http".equals(realm.getScheme())
                || !List.of("localhost", "127.0.0.1").contains(realm.getHost())) {
            throw new IllegalStateException("Der Probeverband benötigt einen lokalen Keycloak-Dev-Service");
        }
        for (String code : List.of("urinet", MANDANT)) {
            try (Connection verbindung = datenbank.get().getConnection();
                 PreparedStatement abfrage = verbindung.prepareStatement(
                         "select 1 from public.mandant where code = ? and aktiv")) {
                abfrage.setString(1, code);
                try (ResultSet ergebnis = abfrage.executeQuery()) {
                    if (!ergebnis.next()) {
                        provisionierung.anlegen(code, null, null);
                    }
                }
            }
        }
        buckets.einrichten(CODE.bucket(bucketPraefix));
        WerkzeugImage.sicherstellen();
        String basis = realmUrl.substring(0, realmUrl.indexOf("/realms/"));
        adminPfad = basis + "/admin/realms/urinet-archiv";
        admin = given().formParam("grant_type", "password").formParam("client_id", "admin-cli")
                .formParam("username", "admin").formParam("password", "admin")
                .when().post(basis + "/realms/master/protocol/openid-connect/token")
                .then().statusCode(200).extract().path("access_token");
        benutzer = "probe-18-5-" + UUID.randomUUID();
        Map<String, Object> passwort = Map.of("type", "password", "value", Testanmeldung.PASSWORT, "temporary", false);
        Map<String, Object> otp = Map.of("type", "otp", "userLabel", "Probe",
                "secretData", "{\"value\":\"" + Testanmeldung.OTP_SECRET + "\"}",
                "credentialData", "{\"subType\":\"totp\",\"digits\":6,\"counter\":0,\"period\":30,"
                        + "\"algorithm\":\"HmacSHA1\"}");
        Map<String, Object> nutzer = Map.of("username", benutzer, "enabled", true,
                "email", benutzer + "@example.invalid", "emailVerified", true, "firstName", "Probe", "lastName",
                "Verband", "attributes", Map.of("mandant", List.of(MANDANT)),
                "requiredActions", List.of(), "credentials", List.of(passwort, otp));
        given().auth().oauth2(admin).contentType(ContentType.JSON)
                .body(Map.of("ifResourceExists", "FAIL", "users", List.of(nutzer)))
                .when().post(adminPfad + "/partialImport").then().statusCode(200);
        benutzerId = given().auth().oauth2(admin).queryParam("username", benutzer).queryParam("exact", true)
                .when().get(adminPfad + "/users").then().statusCode(200).extract().path("[0].id");
        Map<String, Object> rolle = given().auth().oauth2(admin).when().get(adminPfad + "/roles/inhaber")
                .then().statusCode(200).extract().jsonPath().getMap("$");
        given().auth().oauth2(admin).contentType(ContentType.JSON).body(List.of(rolle))
                .when().post(adminPfad + "/users/" + benutzerId + "/role-mappings/realm")
                .then().statusCode(204);
    }

    @Wenn("der Probeverband eine synthetische Sitzungsunterlage erfasst und bestätigt")
    public void erfassenUndBestaetigen() throws Exception {
        byte[] pdf = OcrTestdateien.textPdf("Sitzungsunterlage Probe", UUID.randomUUID().toString());
        Response upload = given().auth().oauth2(Testanmeldung.token(benutzer))
                .multiPart("dateien", "sitzung.pdf", pdf, "application/pdf")
                .when().post("/api/v1/dokumente");
        assertThat(upload.statusCode()).as(upload.asString()).isEqualTo(202);
        dokument = UUID.fromString(upload.jsonPath().getString("[0].id"));
        verarbeitung.abarbeiten();
        assertThat(verarbeitung.dokument(MANDANT, dokument)).containsEntry("status", "EINGANGSKORB");

        Response ohneKlassifikation = given().auth().oauth2(Testanmeldung.token(benutzer)).header("If-Match", etag())
                .contentType(ContentType.JSON)
                .body(Map.of("art", "VERBANDSSITZUNG", "zusatz", Map.of("sitzung", "2028-02-29", "nummer", 1)))
                .when().patch("/api/v1/dokumente/" + dokument);
        assertThat(ohneKlassifikation.statusCode()).as(ohneKlassifikation.asString()).isEqualTo(422);
        assertThat(ohneKlassifikation.jsonPath().getString("fehlercode")).isEqualTo("KLASSIFIKATION_UNGUELTIG");

        Response patch = given().auth().oauth2(Testanmeldung.token(benutzer)).header("If-Match", etag())
                .contentType(ContentType.JSON)
                .body(Map.of("art", "VERBANDSSITZUNG", "zusatz", Map.of("sitzung", "2028-02-29", "nummer", 1),
                        "klassifikationen", Map.of("sitzungsthema", List.of("projekte"))))
                .when().patch("/api/v1/dokumente/" + dokument);
        assertThat(patch.statusCode()).as(patch.asString()).isEqualTo(200);
        Response bestaetigung = given().auth().oauth2(Testanmeldung.token(benutzer)).header("If-Match", etag())
                .contentType(ContentType.JSON)
                .body(Map.of("art", "VERBANDSSITZUNG", "titel", "Sitzung Probe",
                        "klassifikationen", Map.of("sitzungsthema", List.of("projekte"))))
                .when().post("/api/v1/dokumente/" + dokument + "/bestaetigen");
        assertThat(bestaetigung.statusCode()).as(bestaetigung.asString()).isEqualTo(202);
        auftraege.abarbeiten();
    }

    @Dann("ist die Sitzungsunterlage mit Feldfrist archiviert, in Suche und Lückenbericht enthalten und ihre Nummer eindeutig")
    public void pruefen() throws Exception {
        assertThat(verarbeitung.dokument(MANDANT, dokument)).containsEntry("status", "ARCHIVIERT");
        try (Connection verbindung = datenbank.get().getConnection(); PreparedStatement abfrage = verbindung.prepareStatement(
                "select aufbewahren_bis from " + CODE.schema() + ".dokument where id = ?")) {
            abfrage.setObject(1, dokument);
            try (ResultSet zeile = abfrage.executeQuery()) {
                assertThat(zeile.next()).isTrue();
                assertThat(zeile.getDate(1).toLocalDate()).hasToString("2038-02-28");
            }
        }
        byte[] pdf = OcrTestdateien.textPdf("Zweite Sitzungsunterlage Probe", UUID.randomUUID().toString());
        Response upload = given().auth().oauth2(Testanmeldung.token(benutzer))
                .multiPart("dateien", "sitzung-zwei.pdf", pdf, "application/pdf")
                .when().post("/api/v1/dokumente");
        assertThat(upload.statusCode()).as(upload.asString()).isEqualTo(202);
        duplikat = UUID.fromString(upload.jsonPath().getString("[0].id"));
        verarbeitung.abarbeiten();
        assertThat(verarbeitung.dokument(MANDANT, duplikat)).containsEntry("status", "EINGANGSKORB");
        Response doppelteNummer = given().auth().oauth2(Testanmeldung.token(benutzer)).header("If-Match", etag(duplikat))
                .contentType(ContentType.JSON)
                .body(Map.of("art", "VERBANDSSITZUNG", "zusatz", Map.of("sitzung", "2028-03-01", "nummer", 1),
                        "klassifikationen", Map.of("sitzungsthema", List.of("betrieb"))))
                .when().patch("/api/v1/dokumente/" + duplikat);
        assertThat(doppelteNummer.statusCode()).as(doppelteNummer.asString()).isEqualTo(422);
        assertThat(doppelteNummer.jsonPath().getString("fehlercode")).isEqualTo("REGEL_VERLETZT");

        Response suche = given().auth().oauth2(Testanmeldung.token(benutzer))
                .queryParam("klassifikation.sitzungsthema", "projekte")
                .when().get("/api/v1/dokumente");
        assertThat(suche.statusCode()).as(suche.asString()).isEqualTo(200);
        assertThat(suche.jsonPath().<String>getList("treffer.id")).contains(dokument.toString());
        given().auth().oauth2(Testanmeldung.token("inhaber-urinet")).when()
                .get("/api/v1/dokumente/" + dokument).then().statusCode(404);
        Response fremdeSuche = given().auth().oauth2(Testanmeldung.token("inhaber-urinet"))
                .queryParam("q", "Sitzung Probe").when().get("/api/v1/dokumente");
        assertThat(fremdeSuche.statusCode()).as(fremdeSuche.asString()).isEqualTo(200);
        assertThat(fremdeSuche.jsonPath().<String>getList("treffer.id")).doesNotContain(dokument.toString());
        Response bericht = given().auth().oauth2(Testanmeldung.token(benutzer)).queryParam("geschaeftsjahr", 2026)
                .when().get("/api/v1/berichte/luecken");
        assertThat(bericht.statusCode()).as(bericht.asString()).isEqualTo(200);
        assertThat(bericht.jsonPath().<Map<String, Object>>getList("kategorien")).anySatisfy(kategorie -> {
            assertThat(kategorie).containsEntry("art", "VERBANDSSITZUNG").containsEntry("regel", "FOLGE")
                    .containsEntry("belege", 1);
        });
    }

    @Dann("bleiben Konfiguration, KI-Texte und Versionsabbilder des Probeverbands in seiner API")
    public void apiIsolation() {
        int versionVerband = given().auth().oauth2(Testanmeldung.token(benutzer)).when()
                .get("/api/v1/konfiguration/versionen").then().statusCode(200).extract().jsonPath()
                .getList("version", Integer.class).stream().max(Integer::compareTo).orElseThrow();
        int versionUrinet = given().auth().oauth2(Testanmeldung.token("inhaber-urinet")).when()
                .get("/api/v1/konfiguration/versionen").then().statusCode(200).extract().jsonPath()
                .getList("version", Integer.class).stream().max(Integer::compareTo).orElseThrow();
        for (String pfad : List.of("/api/v1/konfiguration", "/api/v1/konfiguration/versionen/{version}",
                "/api/v1/konfiguration/ki-texte")) {
            String verband = given().auth().oauth2(Testanmeldung.token(benutzer)).when()
                    .get(pfad.replace("{version}", Integer.toString(versionVerband)))
                    .then().statusCode(200).extract().asString();
            String urinet = given().auth().oauth2(Testanmeldung.token("inhaber-urinet")).when()
                    .get(pfad.replace("{version}", Integer.toString(versionUrinet)))
                    .then().statusCode(200).extract().asString();
            assertThat(verband).as(pfad).contains("Probeverband").doesNotContain("AUSGANGSRECHNUNG");
            assertThat(urinet).as(pfad)
                    .doesNotContain("Probeverband", "VERBANDSSITZUNG", "sitzungsthema", "sitzung_nummer_folge");
            if (!pfad.endsWith("/ki-texte")) {
                assertThat(verband).as(pfad).contains("VERBANDSSITZUNG", "sitzungsthema", "sitzung_nummer_folge");
                assertThat(urinet).as(pfad).contains("AUSGANGSRECHNUNG");
            }
        }
    }

    private String etag() {
        return etag(dokument);
    }

    private String etag(UUID id) {
        Response antwort = given().auth().oauth2(Testanmeldung.token(benutzer)).when()
                .get("/api/v1/dokumente/" + id);
        assertThat(antwort.statusCode()).as(antwort.asString()).isEqualTo(200);
        return antwort.header("ETag");
    }

    @After
    public void aufraeumen() throws SQLException {
        for (UUID id : java.util.stream.Stream.of(dokument, duplikat).filter(java.util.Objects::nonNull).toList()) {
            auftraege.ausfuehren("delete from public.auftrag where nutzlast ->> 'dokumentId' = '" + id + "'");
            Testdokumente.loeschen(datenbank.get(), MANDANT, List.of(id));
            for (String praefix : List.of("eingang/", "archiv/", "vorschau/")) {
                TestAblage.loeschen(MANDANT, praefix, schluessel -> schluessel.contains("/" + id + "/"));
            }
        }
        if (benutzerId != null) {
            given().auth().oauth2(admin).when().delete(adminPfad + "/users/" + benutzerId)
                    .then().statusCode(204);
        }
    }
}
