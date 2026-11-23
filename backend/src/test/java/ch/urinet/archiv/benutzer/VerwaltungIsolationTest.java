package ch.urinet.archiv.benutzer;

import ch.urinet.archiv.benutzer.adapter.out.stepup.StepUpMitAuthTime;
import ch.urinet.archiv.benutzer.adapter.out.stub.IdentitaetsDienstStub;
import ch.urinet.archiv.benutzer.application.IdentitaetsDienst;
import ch.urinet.archiv.benutzer.application.StepUp;
import ch.urinet.archiv.gemeinsam.Testanmeldung;
import ch.urinet.archiv.mandant.application.MandantProvisionierung;
import ch.urinet.archiv.sicherheit.domain.Rolle;
import io.agroal.api.AgroalDataSource;
import io.quarkus.arc.ClientProxy;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

abstract class VerwaltungIsolationIT {
    private static final String BENUTZER = "/api/v1/benutzer";

    private record Aufruf(String methode, String pfad, String body) { }

    private static final List<Aufruf> ZIELAUFRUFE = List.of(
            new Aufruf("GET", "", null),
            new Aufruf("GET", "/rechte", null),
            new Aufruf("GET", "/loeschvorschau", null),
            new Aufruf("PATCH", "", "{\"vorname\":\"Probe\"}"),
            new Aufruf("PUT", "/rolle", "{\"rolle\":\"mitarbeiter\"}"),
            new Aufruf("POST", "/einladung", "{}"),
            new Aufruf("POST", "/zugang-zuruecksetzen", "{}"),
            new Aufruf("DELETE", "/google-verknuepfung", "{}"),
            new Aufruf("POST", "/sperren", "{\"grund\":\"SICHERHEIT\"}"),
            new Aufruf("POST", "/entsperren", "{}"),
            new Aufruf("POST", "/deaktivieren", "{\"grund\":\"SICHERHEIT\"}"),
            new Aufruf("POST", "/reaktivieren", "{}"),
            new Aufruf("POST", "/loeschen", "{\"grund\":\"SICHERHEIT\"}"));

    @Inject MandantProvisionierung provisionierung;
    @Inject IdentitaetsDienst identitaet;
    @Inject AgroalDataSource datenbank;
    @Inject StepUp stepUp;

    private StepUp originalStepUp;
    private String urinetId;
    private String verbandId;
    private String name;

    @BeforeEach
    void vorbereiten() throws Exception {
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
        name = "isolation-" + UUID.randomUUID().toString().substring(0, 8);
        urinetId = anlegen("urinet", name + "-a");
        verbandId = anlegen("vhf-gsk", name + "-b");
        originalStepUp = ClientProxy.unwrap(stepUp);
        QuarkusMock.installMockForType(new StepUpMitAuthTime(null, Duration.ofMinutes(5)) {
            @Override
            public void pruefen() { }
        }, StepUp.class);
    }

    @AfterEach
    void aufraeumen() {
        if (originalStepUp != null) {
            QuarkusMock.installMockForType(originalStepUp, StepUp.class);
        }
        if (urinetId != null) {
            identitaet.loeschen("urinet", urinetId);
        }
        if (verbandId != null) {
            identitaet.loeschen("vhf-gsk", verbandId);
        }
    }

    @Test
    void fremdeBenutzerSindBeiJederZielaktionNichtAuffindbar() {
        var a = identitaet.benutzerLesen("urinet", urinetId);
        var b = identitaet.benutzerLesen("vhf-gsk", verbandId);
        for (var zugang : List.of(Map.entry("inhaber-urinet", verbandId),
                Map.entry("inhaber-vhf-gsk", urinetId))) {
            String token = Testanmeldung.token(zugang.getKey());
            for (Aufruf aufruf : ZIELAUFRUFE) {
                Response antwort = senden(token, BENUTZER + "/" + zugang.getValue(), aufruf);
                assertThat(antwort.statusCode()).as("%s %s für %s",
                        aufruf.methode(), aufruf.pfad(), zugang.getKey()).isEqualTo(404);
                assertThat(antwort.<String>path("fehlercode")).isEqualTo("NICHT_GEFUNDEN");
                assertThat(antwort.asString()).doesNotContain(name);
            }
        }
        assertThat(identitaet.benutzerLesen("urinet", urinetId)).isEqualTo(a);
        assertThat(identitaet.benutzerLesen("vhf-gsk", verbandId)).isEqualTo(b);
    }

