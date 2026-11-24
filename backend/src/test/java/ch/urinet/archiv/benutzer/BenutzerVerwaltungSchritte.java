package ch.urinet.archiv.benutzer;

import ch.urinet.archiv.benutzer.adapter.out.stepup.StepUpMitAuthTime;
import ch.urinet.archiv.benutzer.adapter.out.keycloak.KeycloakAdminAdapter;
import ch.urinet.archiv.benutzer.adapter.out.stub.IdentitaetsDienstStub;
import ch.urinet.archiv.benutzer.adapter.out.mail.MailBenutzerMitteilung;
import ch.urinet.archiv.benutzer.application.IdentitaetsDienst;
import ch.urinet.archiv.benutzer.application.BenutzerMitteilung;
import ch.urinet.archiv.benutzer.application.StepUp;
import ch.urinet.archiv.dokument.Testdokumente;
import ch.urinet.archiv.gemeinsam.LogMitschnitt;
import ch.urinet.archiv.gemeinsam.Testanmeldung;
import ch.urinet.archiv.sicherheit.ProblemDetails;
import ch.urinet.archiv.gemeinsam.domain.FachFehler;
import ch.urinet.archiv.gemeinsam.domain.Fehlercode;
import ch.urinet.archiv.gemeinsam.domain.StepUpNoetig;
import ch.urinet.archiv.konfiguration.application.KonfigurationsdateiCodec;
import ch.urinet.archiv.konfiguration.domain.Konfiguration;
import ch.urinet.archiv.mandant.adapter.out.seed.KlassenpfadKonfigurationSeed;
import ch.urinet.archiv.mandant.application.KonfigurationSeed;
import ch.urinet.archiv.mandant.application.MandantProvisionierung;
import ch.urinet.archiv.mandant.application.MandantAbbau;
import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.sicherheit.application.ApiTokenVerwaltung;
import ch.urinet.archiv.sicherheit.application.TokenWiderruf;
import ch.urinet.archiv.sicherheit.application.WebseitenSchluesselVerwaltung;
import ch.urinet.archiv.sicherheit.domain.Aktion;
import ch.urinet.archiv.sicherheit.domain.ApiToken;
import ch.urinet.archiv.sicherheit.domain.Rechtematrix;
import ch.urinet.archiv.sicherheit.domain.Rolle;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agroal.api.AgroalDataSource;
import io.cucumber.java.After;
import io.cucumber.java.Before;
import io.cucumber.java.de.Angenommen;
import io.cucumber.java.de.Dann;
import io.cucumber.java.de.Wenn;
import io.quarkus.arc.ClientProxy;
import io.quarkus.test.junit.QuarkusMock;
import io.restassured.response.Response;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.ConfigProvider;

