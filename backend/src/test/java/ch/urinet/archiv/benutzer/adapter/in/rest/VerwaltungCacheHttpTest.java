package ch.urinet.archiv.benutzer.adapter.in.rest;

import ch.urinet.archiv.benutzer.application.IdentitaetsDienst;
import ch.urinet.archiv.benutzer.application.StepUp;
import ch.urinet.archiv.benutzer.adapter.out.stepup.StepUpAusstehend;
import ch.urinet.archiv.benutzer.adapter.out.stub.IdentitaetsDienstStub;
import ch.urinet.archiv.gemeinsam.domain.FachFehler;
import ch.urinet.archiv.gemeinsam.domain.Fehlercode;
import ch.urinet.archiv.gemeinsam.Testanmeldung;
import ch.urinet.archiv.mandant.application.MandantProvisionierung;
import ch.urinet.archiv.sicherheit.domain.Aktion;
import ch.urinet.archiv.sicherheit.domain.Rechtematrix;
import ch.urinet.archiv.sicherheit.domain.Rolle;
import io.agroal.api.AgroalDataSource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.common.mapper.TypeRef;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

@QuarkusTest
@Tag("start-4")
@TestProfile(VerwaltungCacheHttpTest.Isoliert.class)
class VerwaltungCacheHttpTest {

