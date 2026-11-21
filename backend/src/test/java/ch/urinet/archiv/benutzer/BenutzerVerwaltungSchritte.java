package ch.urinet.archiv.benutzer;

import ch.urinet.archiv.benutzer.adapter.out.stepup.StepUpAusstehend;
import ch.urinet.archiv.benutzer.adapter.out.stub.IdentitaetsDienstStub;
import ch.urinet.archiv.benutzer.application.IdentitaetsDienst;
import ch.urinet.archiv.benutzer.application.StepUp;
import ch.urinet.archiv.gemeinsam.LogMitschnitt;
import ch.urinet.archiv.gemeinsam.Testanmeldung;
import ch.urinet.archiv.gemeinsam.domain.FachFehler;
import ch.urinet.archiv.gemeinsam.domain.Fehlercode;
import ch.urinet.archiv.mandant.application.MandantProvisionierung;
import ch.urinet.archiv.sicherheit.domain.Rolle;
import io.agroal.api.AgroalDataSource;
import io.cucumber.java.After;
import io.cucumber.java.Before;
import io.cucumber.java.de.Angenommen;
import io.cucumber.java.de.Dann;
import io.cucumber.java.de.Wenn;
import io.quarkus.arc.ClientProxy;
import io.quarkus.test.junit.QuarkusMock;
import io.restassured.response.Response;
import jakarta.inject.Inject;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class BenutzerVerwaltungSchritte {
    private static final String PFAD = "/api/v1/benutzer";

    @Inject AgroalDataSource datenbank;
    @Inject MandantProvisionierung provisionierung;
    @Inject IdentitaetsDienst identitaet;
    @Inject StepUp stepUp;

    private final List<Map.Entry<String, String>> benutzer = new ArrayList<>();
    private final List<Response> antworten = new ArrayList<>();
    private StepUp urspruenglichesStepUp;
    private IdentitaetsDienst urspruenglicheIdentitaet;
    private IdentitaetsDienstStub fehlerdienst;
    private LogMitschnitt log;
    private String name;
    private String eigeneId;
    private String fremdeId;

    @Before("@benutzer-verwaltung")
    public void mandantenVorbereiten() throws SQLException {
        for (String code : List.of("urinet", "vhf-gsk")) {
            try (Connection verbindung = datenbank.getConnection();
                 PreparedStatement abfrage = verbindung.prepareStatement(
                         "select 1 from public.mandant where code = ? and aktiv")) {
                abfrage.setString(1, code);
                try (ResultSet zeile = abfrage.executeQuery()) {
                    if (!zeile.next()) {
                        provisionierung.anlegen(code, null, null);
                    }
                }
            }
        }
        name = "probe." + UUID.randomUUID().toString().substring(0, 8);
    }

    @After("@benutzer-verwaltung")
    public void aufraeumen() {
        if (log != null) {
            log.close();
        }
        if (urspruenglichesStepUp != null) {
            QuarkusMock.installMockForType(urspruenglichesStepUp, StepUp.class);
        }
        if (urspruenglicheIdentitaet != null) {
            QuarkusMock.installMockForType(urspruenglicheIdentitaet, IdentitaetsDienst.class);
        }
        for (var eintrag : benutzer) {
            identitaet.loeschen(eintrag.getKey(), eintrag.getValue());
        }
    }

    @Angenommen("in zwei Mandanten gibt es je einen synthetischen Benutzer")
    public void benutzerInZweiMandanten() {
        eigeneId = anlegen("urinet", name);
        fremdeId = anlegen("vhf-gsk", "fremd." + name);
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
        assertThat(antworten.get(3).statusCode()).isEqualTo(404);
        assertThat(antworten.get(4).statusCode()).isEqualTo(404);
        assertThat(antworten.get(3).asString()).doesNotContain("fremd." + name);
    }

    @Angenommen("die frische Anmeldung ist für den Test bestätigt")
    public void stepUpBestaetigen() {
        urspruenglichesStepUp = ClientProxy.unwrap(stepUp);
        QuarkusMock.installMockForType(new StepUpAusstehend() {
            @Override
            public void pruefen() { }
        }, StepUp.class);
    }

    @Wenn("der Inhaber einen synthetischen Mitarbeiter anlegt")
    public void mitarbeiterAnlegen() {
        anlegenUeberApi("mitarbeiter");
    }

    private void anlegenUeberApi(String rolle) {
        Response antwort = anfrage().contentType("application/json").body(Map.of(
                "benutzername", name, "email", name + "@example.invalid",
                "vorname", "Test", "nachname", "Person", "rolle", rolle)).post(PFAD);
        antworten.add(antwort);
        if (antwort.statusCode() == 201) {
            eigeneId = antwort.path("id");
            benutzer.add(Map.entry("urinet", eigeneId));
        }
    }

    @Dann("ist der Mitarbeiter mit Einrichtungspflichten im Mandanten angelegt und protokolliert")
    public void angelegtUndProtokolliert() throws SQLException {
        assertThat(antworten.getFirst().statusCode()).isEqualTo(201);
        var ziel = identitaet.benutzerLesen("urinet", eigeneId);
        assertThat(ziel.rollen()).containsExactly(Rolle.MITARBEITER);
        assertThat(ziel.aktionen()).containsExactlyInAnyOrder(
                IdentitaetsDienst.ErforderlicheAktion.VERIFY_EMAIL,
                IdentitaetsDienst.ErforderlicheAktion.UPDATE_PASSWORD,
                IdentitaetsDienst.ErforderlicheAktion.CONFIGURE_TOTP);
        try (Connection verbindung = datenbank.getConnection();
             PreparedStatement abfrage = verbindung.prepareStatement(
                     "select details::text from m_urinet.zugriff_protokoll "
                             + "where details->>'ziel' = ? and details->>'phase' = 'ABGESCHLOSSEN' "
                             + "and details->>'vorgang' = 'ANLEGEN'")) {
            abfrage.setString(1, eigeneId);
            try (ResultSet zeile = abfrage.executeQuery()) {
                assertThat(zeile.next()).isTrue();
                assertThat(zeile.getString(1)).doesNotContain(name, "@example.invalid");
            }
            try (PreparedStatement verzeichnis = verbindung.prepareStatement(
                    "select name, rolle, anlass from m_urinet.benutzer_verzeichnis where benutzer = ?")) {
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
        eigeneId = anlegen("urinet", name);
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

    @Angenommen("der Mandant hat {int} deaktivierte synthetische Benutzer")
    public void deaktivierteBenutzer(int anzahl) {
        for (int i = 0; i < anzahl; i++) {
            String id = anlegen("urinet", name + "." + i);
            identitaet.aktivieren("urinet", id, false);
        }
    }

    @Dann("wird die Anlage wegen der Höchstzahl abgewiesen")
    public void hoechstzahl() {
        assertThat(antworten.getFirst().statusCode()).isEqualTo(409);
        assertThat(antworten.getFirst().jsonPath().<String>get("fehlercode")).isEqualTo("STATUS_KONFLIKT");
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
        assertThatThrownBy(() -> fehlerdienst.benutzerLesen("urinet", eigeneId)).isInstanceOf(FachFehler.class);
        try (Connection verbindung = datenbank.getConnection();
             PreparedStatement abfrage = verbindung.prepareStatement(
                     "select details->>'phase' from m_urinet.zugriff_protokoll "
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
            String id = identitaet.anlegen("urinet", new IdentitaetsDienst.Neuanlage(inhaberName,
                    inhaberName + "@example.invalid", "Test", "Person"), Rolle.INHABER);
            benutzer.add(Map.entry("urinet", id));
            identitaet.einladen("urinet", id, Set.of(), Duration.ofHours(1));
        }
    }

    @Wenn("der Inhaber einen weiteren Inhaber anlegt")
    public void inhaberAnlegen() {
        anlegenUeberApi("inhaber");
    }

    @Dann("ist die Inhaber-Rolle nicht vergeben und ein offener Antrag erfasst")
    public void antragStattRolle() throws SQLException {
        assertThat(antworten.getFirst().statusCode()).isEqualTo(201);
        assertThat(identitaet.benutzerLesen("urinet", eigeneId).rollen()).isEmpty();
        String antragId = antworten.getFirst().path("antragId");
        assertThat(antragId).isNotBlank();
        try (Connection verbindung = datenbank.getConnection();
             PreparedStatement abfrage = verbindung.prepareStatement(
                     "select art, rolle, status, ziel from m_urinet.verwaltung_antrag where id = ?")) {
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

    private String anlegen(String mandant, String benutzername) {
        String id = identitaet.anlegen(mandant, new IdentitaetsDienst.Neuanlage(benutzername,
                benutzername + "@example.invalid", "Test", "Person"), Rolle.MITARBEITER);
        benutzer.add(Map.entry(mandant, id));
        return id;
    }

    private static io.restassured.specification.RequestSpecification anfrage() {
        return given().auth().oauth2(Testanmeldung.token("inhaber-urinet"));
    }
}