import java.io.IOException;
import java.net.URI;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class BenutzerVerwaltungSchritte {
    private static final String PFAD = "/api/v1/benutzer";
    private static final String MANDANT = "b19-" + UUID.randomUUID().toString().substring(0, 8);
    private static final String SCHEMA = MANDANT.replace('-', '_');

    @Inject Instance<AgroalDataSource> datenbank;
    @Inject MandantProvisionierung provisionierung;
    @Inject MandantAbbau abbau;
    @Inject KonfigurationSeed seed;
    @Inject KonfigurationsdateiCodec codec;
    @Inject IdentitaetsDienst identitaet;
    @Inject BenutzerMitteilung mitteilung;
    @Inject StepUp stepUp;
    @Inject ApiTokenVerwaltung apiTokens;
    @Inject WebseitenSchluesselVerwaltung webseitenSchluessel;
    @Inject TokenWiderruf tokenWiderruf;

    private final List<Map.Entry<String, String>> benutzer = new ArrayList<>();
    private final List<Response> antworten = new ArrayList<>();
    private final List<ApiToken> verwaltungsSchluessel = new ArrayList<>();
    private StepUp urspruenglichesStepUp;
    private IdentitaetsDienst urspruenglicheIdentitaet;
    private BenutzerMitteilung urspruenglicheMitteilung;
    private final List<String> emailMitteilungen = new ArrayList<>();
    private IdentitaetsDienstStub fehlerdienst;
    private boolean googleVerknuepft;
    private boolean googleSitzungenBeendet;
    private LogMitschnitt log;
    private String name;
    private String spaetererBestandsname;
    private String eigeneId;
    private String fremdeId;
    private String admin;
    private String adminPfad;
    private String akteurId;
    private String akteurName;
    private String zweiterAkteurId;
    private String zweiterAkteurName;
    private boolean loeschungMitKeycloak;
    private CountDownLatch loeschungBegonnen;
    private CountDownLatch loeschungFortsetzen;
    private UUID loeschDokument;
    private String altesToken;
    private String erneuerungsToken;
    private final Map<String, Rolle> gleichzeitigeRollen = new ConcurrentHashMap<>();

    @Before("@benutzer-verwaltung")
    public void mandantenVorbereiten() throws SQLException {
        for (String code : List.of(MANDANT, "vhf-gsk")) {
            try (Connection verbindung = datenbank.get().getConnection();
                 PreparedStatement abfrage = verbindung.prepareStatement(
                         "select 1 from public.mandant where code = ? and aktiv")) {
                abfrage.setString(1, code);
                try (ResultSet zeile = abfrage.executeQuery()) {
                    if (!zeile.next()) {
                        if (code.equals(MANDANT)) {
                            KonfigurationSeed original = ClientProxy.unwrap(seed);
                            QuarkusMock.installMockForType(new KlassenpfadKonfigurationSeed(codec) {
                                @Override
                                public Konfiguration lesen(MandantCode ignoriert) {
                                    return original.lesen(new MandantCode("urinet"));
                                }
                            }, KonfigurationSeed.class);
                            try {
                                provisionierung.anlegen(code, null, null);
                            } finally {
                                QuarkusMock.installMockForType(original, KonfigurationSeed.class);
                            }
                        } else {
                            provisionierung.anlegen(code, null, null);
                        }
                    }
                }
            }
        }
        name = "probe." + UUID.randomUUID().toString().substring(0, 8);
        String realmUrl = ConfigProvider.getConfig().getValue("quarkus.oidc.auth-server-url", String.class);
        URI realm = URI.create(realmUrl);
        if (!"http".equals(realm.getScheme()) || !List.of("localhost", "127.0.0.1").contains(realm.getHost())) {
            throw new IllegalStateException("Benutzerszenarien benötigen einen lokalen Keycloak-Dev-Service");
        }
        String basis = realmUrl.substring(0, realmUrl.indexOf("/realms/"));
        adminPfad = basis + "/admin/realms/urinet-archiv";
        admin = given().formParam("grant_type", "password").formParam("client_id", "admin-cli")
                .formParam("username", "admin").formParam("password", "admin")
                .post(basis + "/realms/master/protocol/openid-connect/token")
                .then().statusCode(200).extract().path("access_token");
        akteurName = "probe-19-7-" + UUID.randomUUID();
        akteurId = akteurAnlegen(MANDANT, akteurName);
    }

    private String akteurAnlegen(String mandant, String benutzername) {
        return keycloakBenutzerAnlegen(mandant, benutzername, "inhaber");
    }

    private String keycloakBenutzerAnlegen(String mandant, String benutzername, String rollenname) {
        Map<String, Object> passwort = Map.of("type", "password", "value", Testanmeldung.PASSWORT, "temporary", false);
        Map<String, Object> otp = Map.of("type", "otp", "userLabel", "Probe",
                "secretData", "{\"value\":\"" + Testanmeldung.OTP_SECRET + "\"}",
                "credentialData", "{\"subType\":\"totp\",\"digits\":6,\"counter\":0,\"period\":30,"
                        + "\"algorithm\":\"HmacSHA1\"}");
        Map<String, Object> nutzer = Map.of("username", benutzername, "enabled", true,
                "email", benutzername + "@example.invalid", "emailVerified", true, "firstName", "Probe",
                "lastName", "Person", "attributes", Map.of("mandant", List.of(mandant)),
                "requiredActions", List.of(), "credentials", List.of(passwort, otp));
        given().auth().oauth2(admin).contentType("application/json")
                .body(Map.of("ifResourceExists", "FAIL", "users", List.of(nutzer)))
                .post(adminPfad + "/partialImport").then().statusCode(200);
        String id = given().auth().oauth2(admin).queryParam("username", benutzername).queryParam("exact", true)
                .get(adminPfad + "/users").then().statusCode(200).extract().path("[0].id");
        Map<String, Object> rolle = given().auth().oauth2(admin).get(adminPfad + "/roles/" + rollenname)
                .then().statusCode(200).extract().jsonPath().getMap("$");
        given().auth().oauth2(admin).contentType("application/json").body(List.of(rolle))
                .post(adminPfad + "/users/" + id + "/role-mappings/realm").then().statusCode(204);
        return id;
    }

    @After("@benutzer-verwaltung")
    public void aufraeumen() throws SQLException {
        if (log != null) {
            log.close();
        }
        if (urspruenglichesStepUp != null) {
            QuarkusMock.installMockForType(urspruenglichesStepUp, StepUp.class);
        }
        if (loeschungMitKeycloak && eigeneId != null) {
            int status = given().auth().oauth2(admin).delete(adminPfad + "/users/" + eigeneId).statusCode();
            assertThat(status).isIn(204, 404);
        }
        if (loeschungMitKeycloak && fremdeId != null) {
            given().auth().oauth2(admin).delete(adminPfad + "/users/" + fremdeId).then().statusCode(204);
        }
        if (urspruenglicheIdentitaet != null) {
            QuarkusMock.installMockForType(urspruenglicheIdentitaet, IdentitaetsDienst.class);
        }
        if (urspruenglicheMitteilung != null) {
            QuarkusMock.installMockForType(urspruenglicheMitteilung, BenutzerMitteilung.class);
        }
        for (var eintrag : benutzer) {
            identitaet.loeschen(eintrag.getKey(), eintrag.getValue());
            tokenWiderrufZeileLoeschen(eintrag.getValue());
        }
        if (loeschDokument != null) {
            Testdokumente.loeschen(datenbank.get(), MANDANT, List.of(loeschDokument));
        }
        if (eigeneId != null) {
            tokenWiderrufZeileLoeschen(eigeneId);
        }
        for (ApiToken token : verwaltungsSchluessel) {
            apiTokens.widerrufen(new MandantCode("urinet"), token.id(), Optional.empty());
        }
        if (akteurId != null) {
            tokenWiderrufZeileLoeschen(akteurId);
            given().auth().oauth2(admin).delete(adminPfad + "/users/" + akteurId).then().statusCode(204);
        }
        if (zweiterAkteurId != null) {
            tokenWiderrufZeileLoeschen(zweiterAkteurId);
            given().auth().oauth2(admin).delete(adminPfad + "/users/" + zweiterAkteurId).then().statusCode(204);
        }
    }

    private void tokenWiderrufZeileLoeschen(String id) throws SQLException {
        try (Connection verbindung = datenbank.get().getConnection();
             PreparedStatement loeschen = verbindung.prepareStatement(
                     "delete from public.token_widerruf where benutzer = ?")) {
            loeschen.setString(1, id);
            loeschen.executeUpdate();
        }
    }

    @Angenommen("in zwei Mandanten gibt es je einen synthetischen Benutzer")
    public void benutzerInZweiMandanten() {
        eigeneId = anlegen(MANDANT, name);
        fremdeId = anlegen("vhf-gsk", "fremd." + name);
    }

    @Dann("sieht ein Inhaber des Beispielverbands nur die Benutzer seines Mandanten")
    public void beispielverbandBleibtGetrennt() throws SQLException {
        String code = "probe-verband";
        try (Connection verbindung = datenbank.get().getConnection();
             PreparedStatement abfrage = verbindung.prepareStatement(
                     "select 1 from public.mandant where code = ? and aktiv")) {
            abfrage.setString(1, code);
            try (ResultSet zeile = abfrage.executeQuery()) {
                if (!zeile.next()) {
                    provisionierung.anlegen(code, null, null);
                }
            }
        }
        String verbandsId = anlegen(code, "verband." + name);
        String verbandsAkteur = "probe-verband-" + UUID.randomUUID();
        String verbandsAkteurId = akteurAnlegen(code, verbandsAkteur);
        try {
            String token = Testanmeldung.token(verbandsAkteur);
            Response liste = given().auth().oauth2(token).queryParam("q", name).get(PFAD);
            assertThat(liste.statusCode()).isEqualTo(200);
            assertThat(liste.jsonPath().<String>getList("id")).containsExactly(verbandsId);
            assertThat(liste.asString()).doesNotContain(eigeneId, fremdeId);
            given().auth().oauth2(token).get(PFAD + "/" + eigeneId).then().statusCode(404);
            given().auth().oauth2(token).get(PFAD + "/" + fremdeId).then().statusCode(404);
            anfrage().get(PFAD + "/" + verbandsId).then().statusCode(404);
        } finally {
            given().auth().oauth2(admin).delete(adminPfad + "/users/" + verbandsAkteurId).then().statusCode(204);
        }
    }

    @Wenn("der Inhaber die Benutzerliste und beide Details abruft")
    public void benutzerAbrufen() {
        antworten.add(anfrage().queryParam("q", name).queryParam("groesse", 1).get(PFAD));
        antworten.add(anfrage().queryParam("q", name).queryParam("groesse", 1).queryParam("seite", 1).get(PFAD));
        antworten.add(anfrage().get(PFAD + "/" + eigeneId));
        antworten.add(anfrage().get(PFAD + "/" + fremdeId));
        antworten.add(anfrage().get(PFAD + "/" + UUID.randomUUID()));
    }

    @Dann("sieht er nur den eigenen Benutzer und fremde Kennungen ergeben 404")
    public void nurEigenerBenutzer() {
        assertThat(antworten.get(0).statusCode()).isEqualTo(200);
        assertThat(antworten.get(0).jsonPath().<String>getList("id")).containsExactly(eigeneId);
        assertThat(antworten.get(0).jsonPath().<String>getList("email"))
                .containsExactly(name + "@example.invalid");
        assertThat(antworten.get(1).jsonPath().getList("$")).isEmpty();
        assertThat(antworten.get(2).statusCode()).isEqualTo(200);
        assertThat(antworten.get(2).jsonPath().<String>get("vorname")).isEqualTo("Test");
        assertThat(antworten.get(2).jsonPath().<String>get("apiTokenVerwaltungspfad"))
                .isEqualTo("/verwaltung/api-tokens");
        assertThat(antworten.get(2).jsonPath().<String>get("apiTokenHinweis"))
                .contains("bleiben beim Sperren oder Deaktivieren gültig");
        assertThat(antworten.get(3).statusCode()).isEqualTo(404);
        assertThat(antworten.get(4).statusCode()).isEqualTo(404);
        assertThat(antworten.get(3).asString()).doesNotContain("fremd." + name);
    }

    @Wenn("der Inhaber die Rechte beider Benutzer abruft")
    public void benutzerrechteAbrufen() {
        antworten.add(anfrage().get(PFAD + "/" + eigeneId + "/rechte"));
        antworten.add(anfrage().get(PFAD + "/" + fremdeId + "/rechte"));
    }

    @Dann("sieht er die Rechte aus der Matrix nur für den eigenen Benutzer")
    public void benutzerrechtePruefen() {
        Response eigene = antworten.get(0);
        assertThat(eigene.statusCode()).isEqualTo(200);
        assertThat(eigene.jsonPath().<String>get("code")).isEqualTo("mitarbeiter");
        var rechte = eigene.jsonPath().<Map<String, Object>>getList("rechte");
        assertThat(rechte).hasSize(Aktion.values().length);
        for (Aktion aktion : Aktion.values()) {
            var recht = rechte.stream().filter(r -> aktion.name().equals(r.get("aktion"))).findFirst().orElseThrow();
            assertThat(recht.get("ergebnis")).isEqualTo(Rechtematrix.ergebnis(Rolle.MITARBEITER, aktion).name());
        }
        assertThat(antworten.get(1).statusCode()).isEqualTo(404);
    }

    @Angenommen("die frische Anmeldung ist für den Test bestätigt")
    public void stepUpBestaetigen() {
        urspruenglichesStepUp = ClientProxy.unwrap(stepUp);
        QuarkusMock.installMockForType(new StepUpMitAuthTime(null, Duration.ofMinutes(5)) {
            @Override
            public void pruefen() { }
        }, StepUp.class);
    }

    @Angenommen("die Anmeldung für eine kritische Aktion ist nicht frisch")
    public void stepUpNichtBestaetigen() {
        if (urspruenglichesStepUp == null) {
            urspruenglichesStepUp = ClientProxy.unwrap(stepUp);
        }
        QuarkusMock.installMockForType(new StepUpMitAuthTime(null, Duration.ofMinutes(5)) {
            @Override
            public void pruefen() {
                throw new StepUpNoetig(300);
            }
        }, StepUp.class);
    }

    @Angenommen("ein synthetischer Verwaltungszugang hat ein altes Token")
    public void altesToken() {
        altesToken = Testanmeldung.token(akteurName);
        assertThat(Testanmeldung.sub(akteurName)).isEqualTo(akteurId);
    }

    @Wenn("das Token des Inhabers widerrufen wird")
    public void inhaberTokenWiderrufen() {
        tokenWiderruf.setzen(Testanmeldung.sub(akteurName), Instant.now());
        assertThat(tokenWiderruf.widerrufen(akteurId, Instant.EPOCH)).isTrue();
    }

    @Dann("wird dieses Token innerhalb der Frist mit 401 abgewiesen")
    public void altesTokenAbgewiesen() {
        Response antwort = given().auth().oauth2(altesToken).get(PFAD);
        assertThat(ProblemDetails.pruefen(antwort, 401)).isEqualTo("NICHT_ANGEMELDET");
    }

    @Wenn("der Inhaber einen synthetischen Mitarbeiter anlegt")
    public void mitarbeiterAnlegen() {
        anlegenUeberApi("mitarbeiter");
    }

    @Dann("wird die kritische Aktion abgewiesen und kein Benutzer angelegt")
    public void stepUpNoetig() {
        Response antwort = antworten.getFirst();
        assertThat(antwort.statusCode()).isEqualTo(401);
        assertThat(antwort.jsonPath().getString("fehlercode")).isEqualTo("STEP_UP_NOETIG");
        assertThat(antwort.header("WWW-Authenticate"))
                .contains("insufficient_user_authentication", "max_age=\"300\"");
        Response liste = anfrage().queryParam("q", name).get(PFAD);
        assertThat(liste.statusCode()).isEqualTo(200);
        assertThat(liste.jsonPath().<String>getList("id")).isEmpty();
    }

    private void anlegenUeberApi(String rolle) {
        Response antwort = anfrage().contentType("application/json").body(Map.of(
                "benutzername", name, "email", name + "@example.invalid",
                "vorname", "Test", "nachname", "Person", "rolle", rolle)).post(PFAD);
        antworten.add(antwort);
        if (antwort.statusCode() == 201) {
            eigeneId = antwort.path("id");
            benutzer.add(Map.entry(MANDANT, eigeneId));
        }
    }

    @Dann("ist der Mitarbeiter mit Einrichtungspflichten im Mandanten angelegt und protokolliert")
    public void angelegtUndProtokolliert() throws SQLException {
        assertThat(antworten.getFirst().statusCode()).isEqualTo(201);
        var ziel = identitaet.benutzerLesen(MANDANT, eigeneId);
        assertThat(ziel.rollen()).containsExactly(Rolle.MITARBEITER);
        assertThat(ziel.aktionen()).containsExactlyInAnyOrder(
                IdentitaetsDienst.ErforderlicheAktion.VERIFY_EMAIL,
                IdentitaetsDienst.ErforderlicheAktion.UPDATE_PASSWORD,
                IdentitaetsDienst.ErforderlicheAktion.CONFIGURE_TOTP);
        try (Connection verbindung = datenbank.get().getConnection();
             PreparedStatement abfrage = verbindung.prepareStatement(
                     "select details::text from m_" + SCHEMA + ".zugriff_protokoll "
                             + "where details->>'ziel' = ? and details->>'phase' = 'ABGESCHLOSSEN' "
                             + "and details->>'vorgang' = 'ANLEGEN'")) {
            abfrage.setString(1, eigeneId);
            try (ResultSet zeile = abfrage.executeQuery()) {
                assertThat(zeile.next()).isTrue();
                assertThat(zeile.getString(1)).doesNotContain(name, "@example.invalid");
            }
            try (PreparedStatement verzeichnis = verbindung.prepareStatement(
                    "select name, rolle, anlass from m_" + SCHEMA + ".benutzer_verzeichnis where benutzer = ?")) {
                verzeichnis.setString(1, eigeneId);
                try (ResultSet zeile = verzeichnis.executeQuery()) {
                    assertThat(zeile.next()).isTrue();
                    assertThat(zeile.getString(1)).isEqualTo(name);
                    assertThat(zeile.getString(2)).isEqualTo("MITARBEITER");
                    assertThat(zeile.getString(3)).isEqualTo("ANLAGE");
                }
            }
        }
    }

    @Angenommen("ein synthetischer Mitarbeiter ist eingeladen")
    public void eingeladenerMitarbeiter() {
        eigeneId = anlegen(MANDANT, name);
    }

    @Wenn("der Inhaber den Zugang des Mitarbeiters zurücksetzt")
    public void zugangZuruecksetzen() {
        identitaet.einladen(MANDANT, eigeneId, Set.of(IdentitaetsDienst.ErforderlicheAktion.VERIFY_EMAIL),
                Duration.ofHours(1));
        antworten.add(anfrage().contentType("application/json").post(PFAD + "/" + eigeneId + "/zugang-zuruecksetzen"));
    }

    private String zweiteId;

    private IdentitaetsDienstStub stub() {
        Object roh = ClientProxy.unwrap(identitaet);
        assertThat(roh).isInstanceOf(IdentitaetsDienstStub.class);
        return (IdentitaetsDienstStub) roh;
    }

    @Angenommen("ein synthetischer Mitarbeiter ist eingeladen und hat {int} Passkeys")
    public void eingeladenerMitarbeiterMitPasskeys(int anzahl) {
        eigeneId = anlegen(MANDANT, name);
        stub().passkeysSetzen(MANDANT, eigeneId, anzahl);
        identitaet.einladen(MANDANT, eigeneId, Set.of(IdentitaetsDienst.ErforderlicheAktion.VERIFY_EMAIL),
                Duration.ofHours(1));
    }

    @Angenommen("ein zweiter synthetischer Mitarbeiter ist eingeladen ohne Passkey")
    public void zweiterMitarbeiterOhnePasskey() {
        zweiteId = anlegen(MANDANT, name + "-zwei");
    }

    @Wenn("der Inhaber den Zugang des Mitarbeiters mit der Option Passkeys zurücksetzt")
    public void zugangMitPasskeysZuruecksetzen() {
        antworten.add(anfrage().contentType("application/json").body(Map.of("passkeys", true))
                .post(PFAD + "/" + eigeneId + "/zugang-zuruecksetzen"));
    }

    @Wenn("der Inhaber den Zugang des Mitarbeiters ohne die Option Passkeys zurücksetzt")
    public void zugangOhnePasskeysZuruecksetzen() {
        antworten.add(anfrage().contentType("application/json").body(Map.of("passkeys", false))
                .post(PFAD + "/" + eigeneId + "/zugang-zuruecksetzen"));
    }

    @Wenn("der Inhaber den Zugang des Mitarbeiters mit einem ungültigen Körper zurücksetzt")
    public void zugangMitUngueltigemKoerperZuruecksetzen() {
        antworten.add(anfrage().contentType("application/json").body("{\"passkeys\":\"ja\",\"geraet\":\"x\"}")
                .post(PFAD + "/" + eigeneId + "/zugang-zuruecksetzen"));
    }

    @Dann("hat der Mitarbeiter keine Passkeys mehr und sein Zugang ist zurückgesetzt")
    public void keinePasskeysMehr() {
        assertThat(antworten.getLast().statusCode()).isEqualTo(204);
        var b = identitaet.benutzerLesen(MANDANT, eigeneId);
        assertThat(b.passkeys()).isZero();
        assertThat(b.aktionen()).contains(IdentitaetsDienst.ErforderlicheAktion.UPDATE_PASSWORD,
                IdentitaetsDienst.ErforderlicheAktion.CONFIGURE_TOTP);
    }

    @Dann("hat der Mitarbeiter weiterhin {int} Passkeys")
    public void weiterhinPasskeys(int anzahl) {
        assertThat(antworten.getLast().statusCode()).isEqualTo(204);
        assertThat(identitaet.benutzerLesen(MANDANT, eigeneId).passkeys()).isEqualTo(anzahl);
    }

    @Dann("das Protokoll nennt nur die Zahl {int} der gelöschten Passkeys")
    public void protokollNenntPasskeyZahl(int anzahl) throws SQLException {
        String details = abgeschlossenesProtokoll();
        assertThat(details).contains("\"passkeys\": " + anzahl).doesNotContain(name, "@example.invalid", "webauthn");
    }

    @Dann("das Protokoll nennt keine Zahl der Passkeys")
    public void protokollOhnePasskeyZahl() throws SQLException {
        assertThat(abgeschlossenesProtokoll()).doesNotContain("passkeys");
    }

    private String abgeschlossenesProtokoll() throws SQLException {
        try (Connection verbindung = datenbank.get().getConnection();
             PreparedStatement abfrage = verbindung.prepareStatement(
                     "select details::text from m_" + SCHEMA + ".zugriff_protokoll "
                             + "where details->>'ziel' = ? and details->>'phase' = 'ABGESCHLOSSEN' "
                             + "and details->>'vorgang' = 'ZUGANG_ZURUECKSETZEN'")) {
            abfrage.setString(1, eigeneId);
            try (ResultSet zeile = abfrage.executeQuery()) {
                assertThat(zeile.next()).isTrue();
                return zeile.getString(1);
            }
        }
    }

    @Dann("wird die Aktion wegen fehlendem Step-up abgewiesen und die Passkeys bleiben")
    public void aktionOhneStepUpAbgewiesen() {
        assertThat(antworten.getLast().statusCode()).isEqualTo(401);
        assertThat(antworten.getLast().jsonPath().getString("fehlercode")).isEqualTo("STEP_UP_NOETIG");
        assertThat(identitaet.benutzerLesen(MANDANT, eigeneId).passkeys()).isEqualTo(2);
    }

    @Dann("wird die Anfrage mit 400 abgewiesen und die Passkeys bleiben")
    public void anfrageAbgewiesen() {
        assertThat(antworten.getLast().statusCode()).isEqualTo(400);
        assertThat(identitaet.benutzerLesen(MANDANT, eigeneId).passkeys()).isEqualTo(2);
    }

    @Wenn("der Inhaber das Detail und die gefilterten Listen abruft")
    public void detailUndGefilterteListen() {
        antworten.add(anfrage().get(PFAD + "/" + eigeneId));
        antworten.add(anfrage().get(PFAD + "?passkey=ja"));
        antworten.add(anfrage().get(PFAD + "?passkey=nein"));
        antworten.add(anfrage().get(PFAD + "?passkey=vielleicht"));
        antworten.add(anfrage().get(PFAD + "/" + zweiteId));
    }

    @Dann("zeigt das Detail Passkey ja mit Anzahl {int} und der andere Passkey nein mit Anzahl {int}")
    public void detailZeigtPasskeys(int erste, int zweite) {
        var detail = antworten.get(0);
        assertThat(detail.statusCode()).isEqualTo(200);
        assertThat(detail.jsonPath().getBoolean("passkey")).isTrue();
        assertThat(detail.jsonPath().getInt("passkeyAnzahl")).isEqualTo(erste);
        // nur Ja/Nein und Anzahl: keine Gerätenamen, Schlüsselkennungen oder Credential-Angaben
        assertThat(detail.asString()).doesNotContain("webauthn", "credential", "userLabel");
        var anderes = antworten.get(4);
        assertThat(anderes.jsonPath().getBoolean("passkey")).isFalse();
        assertThat(anderes.jsonPath().getInt("passkeyAnzahl")).isEqualTo(zweite);
    }

    @Dann("der Filter ja liefert nur den ersten und der Filter nein nur den zweiten Mitarbeiter")
    public void filterLiefertPasskeyMitarbeiter() {
        assertThat(antworten.get(1).statusCode()).isEqualTo(200);
        assertThat(antworten.get(1).jsonPath().<String>getList("id")).containsExactly(eigeneId);
        assertThat(antworten.get(2).statusCode()).isEqualTo(200);
        assertThat(antworten.get(2).jsonPath().<String>getList("id")).containsExactly(zweiteId);
    }

    @Dann("ein ungültiger Filterwert wird mit 400 abgewiesen")
    public void ungueltigerFilter() {
        assertThat(antworten.get(3).statusCode()).isEqualTo(400);
    }

    @Dann("sind Passwort und TOTP erneut einzurichten und der Vorgang ist protokolliert")
    public void zugangZurueckgesetzt() throws SQLException {
        assertThat(antworten.getLast().statusCode()).isEqualTo(204);
        assertThat(tokenWiderruf.widerrufen(eigeneId, Instant.EPOCH)).isTrue();
        assertThat(identitaet.benutzerLesen(MANDANT, eigeneId).aktionen()).containsExactlyInAnyOrder(
                IdentitaetsDienst.ErforderlicheAktion.VERIFY_EMAIL,
                IdentitaetsDienst.ErforderlicheAktion.UPDATE_PASSWORD,
                IdentitaetsDienst.ErforderlicheAktion.CONFIGURE_TOTP);
        try (Connection verbindung = datenbank.get().getConnection();
             PreparedStatement abfrage = verbindung.prepareStatement(
                     "select details::text from m_" + SCHEMA + ".zugriff_protokoll "
                             + "where details->>'ziel' = ? and details->>'phase' = 'ABGESCHLOSSEN' "
                             + "and details->>'vorgang' = 'ZUGANG_ZURUECKSETZEN'")) {
            abfrage.setString(1, eigeneId);
            try (ResultSet zeile = abfrage.executeQuery()) {
                assertThat(zeile.next()).isTrue();
                assertThat(zeile.getString(1)).doesNotContain(name, "@example.invalid");
            }
        }
    }

    @Angenommen("ein synthetischer Benutzer hat eine Google-Verknüpfung")
    public void googleVerknuepft() {
        urspruenglicheIdentitaet = ClientProxy.unwrap(identitaet);
        googleVerknuepft = true;
        fehlerdienst = new IdentitaetsDienstStub() {
            @Override
            public synchronized Benutzer benutzerLesen(String mandant, String id) {
                Benutzer b = super.benutzerLesen(mandant, id);
                return new Benutzer(b.id(), b.benutzername(), b.email(), b.vorname(), b.nachname(), b.aktiv(),
                        b.rollen(), b.aktionen(), b.totp(), googleVerknuepft);
            }

            @Override
            public synchronized void googleLoesen(String mandant, String id) {
                super.googleLoesen(mandant, id);
                googleVerknuepft = false;
            }

            @Override
            public synchronized void sitzungenBeenden(String mandant, String id) {
                super.sitzungenBeenden(mandant, id);
                googleSitzungenBeendet = true;
            }
        };
        eigeneId = fehlerdienst.anlegen(MANDANT, new IdentitaetsDienst.Neuanlage(name,
                name + "@example.invalid", "Test", "Person"), Rolle.MITARBEITER);
        QuarkusMock.installMockForType(fehlerdienst, IdentitaetsDienst.class);
    }

    @Wenn("der Inhaber die Google-Verknüpfung löst")
    public void googleLoesen() {
        antworten.add(anfrage().contentType("application/json").delete(PFAD + "/" + eigeneId + "/google-verknuepfung"));
    }

    @Dann("ist die Verknüpfung entfernt und die Sitzungen sind beendet")
    public void googleGeloest() {
        assertThat(antworten.getLast().statusCode()).isEqualTo(204);
        assertThat(googleVerknuepft).isFalse();
        assertThat(googleSitzungenBeendet).isTrue();
        assertThat(tokenWiderruf.widerrufen(eigeneId, Instant.EPOCH)).isTrue();
    }

    @Angenommen("die Mitteilung an die bisherige Adresse wird aufgezeichnet")
    public void emailMitteilungAufzeichnen() {
        urspruenglicheMitteilung = ClientProxy.unwrap(mitteilung);
        QuarkusMock.installMockForType(new MailBenutzerMitteilung(mails -> {
            for (var mail : mails) {
                emailMitteilungen.addAll(mail.getTo());
            }
        }), BenutzerMitteilung.class);
    }

    @Wenn("der Inhaber die E-Mail-Adresse des Mitarbeiters mit der aktuellen Version ändert")
    public void emailAendern() {
        String etag = anfrage().get(PFAD + "/" + eigeneId).then().statusCode(200).extract().header("ETag");
        antworten.add(anfrage().contentType("application/json").header("If-Match", etag)
                .body("{\"email\":\"neu." + name + "@example.invalid\"}").patch(PFAD + "/" + eigeneId));
    }

    @Wenn("der Inhaber den Namen des Mitarbeiters mit der aktuellen Version ändert")
    public void namenAendern() {
        String etag = anfrage().get(PFAD + "/" + eigeneId).then().statusCode(200).extract().header("ETag");
        antworten.add(anfrage().contentType("application/json").header("If-Match", etag)
                .body("{\"vorname\":\"Neu" + name + "\",\"nachname\":\"Person" + name + "\"}")
                .patch(PFAD + "/" + eigeneId));
    }

    @Dann("ist der neue Name gespeichert und nicht im Verwaltungsprotokoll enthalten")
    public void namenGeaendert() throws SQLException {
        assertThat(antworten.getLast().statusCode()).isEqualTo(204);
        assertThat(identitaet.benutzerLesen(MANDANT, eigeneId).vorname()).isEqualTo("Neu" + name);
        assertThat(identitaet.benutzerLesen(MANDANT, eigeneId).nachname()).isEqualTo("Person" + name);
        try (Connection verbindung = datenbank.get().getConnection();
             PreparedStatement abfrage = verbindung.prepareStatement(
                     "select details::text, details->'felder'->>'vorname', details->'felder'->>'nachname' "
                             + "from m_" + SCHEMA + ".zugriff_protokoll where details->>'ziel' = ? "
                             + "and details->>'vorgang' = 'NAME_AENDERN' and details->>'phase' = 'ABGESCHLOSSEN'")) {
            abfrage.setString(1, eigeneId);
            try (ResultSet zeile = abfrage.executeQuery()) {
                assertThat(zeile.next()).isTrue();
                assertThat(zeile.getString(1)).doesNotContain("Neu" + name, "Person" + name);
                assertThat(zeile.getString(2)).isEqualTo("geändert");
                assertThat(zeile.getString(3)).isEqualTo("geändert");
            }
        }
    }

    @Dann("muss die neue Adresse bestätigt werden und die bisherige Adresse wird benachrichtigt")
    public void emailGeaendert() throws SQLException {
        assertThat(antworten.getLast().statusCode()).isEqualTo(204);
        assertThat(identitaet.benutzerLesen(MANDANT, eigeneId).email()).isEqualTo("neu." + name + "@example.invalid");
        assertThat(identitaet.benutzerLesen(MANDANT, eigeneId).aktionen())
                .contains(IdentitaetsDienst.ErforderlicheAktion.VERIFY_EMAIL);
        assertThat(emailMitteilungen).containsExactly(name + "@example.invalid");
        try (Connection verbindung = datenbank.get().getConnection();
             PreparedStatement abfrage = verbindung.prepareStatement(
                     "select details::text from m_" + SCHEMA + ".zugriff_protokoll "
                             + "where details->>'ziel' = ? and details->>'phase' = 'ABGESCHLOSSEN' "
                             + "and details->>'vorgang' = 'EMAIL_AENDERN'")) {
            abfrage.setString(1, eigeneId);
            try (ResultSet zeile = abfrage.executeQuery()) {
                assertThat(zeile.next()).isTrue();
                assertThat(zeile.getString(1)).doesNotContain(name, "@example.invalid");
            }
        }
    }

    @Angenommen("ein synthetischer aktiver Mitarbeiter besteht")
    public void aktiverMitarbeiter() {
        eingeladenerMitarbeiter();
        identitaet.einladen(MANDANT, eigeneId, Set.of(), Duration.ofHours(1));
    }

    @Angenommen("ein synthetischer aktiver Mitarbeiter mit erneuerbarem Token besteht")
    public void aktiverMitarbeiterMitErneuerbaremToken() {
        keycloakBenutzerMitDienstkonto("mitarbeiter", false);
        Response anmeldung = Testanmeldung.anmelden(name, true);
        assertThat(anmeldung.statusCode()).isEqualTo(200);
        altesToken = anmeldung.path("access_token");
        erneuerungsToken = anmeldung.path("refresh_token");
        given().auth().oauth2(altesToken).get("/api/v1/mandant/funktionen").then().statusCode(200);
    }

    @Wenn("der Inhaber die Rolle mit fehlender, veralteter und aktueller Version ändert")
    public void rolleAendern() {
        String pfad = PFAD + "/" + eigeneId;
        String etag = anfrage().get(pfad).then().statusCode(200).extract().header("ETag");
        antworten.add(anfrage().contentType("application/json").body(Map.of("rolle", "treuhaender"))
                .put(pfad + "/rolle"));
        antworten.add(anfrage().contentType("application/json").header("If-Match", "\"" + "0".repeat(64) + "\"")
                .body(Map.of("rolle", "treuhaender")).put(pfad + "/rolle"));
        antworten.add(anfrage().contentType("application/json").header("If-Match", etag)
                .body(Map.of("rolle", "treuhaender")).put(pfad + "/rolle"));
    }

    @Dann("ist nur die aktuelle Rollenänderung wirksam und ein erneuertes Token trägt die neue Rolle")
    public void rolleGeaendert() {
        assertThat(antworten).extracting(Response::statusCode).containsExactly(428, 412, 204);
        assertThat(identitaet.benutzerLesen(MANDANT, eigeneId).rollen()).containsExactly(Rolle.TREUHAENDER);
        assertThat(tokenWiderruf.widerrufen(eigeneId, Instant.EPOCH)).isTrue();
        Response altesTokenAntwort = given().auth().oauth2(altesToken).get("/api/v1/mandant/funktionen");
        assertThat(ProblemDetails.pruefen(altesTokenAntwort, 401)).isEqualTo("NICHT_ANGEMELDET");

        wartenAufNaechsteTokenSekunde();
        Response erneuertesToken = given().formParam("grant_type", "refresh_token")
                .formParam("client_id", "archiv-test").formParam("refresh_token", erneuerungsToken)
                .post(ConfigProvider.getConfig().getValue("quarkus.oidc.auth-server-url", String.class)
                        + "/protocol/openid-connect/token");
        assertThat(erneuertesToken.statusCode()).isEqualTo(200);
        String zugang = erneuertesToken.path("access_token");
        assertThat(tokenRollen(zugang)).contains("treuhaender").doesNotContain("mitarbeiter");
        assertThat(tokenWiderruf.widerrufen(eigeneId, tokenIat(zugang))).isFalse();
        given().auth().oauth2(zugang).get("/api/v1/mandant/funktionen").then().statusCode(200);
    }

    @Angenommen("ein synthetischer aktiver Mitarbeiter und zwei aktive Inhaber bestehen")
    public void mitarbeiterUndInhaber() {
        aktiverMitarbeiter();
        for (int i = 0; i < 2; i++) {
            String inhaberName = "inhaber." + i + "." + UUID.randomUUID().toString().substring(0, 8);
            String id = identitaet.anlegen(MANDANT, new IdentitaetsDienst.Neuanlage(inhaberName,
                    inhaberName + "@example.invalid", "Test", "Person"), Rolle.INHABER);
            benutzer.add(Map.entry(MANDANT, id));
            identitaet.einladen(MANDANT, id, Set.of(), Duration.ofHours(1));
        }
        zweiterAkteurName = "probe-19-11-" + UUID.randomUUID();
        zweiterAkteurId = akteurAnlegen(MANDANT, zweiterAkteurName);
    }

    @Angenommen("ein synthetischer aktiver Mitarbeiter und zwei Inhaber bestehen im Test-Keycloak")
    public void loeschungMitEchtemKeycloak() {
        keycloakBenutzerMitDienstkonto("mitarbeiter", true);
    }

    private void keycloakBenutzerMitDienstkonto(String zielRolle, boolean zweiterInhaber) {
        String realmUrl = ConfigProvider.getConfig().getValue("quarkus.oidc.auth-server-url", String.class);
        String basis = realmUrl.substring(0, realmUrl.indexOf("/realms/"));
        String clientId = given().auth().oauth2(admin).queryParam("clientId", "archiv-verwaltung")
                .get(adminPfad + "/clients").then().statusCode(200).extract().path("[0].id");
        String testSecret = given().auth().oauth2(admin).get(adminPfad + "/clients/" + clientId + "/client-secret")
                .then().statusCode(200).extract().path("value");
        urspruenglicheIdentitaet = ClientProxy.unwrap(identitaet);
        IdentitaetsDienst keycloak = new KeycloakAdminAdapter(new ObjectMapper(), Optional.of(basis),
                "urinet-archiv", "archiv-verwaltung", Optional.of(testSecret), "archiv-pwa", Optional.empty(),
                Duration.ofSeconds(2), Duration.ofSeconds(5),
                status -> { throw new AssertionError("Unerwarteter Dienstkonto-Alarm: " + status); });
        QuarkusMock.installMockForType(new IdentitaetsDienstStub() {
            @Override
            public Benutzer benutzerLesen(String mandant, String id) {
                return keycloak.benutzerLesen(mandant, id);
            }

            @Override
            public long inhaberZaehlen(String mandant) {
                return keycloak.inhaberZaehlen(mandant);
            }

            @Override
            public void rolleSetzen(String mandant, String id, Rolle rolle) {
                keycloak.rolleSetzen(mandant, id, rolle);
            }

            @Override
            public void aktivieren(String mandant, String id, boolean aktiv) {
                keycloak.aktivieren(mandant, id, aktiv);
            }

            @Override
            public void sitzungenBeenden(String mandant, String id) {
                keycloak.sitzungenBeenden(mandant, id);
            }

            @Override
            public void loeschen(String mandant, String id) {
                if (loeschungBegonnen != null) {
                    loeschungBegonnen.countDown();
                    try {
                        if (!loeschungFortsetzen.await(10, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("Zweiter Löschaufruf ist ausgeblieben");
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("Test unterbrochen", e);
                    }
                }
                keycloak.loeschen(mandant, id);
            }

            @Override
            public boolean anmeldungGesperrt(String mandant, String id) {
                return keycloak.anmeldungGesperrt(mandant, id);
            }

            @Override
            public void sperreAufheben(String mandant, String id) {
                keycloak.sperreAufheben(mandant, id);
            }
        }, IdentitaetsDienst.class);
        loeschungMitKeycloak = true;
        eigeneId = keycloakBenutzerAnlegen(MANDANT, name, zielRolle);
        if (zweiterInhaber) {
            zweiterAkteurName = "probe-19-12-" + UUID.randomUUID();
            zweiterAkteurId = akteurAnlegen(MANDANT, zweiterAkteurName);
        }
    }

    @Angenommen("ein aktiver und ein deaktivierter Inhaber bestehen im Test-Keycloak")
    public void einAktiverUndEinDeaktivierterInhaber() {
        inhaberFuerLoeschung(false);
    }

    @Angenommen("zwei aktive und ein deaktivierter Inhaber bestehen im Test-Keycloak")
    public void zweiAktiveUndEinDeaktivierterInhaber() {
        inhaberFuerLoeschung(true);
    }

    private void inhaberFuerLoeschung(boolean zweiterInhaber) {
        keycloakBenutzerMitDienstkonto("inhaber", zweiterInhaber);
        anfrage().contentType("application/json").body(Map.of("grund", "AUSTRITT"))
                .post(PFAD + "/" + eigeneId + "/deaktivieren").then().statusCode(204);
        assertThat(identitaet.inhaberZaehlen(MANDANT)).isEqualTo(zweiterInhaber ? 2 : 1);
    }

    @Wenn("der aktive Inhaber den deaktivierten Inhaber nach der Frist löscht")
    public void deaktiviertenInhaberLoeschen() throws SQLException {
        loeschfristAbgelaufen();
        antworten.add(anfrage().contentType("application/json").body(Map.of("grund", "AUSTRITT"))
                .post(PFAD + "/" + eigeneId + "/loeschen"));
    }

    @Dann("ist nur der deaktivierte Inhaber gelöscht")
    public void deaktivierterInhaberGeloescht() {
        assertThat(antworten.getLast().statusCode()).isEqualTo(204);
        assertThat(anfrage().get(PFAD + "/" + eigeneId).statusCode()).isEqualTo(404);
        assertThat(identitaet.inhaberZaehlen(MANDANT)).isEqualTo(1);
    }

    @Wenn("der Inhaber die Inhaberrolle beantragt und beide Inhaber darüber entscheiden")
    public void inhabervergabeBeantragen() {
        String pfad = PFAD + "/" + eigeneId;
        String etag = anfrage().get(pfad).then().statusCode(200).extract().header("ETag");
        Response beantragt = anfrage().contentType("application/json").header("If-Match", etag)
                .body(Map.of("rolle", "inhaber")).put(pfad + "/rolle");
        antworten.add(beantragt);
        String antragId = beantragt.then().statusCode(202).extract().path("antragId");
        assertThat(identitaet.benutzerLesen(MANDANT, eigeneId).rollen()).containsExactly(Rolle.MITARBEITER);
        urspruenglicheIdentitaet = ClientProxy.unwrap(identitaet);
        QuarkusMock.installMockForType(new IdentitaetsDienstStub() {
            @Override
            public synchronized Benutzer benutzerLesen(String mandant, String id) {
                if (mandant.equals(MANDANT) && (id.equals(akteurId) || id.equals(zweiterAkteurId))) {
                    return new Benutzer(id, "probe", "probe@example.invalid", "Test", "Person", true,
                            Set.of(Rolle.INHABER), Set.of(), true, false);
                }
                return urspruenglicheIdentitaet.benutzerLesen(mandant, id);
            }

            @Override
            public synchronized void rolleSetzen(String mandant, String id, Rolle rolle) {
                urspruenglicheIdentitaet.rolleSetzen(mandant, id, rolle);
            }
        }, IdentitaetsDienst.class);
        String antragPfad = PFAD + "/antraege/" + antragId + "/bestaetigen";
        antworten.add(anfrage().contentType("application/json").post(antragPfad));
        antworten.add(given().auth().oauth2(Testanmeldung.token(zweiterAkteurName))
                .contentType("application/json").post(antragPfad));
    }

    @Dann("kann nur der zweite Inhaber die Vergabe bestätigen")
    public void inhabervergabeBestaetigt() {
        assertThat(antworten).extracting(Response::statusCode).containsExactly(202, 403, 204);
        assertThat(urspruenglicheIdentitaet.benutzerLesen(MANDANT, eigeneId).rollen()).containsExactly(Rolle.INHABER);
        assertThat(tokenWiderruf.widerrufen(eigeneId, Instant.EPOCH)).isTrue();
    }

    @Angenommen("zwei aktive Inhaber bestehen")
    public void zweiInhaberFuerGleichzeitigeRollenwechsel() {
        zweiterAkteurName = "probe-19-11-" + UUID.randomUUID();
        zweiterAkteurId = akteurAnlegen(MANDANT, zweiterAkteurName);
    }

    @Wenn("beide Inhaber gleichzeitig die Rolle des anderen ändern")
    public void gleichzeitigeRollenwechsel() throws Exception {
        String erstesToken = Testanmeldung.token(akteurName);
        String zweitesToken = Testanmeldung.token(zweiterAkteurName);
        String ersterPfad = PFAD + "/" + zweiterAkteurId;
        String zweiterPfad = PFAD + "/" + akteurId;
        gleichzeitigeRollen.put(akteurId, Rolle.INHABER);
        gleichzeitigeRollen.put(zweiterAkteurId, Rolle.INHABER);
        urspruenglicheIdentitaet = ClientProxy.unwrap(identitaet);
        CountDownLatch begonnen = new CountDownLatch(1);
        CountDownLatch fortsetzen = new CountDownLatch(1);
        QuarkusMock.installMockForType(new IdentitaetsDienstStub() {
            @Override
            public Benutzer benutzerLesen(String mandant, String id) {
                Rolle rolle = gleichzeitigeRollen.get(id);
                if (!MANDANT.equals(mandant) || rolle == null) {
                    throw new FachFehler(Fehlercode.NICHT_GEFUNDEN);
                }
                return new Benutzer(id, "probe", "probe@example.invalid", "Test", "Person", true,
                        Set.of(rolle), Set.of(), true, false);
            }

            @Override
            public boolean anmeldungGesperrt(String mandant, String id) {
                benutzerLesen(mandant, id);
                return false;
            }

            @Override
            public long inhaberZaehlen(String mandant) {
                return gleichzeitigeRollen.values().stream().filter(rolle -> rolle == Rolle.INHABER).count();
            }

            @Override
            public void rolleSetzen(String mandant, String id, Rolle rolle) {
                begonnen.countDown();
                try {
                    if (!fortsetzen.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Zweiter Aufruf ist ausgeblieben");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Test unterbrochen", e);
                }
                benutzerLesen(mandant, id);
                gleichzeitigeRollen.put(id, rolle);
            }
        }, IdentitaetsDienst.class);
        String erstesEtag = given().auth().oauth2(erstesToken).get(ersterPfad).then()
                .statusCode(200).extract().header("ETag");
        String zweitesEtag = given().auth().oauth2(zweitesToken).get(zweiterPfad).then()
                .statusCode(200).extract().header("ETag");
        CompletableFuture<Response> erster = CompletableFuture.supplyAsync(() -> given().auth().oauth2(erstesToken)
                .contentType("application/json").header("If-Match", erstesEtag)
                .body(Map.of("rolle", "mitarbeiter")).put(ersterPfad + "/rolle"));
        try {
            assertThat(begonnen.await(10, TimeUnit.SECONDS)).isTrue();
            antworten.add(given().auth().oauth2(zweitesToken).contentType("application/json")
                    .header("If-Match", zweitesEtag).body(Map.of("rolle", "mitarbeiter"))
                    .put(zweiterPfad + "/rolle"));
        } finally {
            fortsetzen.countDown();
        }
        antworten.add(erster.get(20, TimeUnit.SECONDS));
    }

    @Dann("wird ein Vorgang gesperrt und mindestens ein Inhaber bleibt aktiv")
    public void einInhaberBleibt() {
        assertThat(antworten).extracting(Response::statusCode).containsExactly(409, 204);
        assertThat(gleichzeitigeRollen.values().stream().filter(rolle -> rolle == Rolle.INHABER)).hasSize(1);
    }

    @Wenn("der Inhaber den Mitarbeiter sperrt und wieder entsperrt")
    public void sperrenUndEntsperren() {
        Response sperrung = anfrage().contentType("application/json").body("{\"grund\":\"SICHERHEIT\"}")
                .post(PFAD + "/" + eigeneId + "/sperren");
        assertThat(sperrung.statusCode()).isEqualTo(204);
        antworten.add(sperrung);
        Response altesTokenAntwort = given().auth().oauth2(altesToken).get("/api/v1/mandant/funktionen");
        assertThat(ProblemDetails.pruefen(altesTokenAntwort, 401)).isEqualTo("NICHT_ANGEMELDET");
        antworten.add(anfrage().get(PFAD + "/" + eigeneId));
        antworten.add(anfrage().contentType("application/json").post(PFAD + "/" + eigeneId + "/entsperren"));
    }

    @Dann("ist der Mitarbeiter wieder aktiv und sein altes Token bleibt abgewiesen")
    public void wiederAktiv() {
        assertThat(antworten).extracting(Response::statusCode).containsExactly(204, 200, 204);
        assertThat(antworten.get(1).jsonPath().<String>get("status")).isEqualTo("GESPERRT");
        assertThat(tokenWiderruf.widerrufen(eigeneId, Instant.EPOCH)).isTrue();
        assertThat(identitaet.benutzerLesen(MANDANT, eigeneId).aktiv()).isTrue();
        Response altesTokenAntwort = given().auth().oauth2(altesToken).get("/api/v1/mandant/funktionen");
        assertThat(ProblemDetails.pruefen(altesTokenAntwort, 401)).isEqualTo("NICHT_ANGEMELDET");
        anfrage().get(PFAD + "/" + eigeneId).then().statusCode(200).body("status", org.hamcrest.Matchers.is("AKTIV"));
    }

    @Wenn("der Inhaber den Mitarbeiter deaktiviert und reaktiviert")
    public void deaktivierenUndReaktivieren() {
        antworten.add(anfrage().contentType("application/json").body("{\"grund\":\"AUSTRITT\"}")
                .post(PFAD + "/" + eigeneId + "/deaktivieren"));
        antworten.add(anfrage().get(PFAD + "/" + eigeneId));
        antworten.add(anfrage().contentType("application/json").post(PFAD + "/" + eigeneId + "/reaktivieren"));
    }

    @Angenommen("der Mitarbeiter ist mit einem Dokument verknüpft und deaktiviert")
    public void mitarbeiterDeaktivierenUndVerknuepfen() throws SQLException {
        loeschDokument = Testdokumente.anlegen(datenbank.get(), new Testdokumente.Angaben(
                MANDANT, null, "PERSONAL", false, eigeneId, eigeneId, "EINGANGSKORB"));
        anfrage().contentType("application/json").body(Map.of("grund", "AUSTRITT"))
                .post(PFAD + "/" + eigeneId + "/deaktivieren").then().statusCode(204);
    }

    @Wenn("der Inhaber Vorschau und Löschung vor und nach der Frist ausführt")
    public void loeschungNachFrist() throws SQLException {
        String pfad = PFAD + "/" + eigeneId;
        Response vorschau = anfrage().get(pfad + "/loeschvorschau");
        assertThat(vorschau.statusCode()).isEqualTo(200);
        assertThat(vorschau.jsonPath().getLong("dokumente")).isEqualTo(1);
        assertThat(vorschau.jsonPath().getLong("protokolleintraege")).isGreaterThanOrEqualTo(2);
        antworten.add(anfrage().contentType("application/json").body(Map.of("grund", "AUSTRITT"))
                .post(pfad + "/loeschen"));
        loeschfristAbgelaufen();
        antworten.add(anfrage().contentType("application/json").body(Map.of("grund", "AUSTRITT"))
                .post(pfad + "/loeschen"));
        if (antworten.getLast().statusCode() == 204) {
            benutzer.remove(Map.entry(MANDANT, eigeneId));
        }
    }

    @Dann("wird nur nach der Frist gelöscht und die Archivverweise bleiben bestehen")
    public void loeschungNachFristGeprueft() throws SQLException {
        assertThat(antworten).extracting(Response::statusCode).containsExactly(409, 204);
        assertThat(anfrage().get(PFAD + "/" + eigeneId).statusCode()).isEqualTo(404);
        assertThat(tokenWiderruf.widerrufen(eigeneId, Instant.EPOCH)).isTrue();
        try (Connection verbindung = datenbank.get().getConnection();
             PreparedStatement abfrage = verbindung.prepareStatement(
                     "select erstellt_von, person_benutzer from m_" + SCHEMA + ".dokument where id = ?")) {
            abfrage.setObject(1, loeschDokument);
            try (ResultSet zeile = abfrage.executeQuery()) {
                assertThat(zeile.next()).isTrue();
                assertThat(zeile.getString(1)).isEqualTo(eigeneId);
                assertThat(zeile.getString(2)).isEqualTo(eigeneId);
            }
        }
        try (Connection verbindung = datenbank.get().getConnection();
             PreparedStatement abfrage = verbindung.prepareStatement(
                     "select name, rolle from m_" + SCHEMA + ".benutzer_verzeichnis where benutzer = ?")) {
            abfrage.setString(1, eigeneId);
            try (ResultSet zeile = abfrage.executeQuery()) {
                assertThat(zeile.next()).isTrue();
                assertThat(zeile.getString(1)).isEqualTo(name);
                assertThat(zeile.getString(2)).isEqualTo("MITARBEITER");
            }
        }
        anlegenUeberApi("mitarbeiter");
        assertThat(antworten.getLast().statusCode()).isEqualTo(409);
        assertThat(antworten.getLast().jsonPath().getString("fehlercode")).isEqualTo("BENUTZER_EXISTIERT");
    }

    @Wenn("der Inhaber nach der Frist ohne frische Anmeldung löscht")
    public void loeschenOhneStepUp() throws SQLException {
        loeschfristAbgelaufen();
        stepUpNichtBestaetigen();
        antworten.add(anfrage().contentType("application/json").body(Map.of("grund", "AUSTRITT"))
                .post(PFAD + "/" + eigeneId + "/loeschen"));
    }

    @Dann("wird die Löschung wegen fehlendem Step-up abgewiesen")
    public void loeschungOhneStepUpAbgewiesen() {
        assertThat(antworten.getLast().statusCode()).isEqualTo(401);
        assertThat(antworten.getLast().jsonPath().getString("fehlercode")).isEqualTo("STEP_UP_NOETIG");
        assertThat(identitaet.benutzerLesen(MANDANT, eigeneId).aktiv()).isFalse();
    }

    @Wenn("zwei Inhaber einen Löschantrag nach der Frist entscheiden")
    public void loeschantragEntscheiden() throws SQLException {
        loeschfristAbgelaufen();
        assertThat(Testanmeldung.sub(akteurName)).isEqualTo(akteurId);
        assertThat(Testanmeldung.sub(zweiterAkteurName)).isEqualTo(zweiterAkteurId);
        assertThat(identitaet.benutzerLesen(MANDANT, akteurId).rollen()).contains(Rolle.INHABER);
        assertThat(identitaet.benutzerLesen(MANDANT, zweiterAkteurId).rollen()).contains(Rolle.INHABER);
        Response antrag = anfrage().contentType("application/json").body(Map.of("grund", "AUSTRITT"))
                .post(PFAD + "/" + eigeneId + "/loeschen");
        antworten.add(antrag);
        String id = antrag.then().statusCode(202).extract().path("antragId");
        assertThat(identitaet.benutzerLesen(MANDANT, eigeneId)).isNotNull();
        String pfad = PFAD + "/antraege/" + id + "/bestaetigen";
        Response selbst = anfrage().contentType("application/json").post(pfad);
        assertThat(selbst.statusCode()).as(selbst.asString()).isEqualTo(403);
        antworten.add(selbst);
        Response zweiter = given().auth().oauth2(Testanmeldung.token(zweiterAkteurName))
                .contentType("application/json").post(pfad);
        assertThat(zweiter.statusCode()).as(zweiter.asString()).isEqualTo(204);
        antworten.add(zweiter);
        if (antworten.getLast().statusCode() == 204) {
            benutzer.remove(Map.entry(MANDANT, eigeneId));
        }
    }

    @Dann("löscht nur die Bestätigung des zweiten Inhabers den Benutzer")
    public void loeschantragGeprueft() {
        assertThat(antworten).extracting(Response::statusCode).containsExactly(202, 403, 204);
        assertThat(anfrage().get(PFAD + "/" + eigeneId).statusCode()).isEqualTo(404);
        assertThat(tokenWiderruf.widerrufen(eigeneId, Instant.EPOCH)).isTrue();
    }

    @Wenn("der Inhaber unzulässige Löschungen beantragt")
    public void unzulaessigeLoeschungen() {
        fremdeId = keycloakBenutzerAnlegen("vhf-gsk", "fremd." + name, "mitarbeiter");
        for (String id : List.of(akteurId, UUID.randomUUID().toString(), fremdeId, eigeneId)) {
            antworten.add(anfrage().contentType("application/json").body(Map.of("grund", "AUSTRITT"))
                    .post(PFAD + "/" + id + "/loeschen"));
        }
        antworten.add(anfrage().contentType("application/json").body(Map.of("grund", "ungueltig"))
                .post(PFAD + "/" + eigeneId + "/loeschen"));
    }

    @Dann("werden die Löschungen abgewiesen und der Mitarbeiter bleibt aktiv")
    public void unzulaessigeLoeschungenAbgewiesen() {
        assertThat(antworten).extracting(Response::statusCode).containsExactly(403, 404, 404, 409, 422);
        assertThat(identitaet.benutzerLesen(MANDANT, eigeneId).aktiv()).isTrue();
    }

    @Wenn("Löschanträge abgelehnt, zurückgezogen und nach Ablauf bestätigt werden")
    public void loeschantraegeOhneLoeschung() throws SQLException {
        loeschfristAbgelaufen();
        String zweitesToken = Testanmeldung.token(zweiterAkteurName);
        String erster = loeschantragAnlegen();
        antworten.add(given().auth().oauth2(zweitesToken).contentType("application/json")
                .post(PFAD + "/antraege/" + erster + "/ablehnen"));
        String zweiter = loeschantragAnlegen();
        antworten.add(anfrage().contentType("application/json")
                .post(PFAD + "/antraege/" + zweiter + "/zurueckziehen"));
        String dritter = loeschantragAnlegen();
        try (Connection verbindung = datenbank.get().getConnection();
             PreparedStatement befehl = verbindung.prepareStatement(
                     "update m_" + SCHEMA + ".verwaltung_antrag set beantragt_am = clock_timestamp() - interval '4 days', "
                             + "gueltig_bis = clock_timestamp() - interval '1 day' where id = ?")) {
            befehl.setObject(1, UUID.fromString(dritter));
            assertThat(befehl.executeUpdate()).isEqualTo(1);
        }
        antworten.add(given().auth().oauth2(zweitesToken).contentType("application/json")
                .post(PFAD + "/antraege/" + dritter + "/bestaetigen"));
    }

    private String loeschantragAnlegen() {
        return anfrage().contentType("application/json").body(Map.of("grund", "AUSTRITT"))
                .post(PFAD + "/" + eigeneId + "/loeschen").then().statusCode(202).extract().path("antragId");
    }

    @Dann("wird kein Benutzer durch diese Anträge gelöscht")
    public void loeschantraegeOhneWirkung() {
        assertThat(antworten).extracting(Response::statusCode).containsExactly(204, 204, 409);
        assertThat(identitaet.benutzerLesen(MANDANT, eigeneId).aktiv()).isFalse();
    }

    @Wenn("ein Löschantrag gleichzeitig zweimal bestätigt wird")
    public void loeschantragGleichzeitigBestaetigen() throws Exception {
        loeschfristAbgelaufen();
        String antrag = loeschantragAnlegen();
        String token = Testanmeldung.token(zweiterAkteurName);
        String pfad = PFAD + "/antraege/" + antrag + "/bestaetigen";
        loeschungBegonnen = new CountDownLatch(1);
        loeschungFortsetzen = new CountDownLatch(1);
        CompletableFuture<Response> erster = CompletableFuture.supplyAsync(() -> given().auth().oauth2(token)
                .contentType("application/json").post(pfad));
        try {
            assertThat(loeschungBegonnen.await(10, TimeUnit.SECONDS)).isTrue();
            antworten.add(given().auth().oauth2(token).contentType("application/json").post(pfad));
        } finally {
            loeschungFortsetzen.countDown();
        }
        antworten.add(erster.get(20, TimeUnit.SECONDS));
    }

    @Dann("wird nur eine Bestätigung ausgeführt")
    public void loeschungNurEinmal() {
        assertThat(antworten).extracting(Response::statusCode).containsExactly(409, 204);
        assertThat(anfrage().get(PFAD + "/" + eigeneId).statusCode()).isEqualTo(404);
    }

    private void loeschfristAbgelaufen() throws SQLException {
        try (Connection verbindung = datenbank.get().getConnection();
             PreparedStatement befehl = verbindung.prepareStatement(
                     "update m_" + SCHEMA + ".benutzer_status set seit = clock_timestamp() - interval '31 days' "
                             + "where benutzer = ? and art = 'DEAKTIVIERT'")) {
            befehl.setString(1, eigeneId);
            assertThat(befehl.executeUpdate()).isEqualTo(1);
        }
    }

    @Dann("ist der Austritt verzeichnet und eine neue Zugangseinrichtung verlangt")
    public void austrittUndNeuerZugang() throws SQLException {
        assertThat(antworten).extracting(Response::statusCode).containsExactly(204, 200, 204);
        assertThat(antworten.get(1).jsonPath().<String>get("status")).isEqualTo("DEAKTIVIERT");
        var benutzer = identitaet.benutzerLesen(MANDANT, eigeneId);
        assertThat(tokenWiderruf.widerrufen(eigeneId, Instant.EPOCH)).isTrue();
        assertThat(benutzer.aktiv()).isTrue();
        assertThat(benutzer.rollen()).containsExactly(Rolle.MITARBEITER);
        assertThat(benutzer.aktionen()).contains(IdentitaetsDienst.ErforderlicheAktion.UPDATE_PASSWORD,
                IdentitaetsDienst.ErforderlicheAktion.CONFIGURE_TOTP);
        try (Connection verbindung = datenbank.get().getConnection();
             PreparedStatement abfrage = verbindung.prepareStatement(
                     "select count(*) from m_" + SCHEMA
                             + ".benutzer_verzeichnis where benutzer = ? and anlass = 'AUSTRITT'")) {
            abfrage.setString(1, eigeneId);
            try (ResultSet zeile = abfrage.executeQuery()) {
                assertThat(zeile.next()).isTrue();
                assertThat(zeile.getInt(1)).isEqualTo(1);
            }
        }
    }

    @Wenn("der Inhaber die Einladung viermal erneut sendet")
    public void einladungenSenden() {
        for (int i = 0; i < 4; i++) {
            antworten.add(anfrage().contentType("application/json").post(PFAD + "/" + eigeneId + "/einladung"));
        }
    }

    @Dann("sind drei Einladungen erlaubt und die vierte wird begrenzt")
    public void einladungenBegrenzt() {
        assertThat(antworten).extracting(Response::statusCode).containsExactly(204, 204, 204, 429);
        assertThat(antworten.getLast().header("Retry-After")).isNotBlank();
    }

    @Wenn("der Inhaber ungültige und bereits vergebene Angaben zur Anlage sendet")
    public void ungueltigeAngaben() {
        antworten.add(anfrage().contentType("application/json").body(Map.of(
                "benutzername", "x", "email", "ungültig", "vorname", "Test",
                "nachname", "Person", "rolle", "mitarbeiter")).post(PFAD));
        antworten.add(anfrage().contentType("application/json").body(Map.of(
                "benutzername", "fremd." + name, "email", "anders@example.invalid",
                "vorname", "Test", "nachname", "Person", "rolle", "mitarbeiter")).post(PFAD));
    }

    @Dann("werden Formfehler und Konflikt ohne die fremden Angaben gemeldet")
    public void formfehlerUndKonflikt() {
        assertThat(antworten.get(0).statusCode()).isEqualTo(422);
        assertThat(antworten.get(0).jsonPath().<List<?>>get("befunde")).isNotEmpty();
        assertThat(antworten.get(1).statusCode()).isEqualTo(409);
        assertThat(antworten.get(1).jsonPath().<String>get("fehlercode")).isEqualTo("BENUTZER_EXISTIERT");
        assertThat(antworten.get(1).asString()).doesNotContain("fremd." + name, "vhf-gsk");
    }

    @Angenommen("ein Benutzername wurde in einem vollständig abgebauten Testmandanten vergeben")
    public void nameAusAbgebautemMandanten() throws SQLException {
        String quelle = "b19-" + UUID.randomUUID().toString().substring(0, 8);
        KonfigurationSeed original = ClientProxy.unwrap(seed);
        QuarkusMock.installMockForType(new KlassenpfadKonfigurationSeed(codec) {
            @Override
            public Konfiguration lesen(MandantCode ignoriert) {
                return original.lesen(new MandantCode("urinet"));
            }
        }, KonfigurationSeed.class);
        try {
            provisionierung.anlegen(quelle, null, null);
        } finally {
            QuarkusMock.installMockForType(original, KonfigurationSeed.class);
        }
        String bisherigerAkteur = akteurName;
        String quellAkteur = "probe-19-7-" + UUID.randomUUID();
        String quellAkteurId = akteurAnlegen(quelle, quellAkteur);
        try {
            akteurName = quellAkteur;
            Response angelegt = anfrage().contentType("application/json").body(Map.of(
                    "benutzername", name, "email", name + "@example.invalid",
                    "vorname", "Test", "nachname", "Person", "rolle", "mitarbeiter")).post(PFAD);
            assertThat(angelegt.statusCode()).isEqualTo(201);
            identitaet.loeschen(quelle, angelegt.path("id"));
        } finally {
            akteurName = bisherigerAkteur;
            given().auth().oauth2(admin).delete(adminPfad + "/users/" + quellAkteurId).then().statusCode(204);
        }
        spaetererBestandsname = "alt." + name;
        try (Connection verbindung = datenbank.get().getConnection();
             PreparedStatement einfuegen = verbindung.prepareStatement(
                     "insert into " + new MandantCode(quelle).schema() + ".benutzer_verzeichnis "
                             + "(benutzer, name, gueltig_von, anlass) values (?, ?, clock_timestamp(), 'AKTION')")) {
            einfuegen.setString(1, UUID.randomUUID().toString());
            einfuegen.setString(2, spaetererBestandsname);
            einfuegen.executeUpdate();
        }
        UUID export = UUID.randomUUID();
        try (Connection verbindung = datenbank.get().getConnection();
             PreparedStatement deaktivieren = verbindung.prepareStatement(
                     "update public.mandant set aktiv = false, deaktiviert_am = clock_timestamp() - interval '31 days', "
                             + "vollexport_bestaetigt = ?, abbau_wer = 'test', abbau_grund = 'test' where code = ?")) {
            deaktivieren.setObject(1, export);
            deaktivieren.setString(2, quelle);
            assertThat(deaktivieren.executeUpdate()).isEqualTo(1);
        }
        var bericht = abbau.ausfuehren(new MandantCode(quelle), export, true, false,
                new MandantAbbau.Anlass("test", "test"));
        assertThat(bericht.zustand()).isEqualTo(MandantAbbau.Zustand.ABGESCHLOSSEN);
        assertThat(bericht.schemaEntfernt()).isTrue();
        assertThat(bericht.bucketEntfernt()).isTrue();
    }

    @Dann("wird die Wiedervergabe ohne Angabe des früheren Mandanten abgewiesen")
    public void wiedervergabeAbgewiesen() {
        assertThat(antworten.getLast().statusCode()).isEqualTo(409);
        assertThat(antworten.getLast().jsonPath().<String>get("fehlercode")).isEqualTo("BENUTZER_EXISTIERT");
        assertThat(antworten.getLast().asString()).doesNotContain(name, "@example.invalid", "b19-");
        Response bestand = anfrage().contentType("application/json").body(Map.of(
                "benutzername", spaetererBestandsname, "email", spaetererBestandsname + "@example.invalid",
                "vorname", "Test", "nachname", "Person", "rolle", "mitarbeiter")).post(PFAD);
        assertThat(bestand.statusCode()).isEqualTo(409);
        assertThat(bestand.jsonPath().<String>get("fehlercode")).isEqualTo("BENUTZER_EXISTIERT");
        assertThat(bestand.asString()).doesNotContain(spaetererBestandsname);
    }

    @Angenommen("der Mandant hat {int} deaktivierte synthetische Benutzer")
    public void deaktivierteBenutzer(int anzahl) {
        for (int i = 0; i < anzahl; i++) {
            String id = anlegen(MANDANT, name + "." + i);
            identitaet.aktivieren(MANDANT, id, false);
        }
    }

    @Dann("wird die Anlage wegen der Höchstzahl und eine übergrosse Anfrage abgewiesen")
    public void hoechstzahl() {
        assertThat(antworten.getFirst().statusCode()).isEqualTo(409);
        assertThat(antworten.getFirst().jsonPath().<String>get("fehlercode")).isEqualTo("STATUS_KONFLIKT");
        anfrage().contentType("application/json").body(" ".repeat(8193)).post(PFAD)
                .then().statusCode(413);
    }

    @Angenommen("der Identitätsdienst weist den Einladungsversand eindeutig ab")
    public void versandfehler() {
        urspruenglicheIdentitaet = ClientProxy.unwrap(identitaet);
        fehlerdienst = new IdentitaetsDienstStub() {
            @Override
            public void einladen(String mandant, String id, Set<ErforderlicheAktion> aktionen, Duration gueltigkeit) {
                eigeneId = id;
                throw new FachFehler(Fehlercode.IDENTITAETSDIENST_FEHLER);
            }
        };
        QuarkusMock.installMockForType(fehlerdienst, IdentitaetsDienst.class);
    }

    @Dann("wird der Benutzer entfernt und der Fehlerabschluss protokolliert")
    public void kompensation() throws SQLException {
        assertThat(antworten.getFirst().statusCode()).isEqualTo(502);
        assertThat(eigeneId).isNotBlank();
        assertThatThrownBy(() -> fehlerdienst.benutzerLesen(MANDANT, eigeneId)).isInstanceOf(FachFehler.class);
        try (Connection verbindung = datenbank.get().getConnection();
             PreparedStatement abfrage = verbindung.prepareStatement(
                     "select details->>'phase' from m_" + SCHEMA + ".zugriff_protokoll "
                             + "where details->>'ziel' = ? and details->>'vorgang' = 'ANLEGEN'")) {
            abfrage.setString(1, eigeneId);
            try (ResultSet zeile = abfrage.executeQuery()) {
                assertThat(zeile.next()).isTrue();
                assertThat(zeile.getString(1)).isEqualTo("FEHLGESCHLAGEN");
            }
        }
    }

    @Wenn("Verwaltungsanfragen mit und ohne Anmeldung und JSON gestellt werden")
    public void sicherheitsanfragen() {
        antworten.add(anfrage().get(PFAD + "/" + eigeneId));
        antworten.add(anfrage().contentType("text/plain").body("x").post(PFAD + "/" + eigeneId + "/einladung"));
        antworten.add(given().contentType("application/json").post(PFAD + "/" + eigeneId + "/einladung"));
    }

    @Dann("tragen die Antworten No-store und unzulässige Schreibanfragen werden abgewiesen")
    public void sicherheitsantworten() {
        assertThat(antworten).extracting(Response::statusCode).containsExactly(200, 415, 401);
        for (Response antwort : antworten) {
            assertThat(antwort.header("Cache-Control")).contains("no-store");
        }
    }

    @Angenommen("die synthetischen Verwaltungslogs werden aufgezeichnet")
    public void logsAufzeichnen() {
        log = LogMitschnitt.starten();
    }

    @Dann("enthält das Log weder den Namen noch die E-Mail-Adresse")
    public void logsOhnePersonendaten() {
        assertThat(antworten.getFirst().statusCode()).isEqualTo(201);
        assertThat(log.volltext()).doesNotContain(name, name + "@example.invalid");
    }

    @Angenommen("im Mandanten gibt es zwei aktive synthetische Inhaber")
    public void zweiAktiveInhaber() {
        for (int i = 0; i < 2; i++) {
            String inhaberName = name + "." + i;
            String id = identitaet.anlegen(MANDANT, new IdentitaetsDienst.Neuanlage(inhaberName,
                    inhaberName + "@example.invalid", "Test", "Person"), Rolle.INHABER);
            benutzer.add(Map.entry(MANDANT, id));
            identitaet.einladen(MANDANT, id, Set.of(), Duration.ofHours(1));
        }
    }

    @Wenn("der Inhaber einen weiteren Inhaber anlegt")
    public void inhaberAnlegen() {
        anlegenUeberApi("inhaber");
    }

    @Dann("ist die Inhaber-Rolle nicht vergeben und ein offener Antrag erfasst")
    public void antragStattRolle() throws SQLException {
        assertThat(antworten.getFirst().statusCode()).isEqualTo(201);
        assertThat(identitaet.benutzerLesen(MANDANT, eigeneId).rollen()).isEmpty();
        String antragId = antworten.getFirst().path("antragId");
        assertThat(antragId).isNotBlank();
        try (Connection verbindung = datenbank.get().getConnection();
             PreparedStatement abfrage = verbindung.prepareStatement(
                     "select art, rolle, status, ziel from m_" + SCHEMA + ".verwaltung_antrag where id = ?")) {
            abfrage.setObject(1, UUID.fromString(antragId));
            try (ResultSet zeile = abfrage.executeQuery()) {
                assertThat(zeile.next()).isTrue();
                assertThat(zeile.getString(1)).isEqualTo("INHABER_ROLLE");
                assertThat(zeile.getString(2)).isEqualTo("inhaber");
                assertThat(zeile.getString(3)).isEqualTo("OFFEN");
                assertThat(zeile.getString(4)).isEqualTo(eigeneId);
            }
        }
    }

    @Wenn("der Inhaber die Einladung des neuen Inhabers erneut sendet")
    public void inhaberErneutEinladen() {
        antworten.add(anfrage().contentType("application/json").post(PFAD + "/" + eigeneId + "/einladung"));
    }

    @Dann("wird die Einladung ohne Rollenvergabe erneut gesendet")
    public void erneutEingeladenOhneRolle() {
        assertThat(antworten.getLast().statusCode()).isEqualTo(204);
        assertThat(identitaet.benutzerLesen(MANDANT, eigeneId).rollen()).isEmpty();
    }

    @Angenommen("ein Mitarbeiterkonto, ein API-Token und ein Webseiten-Schlüssel bestehen")
    public void verwaltungszugaengeAnlegen() {
        verwaltungsSchluessel.add(apiTokens.erstellen("urinet", "Probe Verwaltung", "generator").token());
        verwaltungsSchluessel.add(webseitenSchluessel.erstellen("urinet", "probe", "lesen", "oeffentlich", false)
                .token());
    }

    @Wenn("alle drei Zugänge die Benutzerliste abrufen")
    public void verwaltungszugaengeAbrufen() {
        antworten.clear();
        antworten.add(given().auth().oauth2(Testanmeldung.token("mitarbeiter-urinet")).get(PFAD));
        antworten.add(given().auth().oauth2(verwaltungsSchluessel.get(0).text()).get(PFAD));
        antworten.add(given().auth().oauth2(verwaltungsSchluessel.get(1).text()).get(PFAD));
    }

    @Dann("werden alle drei mit «NICHT_ERLAUBT» abgewiesen")
    public void verwaltungszugaengeAbgewiesen() {
        assertThat(antworten).hasSize(3);
        for (Response antwort : antworten) {
            assertThat(antwort.statusCode()).isEqualTo(403);
            assertThat(antwort.path("fehlercode").toString()).isEqualTo("NICHT_ERLAUBT");
        }
    }

    private String anlegen(String mandant, String benutzername) {
        String id = identitaet.anlegen(mandant, new IdentitaetsDienst.Neuanlage(benutzername,
                benutzername + "@example.invalid", "Test", "Person"), Rolle.MITARBEITER);
        benutzer.add(Map.entry(mandant, id));
        return id;
    }

    private static List<String> tokenRollen(String token) {
        List<String> rollen = new ArrayList<>();
        tokenNutzlast(token).path("realm_access").path("roles").forEach(rolle -> rollen.add(rolle.asText()));
        return rollen;
    }

    private static Instant tokenIat(String token) {
        return Instant.ofEpochSecond(tokenNutzlast(token).path("iat").asLong());
    }

    private static JsonNode tokenNutzlast(String token) {
        try {
            return new ObjectMapper().readTree(Base64.getUrlDecoder().decode(token.split("\\.")[1]));
        } catch (IOException e) {
            throw new IllegalStateException("Testtoken konnte nicht gelesen werden", e);
        }
    }

    private static void wartenAufNaechsteTokenSekunde() {
        try {
            TimeUnit.MILLISECONDS.sleep(1_100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Test unterbrochen", e);
        }
    }

    private io.restassured.specification.RequestSpecification anfrage() {
        return given().auth().oauth2(Testanmeldung.token(akteurName));
    }
}