    public static class Isoliert implements QuarkusTestProfile {
        private static final String SUFFIX = UUID.randomUUID().toString().replace("-", "").substring(0, 8);

        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("quarkus.datasource.devservices.db-name", "archiv_verwaltung_" + SUFFIX);
        }
    }

    @Inject
    AgroalDataSource datenbank;

    @Inject
    MandantProvisionierung provisionierung;

    @Inject
    IdentitaetsDienst identitaet;

    @BeforeEach
    void mandantAnlegen() throws SQLException {
        for (String code : List.of("urinet", "vhf-gsk")) {
            try (Connection verbindung = datenbank.getConnection();
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
    }

    @Test
    void auchAnmeldungUndRechtefehlerTragenNoStore() {
        String pfad = "/api/v1/benutzer/cache-probe";
        given().when().get(pfad).then().statusCode(401).header("Cache-Control", containsString("no-store"));
        given().auth().oauth2(Testanmeldung.token("mitarbeiter-urinet")).when().get(pfad).then()
                .statusCode(403).header("Cache-Control", containsString("no-store"));
        given().auth().oauth2(Testanmeldung.token("inhaber-urinet")).when().get(pfad).then()
                .statusCode(200).header("Cache-Control", containsString("no-store"));
    }

    @Test
    void rollenListeZeigtNurMandantenrollenMitRechtenAusDerMatrix() throws SQLException {
        String[] bisher = rollenDesVerbands();
        rollenDesVerbandsSetzen(new String[]{"geschaeftsstelle"});
        try {
            List<RollenResource.RolleDto> verband = given().auth().oauth2(Testanmeldung.token("inhaber-vhf-gsk"))
                    .when().get("/api/v1/rollen").then().statusCode(200)
                    .header("Cache-Control", containsString("no-store")).extract().as(new TypeRef<>() { });
            assertThat(verband).extracting(RollenResource.RolleDto::code).containsExactly("geschaeftsstelle");
            rechtePruefen(verband);
        } finally {
            rollenDesVerbandsSetzen(bisher);
        }
        List<RollenResource.RolleDto> rollen = given().auth().oauth2(Testanmeldung.token("inhaber-urinet"))
                .when().get("/api/v1/rollen").then().statusCode(200)
                .header("Cache-Control", containsString("no-store")).extract().as(new TypeRef<>() { });
        assertThat(rollen).extracting(RollenResource.RolleDto::code)
                .containsExactlyInAnyOrder("inhaber", "mitarbeiter", "treuhaender");
        rechtePruefen(rollen);
        given().auth().oauth2(Testanmeldung.token("mitarbeiter-urinet")).when().get("/api/v1/rollen").then()
                .statusCode(403).header("Cache-Control", containsString("no-store"));
    }

    @Test
    void benutzerSindNurImEigenenMandantenBerechtigtUndSeitenweiseSichtbar() throws SQLException {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String name = "test." + suffix;
        String fremd = "fremd." + suffix;
        String eigeneId = identitaet.anlegen("urinet",
                new IdentitaetsDienst.Neuanlage(name, name + "@example.invalid", "Test", "Person"),
                Rolle.MITARBEITER);
        String fremdeId = identitaet.anlegen("vhf-gsk",
                new IdentitaetsDienst.Neuanlage(fremd, fremd + "@example.invalid", "Fremd", "Person"),
                Rolle.MITARBEITER);
        try {
            String pfad = "/api/v1/benutzer";
            given().auth().oauth2(Testanmeldung.token("inhaber-urinet")).queryParam("q", suffix)
                    .queryParam("groesse", 1).when().get(pfad).then().statusCode(200)
                    .header("Cache-Control", containsString("no-store"))
                    .body("$", hasSize(1)).body("[0].id", is(eigeneId))
                    .body("[0].email", is(name + "@example.invalid"))
                    .body("[0].status", is("EINGELADEN"));
            given().auth().oauth2(Testanmeldung.token("inhaber-urinet")).queryParam("q", suffix)
                    .queryParam("groesse", 1).queryParam("seite", 1).when().get(pfad).then()
                    .statusCode(200).body("$", hasSize(0));
            given().auth().oauth2(Testanmeldung.token("inhaber-urinet"))
                    .when().get(pfad + "/" + eigeneId).then().statusCode(200)
                    .header("ETag", containsString("\"")).body("vorname", is("Test"))
                    .body("nachname", is("Person"));
            given().auth().oauth2(Testanmeldung.token("inhaber-urinet"))
                    .when().get(pfad + "/" + fremdeId).then().statusCode(404)
                    .body(not(containsString(fremd)));
            given().auth().oauth2(Testanmeldung.token("inhaber-urinet"))
                    .when().get(pfad + "/" + UUID.randomUUID()).then().statusCode(404);
            given().auth().oauth2(Testanmeldung.token("mitarbeiter-urinet"))
                    .when().get(pfad + "/" + eigeneId).then().statusCode(403)
                    .body(not(containsString(name)));
            given().when().get(pfad).then().statusCode(401).body(not(containsString(name)));
            given().auth().oauth2(Testanmeldung.token("inhaber-urinet"))
                    .queryParam("groesse", 51).when().get(pfad).then().statusCode(400);
            given().auth().oauth2(Testanmeldung.token("mitarbeiter-urinet"))
                    .contentType("application/json")
                    .when().post(pfad + "/" + eigeneId + "/einladung").then().statusCode(403);
            given().auth().oauth2(Testanmeldung.token("inhaber-urinet"))
                    .contentType("application/json")
                    .when().post(pfad + "/" + fremdeId + "/einladung").then().statusCode(404);
            given().auth().oauth2(Testanmeldung.token("inhaber-urinet")).contentType("text/plain")
                    .when().post(pfad + "/" + eigeneId + "/einladung").then().statusCode(415)
                    .body("fehlercode", is("MEDIENTYP_FALSCH"));
            given().auth().oauth2(Testanmeldung.token("inhaber-urinet")).contentType("application/json")
                    .body("{\"mandant\":\"vhf-gsk\"}").when().post(pfad + "/" + eigeneId + "/einladung").then()
                    .statusCode(400);
            given().auth().oauth2(Testanmeldung.token("inhaber-urinet")).contentType("application/json")
                    .body(" ".repeat(8193)).when().post(pfad + "/" + eigeneId + "/einladung").then()
                    .statusCode(413);
            given().auth().oauth2(Testanmeldung.token("inhaber-urinet"))
                    .contentType("application/json")
                    .when().post(pfad + "/" + eigeneId + "/einladung").then().statusCode(204)
                    .header("Cache-Control", containsString("no-store"));
            try (Connection verbindung = datenbank.getConnection();
                 PreparedStatement abfrage = verbindung.prepareStatement(
                         "select details::text from m_urinet.zugriff_protokoll "
                                 + "where details->>'ziel' = ? and details->>'vorgang' = 'EINLADUNG_ERNEUT' "
                                 + "order by id")) {
                abfrage.setString(1, eigeneId);
                try (ResultSet zeilen = abfrage.executeQuery()) {
                    List<String> details = new java.util.ArrayList<>();
                    while (zeilen.next()) {
                        details.add(zeilen.getString(1));
                    }
                    assertThat(details).hasSize(2).anyMatch(d -> d.contains("ANGEFRAGT"))
                            .anyMatch(d -> d.contains("ABGESCHLOSSEN"))
                            .allMatch(d -> !d.contains(name) && !d.contains("@example.invalid"));
                }
            }
            for (int i = 0; i < 2; i++) {
                given().auth().oauth2(Testanmeldung.token("inhaber-urinet")).contentType("application/json")
                        .when().post(pfad + "/" + eigeneId + "/einladung").then().statusCode(204);
            }
            given().auth().oauth2(Testanmeldung.token("inhaber-urinet")).contentType("application/json")
                    .when().post(pfad + "/" + eigeneId + "/einladung").then().statusCode(429);
            given().auth().oauth2(Testanmeldung.token("inhaber-urinet")).contentType("text/plain")
                    .body("x").when().post(pfad + "/" + eigeneId + "/einladung").then().statusCode(415);
        } finally {
            identitaet.loeschen("urinet", eigeneId);
            identitaet.loeschen("vhf-gsk", fremdeId);
        }
    }

    @Test
    void anlegenVerlangtStepUp() {
        given().auth().oauth2(Testanmeldung.token("inhaber-urinet")).contentType("application/json")
                .body(anlage("test." + UUID.randomUUID().toString().substring(0, 8), "mitarbeiter"))
                .when().post("/api/v1/benutzer").then()
                .statusCode(401).body("fehlercode", is("STEP_UP_NOETIG"));
    }

    @Test
    void anlegenPrueftRollenNamenssperreUndVierAugen() throws SQLException {
        QuarkusMock.installMockForType(new StepUpAusstehend() {
            @Override
            public void pruefen() { }
        }, StepUp.class);
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String name = "test." + suffix;
        String inhaberName = "owner." + suffix;
        String erster = null;
        String zweiter = null;
        String neu = null;
        String antragZiel = null;
        String[] bisher = rollenDesVerbands();
        try {
            given().auth().oauth2(Testanmeldung.token("inhaber-urinet")).contentType("application/json")
                    .body(anlage("test", "geschaeftsstelle")).when().post("/api/v1/benutzer").then()
                    .statusCode(403).body("fehlercode", is("ESKALATION_VERBOTEN"));
            given().auth().oauth2(Testanmeldung.token("inhaber-urinet")).contentType("application/json")
                    .body(anlage("x", "mitarbeiter")).when().post("/api/v1/benutzer").then()
                    .statusCode(422).body("fehlercode", is("KONFIGURATION_UNGUELTIG"));
            given().auth().oauth2(Testanmeldung.token("inhaber-urinet")).contentType("application/json")
                    .body("{\"mandant\":\"vhf-gsk\"}").when().post("/api/v1/benutzer").then().statusCode(400);
            given().auth().oauth2(Testanmeldung.token("inhaber-urinet")).contentType("application/json")
                    .body(" ".repeat(8193)).when().post("/api/v1/benutzer").then().statusCode(413);
            neu = given().auth().oauth2(Testanmeldung.token("inhaber-urinet")).contentType("application/json")
                    .body(anlage(name, "mitarbeiter")).when().post("/api/v1/benutzer").then()
                    .statusCode(201).body("antragId", is((String) null)).extract().path("id");
            var angelegt = identitaet.benutzerLesen("urinet", neu);
            assertThat(angelegt.rollen()).containsExactly(Rolle.MITARBEITER);
            assertThat(angelegt.aktionen()).containsExactlyInAnyOrder(
                    IdentitaetsDienst.ErforderlicheAktion.VERIFY_EMAIL,
                    IdentitaetsDienst.ErforderlicheAktion.UPDATE_PASSWORD,
                    IdentitaetsDienst.ErforderlicheAktion.CONFIGURE_TOTP);
            identitaet.loeschen("urinet", neu);
            neu = null;
            rollenDesVerbandsSetzen(new String[]{"mitarbeiter"});
            given().auth().oauth2(Testanmeldung.token("inhaber-vhf-gsk")).contentType("application/json")
                    .body(anlage(name, "mitarbeiter")).when().post("/api/v1/benutzer").then()
                    .statusCode(409).body("fehlercode", is("BENUTZER_EXISTIERT"));
            given().auth().oauth2(Testanmeldung.token("inhaber-urinet")).contentType("application/json")
                    .body(anlage(name, "mitarbeiter")).when().post("/api/v1/benutzer").then()
                    .statusCode(409).body("fehlercode", is("BENUTZER_EXISTIERT"));

            erster = identitaet.anlegen("urinet", person("a." + suffix), Rolle.INHABER);
            zweiter = identitaet.anlegen("urinet", person("b." + suffix), Rolle.INHABER);
            identitaet.einladen("urinet", erster, Set.of(), Duration.ofHours(1));
            identitaet.einladen("urinet", zweiter, Set.of(), Duration.ofHours(1));
            var antwort = given().auth().oauth2(Testanmeldung.token("inhaber-urinet"))
                    .contentType("application/json").body(anlage(inhaberName, "inhaber"))
                    .when().post("/api/v1/benutzer").then().statusCode(201).extract();
            antragZiel = antwort.path("id");
            assertThat(antwort.<String>path("antragId")).isNotBlank();
            assertThat(identitaet.benutzerLesen("urinet", antragZiel).rollen()).isEmpty();
        } finally {
            rollenDesVerbandsSetzen(bisher);
            if (neu != null) identitaet.loeschen("urinet", neu);
            if (antragZiel != null) identitaet.loeschen("urinet", antragZiel);
            if (erster != null) identitaet.loeschen("urinet", erster);
            if (zweiter != null) identitaet.loeschen("urinet", zweiter);
        }
    }

    @Test
    void hoechstzahlZaehltAuchDeaktivierteBenutzer() {
        QuarkusMock.installMockForType(new StepUpAusstehend() {
            @Override
            public void pruefen() { }
        }, StepUp.class);
        IdentitaetsDienstStub dienst = new IdentitaetsDienstStub();
        for (int i = 0; i < 25; i++) {
            String id = dienst.anlegen("urinet", person("limit." + i), Rolle.MITARBEITER);
            dienst.aktivieren("urinet", id, false);
        }
        QuarkusMock.installMockForType(dienst, IdentitaetsDienst.class);
        given().auth().oauth2(Testanmeldung.token("inhaber-urinet")).contentType("application/json")
                .body(anlage("test.limit", "mitarbeiter")).when().post("/api/v1/benutzer").then()
                .statusCode(409).body("fehlercode", is("STATUS_KONFLIKT"));
    }

    @Test
    void fehlerBeimVersandEntferntBenutzerUndHaeltUnklareSperre() throws SQLException {
        QuarkusMock.installMockForType(new StepUpAusstehend() {
            @Override
            public void pruefen() { }
        }, StepUp.class);
        var fehlerdienst = new IdentitaetsDienstStub() {
            String ziel;

            @Override
            public void einladen(String mandant, String id, Set<ErforderlicheAktion> aktionen, Duration gueltigkeit) {
                ziel = id;
                throw new FachFehler(Fehlercode.IDENTITAETSDIENST_NICHT_ERREICHBAR);
            }
        };
        QuarkusMock.installMockForType(fehlerdienst, IdentitaetsDienst.class);
        String[] bisher = rollenDesVerbands();
        UUID sperre = null;
        try {
            rollenDesVerbandsSetzen(new String[]{"mitarbeiter"});
            String name = "test." + UUID.randomUUID().toString().substring(0, 8);
            given().auth().oauth2(Testanmeldung.token("inhaber-vhf-gsk")).contentType("application/json")
                    .body(anlage(name, "mitarbeiter")).when().post("/api/v1/benutzer").then()
                    .statusCode(502).body("fehlercode", is("IDENTITAETSDIENST_FEHLER"));
            assertThat(fehlerdienst.ziel).isNotBlank();
            assertThatThrownBy(() -> fehlerdienst.benutzerLesen("vhf-gsk", fehlerdienst.ziel))
                    .isInstanceOf(FachFehler.class);
            try (Connection verbindung = datenbank.getConnection();
                 PreparedStatement abfrage = verbindung.prepareStatement(
                         "select vorgang_id, phase from public.verwaltung_vorgang where mandant = ?")) {
                abfrage.setString(1, "vhf-gsk");
                try (ResultSet zeile = abfrage.executeQuery()) {
                    assertThat(zeile.next()).isTrue();
                    sperre = zeile.getObject(1, UUID.class);
                    assertThat(zeile.getString(2)).isEqualTo("UNKLAR");
                }
            }
        } finally {
            rollenDesVerbandsSetzen(bisher);
            if (sperre != null) {
                try (Connection verbindung = datenbank.getConnection();
                     PreparedStatement loeschen = verbindung.prepareStatement(
                             "delete from public.verwaltung_vorgang where mandant = ? and vorgang_id = ?")) {
                    loeschen.setString(1, "vhf-gsk");
                    loeschen.setObject(2, sperre);
                    loeschen.executeUpdate();
                }
            }
        }
    }

    @Test
    void eindeutigAbgewiesenerVersandGibtSperreNachKompensationFrei() throws SQLException {
        QuarkusMock.installMockForType(new StepUpAusstehend() {
            @Override
            public void pruefen() { }
        }, StepUp.class);
        var fehlerdienst = new IdentitaetsDienstStub() {
            String ziel;

            @Override
            public void einladen(String mandant, String id, Set<ErforderlicheAktion> aktionen, Duration gueltigkeit) {
                ziel = id;
                throw new FachFehler(Fehlercode.IDENTITAETSDIENST_FEHLER);
            }
        };
        QuarkusMock.installMockForType(fehlerdienst, IdentitaetsDienst.class);
        String name = "test." + UUID.randomUUID().toString().substring(0, 8);
        given().auth().oauth2(Testanmeldung.token("inhaber-urinet")).contentType("application/json")
                .body(anlage(name, "mitarbeiter")).when().post("/api/v1/benutzer").then()
                .statusCode(502).body("fehlercode", is("IDENTITAETSDIENST_FEHLER"));
        assertThatThrownBy(() -> fehlerdienst.benutzerLesen("urinet", fehlerdienst.ziel))
                .isInstanceOf(FachFehler.class);
        try (Connection verbindung = datenbank.getConnection();
             PreparedStatement abfrage = verbindung.prepareStatement(
                     "select details->>'phase' from m_urinet.zugriff_protokoll "
                             + "where details->>'ziel' = ? and details->>'vorgang' = 'ANLEGEN'")) {
            abfrage.setString(1, fehlerdienst.ziel);
            try (ResultSet zeile = abfrage.executeQuery()) {
                assertThat(zeile.next()).isTrue();
                assertThat(zeile.getString(1)).isEqualTo("FEHLGESCHLAGEN");
            }
            try (PreparedStatement sperre = verbindung.prepareStatement(
                    "select exists (select 1 from public.verwaltung_vorgang where mandant = ?)")) {
                sperre.setString(1, "urinet");
                try (ResultSet zeile = sperre.executeQuery()) {
                    assertThat(zeile.next()).isTrue();
                    assertThat(zeile.getBoolean(1)).isFalse();
                }
            }
        }
    }

    private static IdentitaetsDienst.Neuanlage person(String name) {
        return new IdentitaetsDienst.Neuanlage(name, name + "@example.invalid", "Test", "Person");
    }

    private static java.util.Map<String, String> anlage(String name, String rolle) {
        return java.util.Map.of("benutzername", name, "email", name + "@example.invalid",
                "vorname", "Test", "nachname", "Person", "rolle", rolle);
    }

    private void rechtePruefen(List<RollenResource.RolleDto> rollen) {
        for (RollenResource.RolleDto dto : rollen) {
            Rolle rolle = Rolle.ausRealmRollen(List.of(dto.code())).orElseThrow();
            assertThat(dto.beschreibung()).isNotBlank();
            assertThat(dto.rechte()).extracting(RollenResource.RechtDto::aktion).containsExactly(Aktion.values());
            for (RollenResource.RechtDto recht : dto.rechte()) {
                assertThat(recht.ergebnis()).isEqualTo(Rechtematrix.ergebnis(rolle, recht.aktion()));
                assertThat(recht.fussnoten()).extracting(RollenResource.FussnoteDto::text)
                        .containsExactlyElementsOf(Rechtematrix.fussnoten(rolle, recht.aktion()).stream()
                                .map(f -> f.text()).toList());
                assertThat(recht.fussnoten()).extracting(RollenResource.FussnoteDto::zeichen)
                        .containsExactlyElementsOf(Rechtematrix.fussnoten(rolle, recht.aktion()).stream()
                                .map(f -> f.zeichen()).toList());
            }
        }
    }

    private String[] rollenDesVerbands() throws SQLException {
        try (Connection verbindung = datenbank.getConnection();
             PreparedStatement abfrage = verbindung.prepareStatement(
                     "select verwaltung_rollen from public.mandant where code = 'vhf-gsk'");
             ResultSet ergebnis = abfrage.executeQuery()) {
            if (!ergebnis.next()) {
                throw new IllegalStateException("Testmandant fehlt");
            }
            return (String[]) ergebnis.getArray(1).getArray();
        }
    }

    private void rollenDesVerbandsSetzen(String[] rollen) throws SQLException {
        try (Connection verbindung = datenbank.getConnection();
             PreparedStatement aenderung = verbindung.prepareStatement(
                     "update public.mandant set verwaltung_rollen = ? where code = 'vhf-gsk'")) {
            aenderung.setArray(1, verbindung.createArrayOf("text", rollen));
            if (aenderung.executeUpdate() != 1) {
                throw new IllegalStateException("Testmandant fehlt");
            }
        }
    }
}