    @Test
    void sucheUndMandantenangabenKoennenDenTokenmandantenNichtUeberschreiben() {
        String token = Testanmeldung.token("inhaber-urinet");
        Response liste = given().auth().oauth2(token).header("X-Mandant", "vhf-gsk")
                .queryParam("mandant", "vhf-gsk").queryParam("q", name).get(BENUTZER);
        assertThat(liste.statusCode()).isEqualTo(200);
        assertThat(liste.jsonPath().<String>getList("id")).containsExactly(urinetId);
        assertThat(liste.asString()).doesNotContain(verbandId, name + "-b");
        Response fremd = given().auth().oauth2(token).header("X-Mandant", "vhf-gsk")
                .queryParam("mandant", "vhf-gsk").get(BENUTZER + "/" + verbandId);
        assertThat(fremd.statusCode()).isEqualTo(404);
        long vorA = identitaet.benutzerZaehlen("urinet");
        long vorB = identitaet.benutzerZaehlen("vhf-gsk");
        Response anlage = given().auth().oauth2(token).contentType("application/json")
                .body(Map.of("benutzername", name + "-c", "email", name + "-c@example.invalid",
                        "vorname", "Probe", "nachname", "Person", "rolle", "mitarbeiter", "mandant", "vhf-gsk"))
                .post(BENUTZER);
        assertThat(anlage.statusCode()).isEqualTo(400);
        assertThat(identitaet.benutzerZaehlen("urinet")).isEqualTo(vorA);
        assertThat(identitaet.benutzerZaehlen("vhf-gsk")).isEqualTo(vorB);
    }

    @Test
    void gleichzeitigeRollenwechselEntfernenNichtBeideInhaber() throws Exception {
        CountDownLatch begonnen = new CountDownLatch(1);
        CountDownLatch fortsetzen = new CountDownLatch(1);
        IdentitaetsDienst original = ClientProxy.unwrap(identitaet);
        IdentitaetsDienstStub dienst = new IdentitaetsDienstStub() {
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
                super.rolleSetzen(mandant, id, rolle);
            }
        };
        String erster = dienst.anlegen("urinet", person(name + "-inhaber-a"), Rolle.INHABER);
        String zweiter = dienst.anlegen("urinet", person(name + "-inhaber-b"), Rolle.INHABER);
        dienst.einladen("urinet", erster, Set.of(), Duration.ofHours(1));
        dienst.einladen("urinet", zweiter, Set.of(), Duration.ofHours(1));
        QuarkusMock.installMockForType(dienst, IdentitaetsDienst.class);
        try (var executor = Executors.newSingleThreadExecutor()) {
            String token = Testanmeldung.token("inhaber-urinet");
            String ersterPfad = BENUTZER + "/" + erster;
            String zweiterPfad = BENUTZER + "/" + zweiter;
            String erstesEtag = given().auth().oauth2(token).get(ersterPfad)
                    .then().statusCode(200).extract().header("ETag");
            String zweitesEtag = given().auth().oauth2(token).get(zweiterPfad)
                    .then().statusCode(200).extract().header("ETag");
            CompletableFuture<Response> laufend = CompletableFuture.supplyAsync(
                    () -> rolleAendern(token, ersterPfad, erstesEtag), executor);
            Response gleichzeitig;
            try {
                assertThat(begonnen.await(10, TimeUnit.SECONDS)).isTrue();
                gleichzeitig = rolleAendern(token, zweiterPfad, zweitesEtag);
            } finally {
                fortsetzen.countDown();
            }

            assertThat(gleichzeitig.statusCode()).isEqualTo(409);
            assertThat(gleichzeitig.<String>path("fehlercode")).isEqualTo("STATUS_KONFLIKT");
            assertThat(laufend.get(20, TimeUnit.SECONDS).statusCode()).isEqualTo(204);
            Response letzter = rolleAendern(token, zweiterPfad, zweitesEtag);
            assertThat(letzter.statusCode()).isEqualTo(409);
            assertThat(letzter.<String>path("fehlercode")).isEqualTo("LETZTER_INHABER");
            assertThat(dienst.inhaberZaehlen("urinet")).isEqualTo(1);
        } finally {
            fortsetzen.countDown();
            QuarkusMock.installMockForType(original, IdentitaetsDienst.class);
        }
    }

    @Test
    void alleNichtInhaberWerdenAnVerwaltungsendpunktenAbgewiesen() {
        List<Aufruf> weitere = List.of(
                new Aufruf("GET", "/api/v1/rollen", null),
                new Aufruf("GET", "/api/v1/mandant/einstellungen", null),
                new Aufruf("PATCH", "/api/v1/mandant/einstellungen", "{}"),
                new Aufruf("POST", "/api/v1/mandant/einstellungen/vorschau", "{}"),
                new Aufruf("POST", "/api/v1/mandant/einstellungen/zuruecksetzen", "{}"),
                new Aufruf("GET", BENUTZER, null),
                new Aufruf("POST", BENUTZER, "{}"),
                new Aufruf("GET", BENUTZER + "/antraege", null),
                new Aufruf("POST", BENUTZER + "/antraege/" + UUID.randomUUID() + "/bestaetigen", "{}"),
                new Aufruf("POST", BENUTZER + "/antraege/" + UUID.randomUUID() + "/ablehnen", "{}"),
                new Aufruf("POST", BENUTZER + "/antraege/" + UUID.randomUUID() + "/zurueckziehen", "{}"));
        for (String benutzer : List.of("mitarbeiter-urinet", "treuhaender-urinet", "geschaeftsstelle-vhf-gsk")) {
            String token = Testanmeldung.token(benutzer);
            String ziel = benutzer.endsWith("vhf-gsk") ? verbandId : urinetId;
            for (Aufruf aufruf : ZIELAUFRUFE) {
                verweigert(senden(token, BENUTZER + "/" + ziel, aufruf), benutzer, aufruf);
            }
            for (Aufruf aufruf : weitere) {
                verweigert(senden(token, "", aufruf), benutzer, aufruf);
            }
        }
    }

    @Test
    void fremdeAntraegeUndProtokolleBleibenImEigenenMandanten() throws Exception {
        UUID antragA = UUID.randomUUID();
        UUID antragB = UUID.randomUUID();
        String markerA = UUID.randomUUID().toString();
        String markerB = UUID.randomUUID().toString();
        try {
            antragAnlegen("m_urinet", antragA, urinetId);
            antragAnlegen("m_vhf_gsk", antragB, verbandId);
            protokollAnlegen("m_urinet", markerA);
            protokollAnlegen("m_vhf_gsk", markerB);
            for (var fall : List.of(
                    new Testfall("inhaber-urinet", antragA, antragB, markerA, markerB),
                    new Testfall("inhaber-vhf-gsk", antragB, antragA, markerB, markerA))) {
                String token = Testanmeldung.token(fall.benutzer());
                String eigene = given().auth().oauth2(token).get(BENUTZER + "/antraege")
                        .then().statusCode(200).extract().asString();
                assertThat(eigene).contains(fall.eigenerAntrag().toString())
                        .doesNotContain(fall.fremderAntrag().toString());
                for (String entscheid : List.of("bestaetigen", "ablehnen", "zurueckziehen")) {
                    given().auth().oauth2(token).contentType("application/json").body("{}")
                            .post(BENUTZER + "/antraege/" + fall.fremderAntrag() + "/" + entscheid)
                            .then().statusCode(404);
                }
                assertThat(protokoll(token, fall.eigenerMarker())).contains(fall.eigenerMarker());
                assertThat(protokoll(token, fall.fremderMarker())).doesNotContain(fall.fremderMarker());
            }
        } finally {
            antragEntfernen("m_urinet", antragA);
            antragEntfernen("m_vhf_gsk", antragB);
        }
    }

    private record Testfall(String benutzer, UUID eigenerAntrag, UUID fremderAntrag,
                            String eigenerMarker, String fremderMarker) { }

    private void antragAnlegen(String schema, UUID id, String ziel) throws Exception {
        try (Connection verbindung = datenbank.getConnection();
             PreparedStatement einfuegen = verbindung.prepareStatement(
                     "insert into " + schema + ".verwaltung_antrag "
                             + "(id, art, ziel, rolle, beantragt_von, beantragt_am, gueltig_bis, status) "
                             + "values (?, 'INHABER_ROLLE', ?, 'inhaber', ?, now(), now() + interval '1 hour', 'OFFEN')")) {
            einfuegen.setObject(1, id);
            einfuegen.setString(2, ziel);
            einfuegen.setString(3, UUID.randomUUID().toString());
            einfuegen.executeUpdate();
        }
    }

    private void antragEntfernen(String schema, UUID id) throws Exception {
        try (Connection verbindung = datenbank.getConnection();
             PreparedStatement loeschen = verbindung.prepareStatement(
                     "delete from " + schema + ".verwaltung_antrag where id = ?")) {
            loeschen.setObject(1, id);
            loeschen.executeUpdate();
        }
    }

    private void protokollAnlegen(String schema, String marker) throws Exception {
        try (Connection verbindung = datenbank.getConnection();
             PreparedStatement einfuegen = verbindung.prepareStatement(
                     "insert into " + schema + ".zugriff_protokoll (benutzer, rolle, aktion) "
                             + "values (?, 'INHABER', 'BENUTZER_AENDERN')")) {
            einfuegen.setString(1, marker);
            einfuegen.executeUpdate();
        }
    }

    private static String protokoll(String token, String marker) {
        return given().auth().oauth2(token).queryParam("benutzer", marker).get("/api/v1/protokoll")
                .then().statusCode(200).extract().asString();
    }

    private String anlegen(String mandant, String benutzername) {
        return identitaet.anlegen(mandant, person(benutzername), Rolle.MITARBEITER);
    }

    private static IdentitaetsDienst.Neuanlage person(String benutzername) {
        return new IdentitaetsDienst.Neuanlage(benutzername, benutzername + "@example.invalid", "Probe", "Person");
    }

    private static Response rolleAendern(String token, String pfad, String etag) {
        return given().auth().oauth2(token).contentType("application/json").header("If-Match", etag)
                .body(Map.of("rolle", "mitarbeiter")).put(pfad + "/rolle");
    }

    private static Response senden(String token, String basis, Aufruf aufruf) {
        var anfrage = given().auth().oauth2(token).header("If-Match", "\"" + "0".repeat(64) + "\"")
                .contentType("application/json");
        if (aufruf.body() != null) {
            anfrage.body(aufruf.body());
        }
        return anfrage.request(aufruf.methode(), basis + aufruf.pfad());
    }

    private static void verweigert(Response antwort, String benutzer, Aufruf aufruf) {
        assertThat(antwort.statusCode()).as("%s %s für %s",
                aufruf.methode(), aufruf.pfad(), benutzer).isEqualTo(403);
        assertThat(antwort.<String>path("fehlercode")).isEqualTo("NICHT_ERLAUBT");
    }
}

@QuarkusTest
class VerwaltungIsolationTest extends VerwaltungIsolationIT {
}
