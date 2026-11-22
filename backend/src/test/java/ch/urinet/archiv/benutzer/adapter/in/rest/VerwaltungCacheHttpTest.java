package ch.urinet.archiv.benutzer.adapter.in.rest;

import ch.urinet.archiv.benutzer.application.IdentitaetsDienst;
import ch.urinet.archiv.benutzer.application.BenutzerNamen;
import ch.urinet.archiv.benutzer.application.BenutzerMitteilung;
import ch.urinet.archiv.benutzer.application.StepUp;
import ch.urinet.archiv.benutzer.adapter.out.stepup.StepUpMitAuthTime;
import ch.urinet.archiv.benutzer.adapter.out.stub.IdentitaetsDienstStub;
import ch.urinet.archiv.benutzer.adapter.out.mail.MailBenutzerMitteilung;
import ch.urinet.archiv.benutzer.adapter.out.persistence.NamenssperreBestandMigration;
import ch.urinet.archiv.benutzer.adapter.out.persistence.NamenssperreHash;
import ch.urinet.archiv.sicherheit.application.TokenWiderruf;
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
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.restassured.common.mapper.TypeRef;
import jakarta.inject.Inject;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
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

    @Inject
    TokenWiderruf tokenWiderruf;

    @Inject
    BenutzerNamen namen;

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
    void bestandsmigrationUebernimmtBeideMandantenOhneKlarnamen() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String erster = "probe." + suffix;
        String zweiter = "fremd." + suffix;
        try (Connection verbindung = datenbank.getConnection()) {
            for (var eintrag : Map.of("m_urinet", erster, "m_vhf_gsk", zweiter).entrySet()) {
                try (PreparedStatement einfuegen = verbindung.prepareStatement(
                        "insert into " + eintrag.getKey() + ".benutzer_verzeichnis "
                                + "(benutzer, name, gueltig_von, anlass) values (?, ?, clock_timestamp(), 'AKTION')")) {
                    einfuegen.setString(1, UUID.randomUUID().toString());
                    einfuegen.setString(2, eintrag.getValue().toUpperCase(java.util.Locale.ROOT));
                    einfuegen.executeUpdate();
                }
            }
            var database = DatabaseFactory.getInstance()
                    .findCorrectDatabaseImplementation(new JdbcConnection(verbindung));
            var migration = new NamenssperreBestandMigration();
            migration.execute(database);
            migration.execute(database);
            var hash = NamenssperreHash.ausKonfiguration().orElseThrow();
            try (PreparedStatement suchen = verbindung.prepareStatement(
                    "select count(*) from public.benutzername_sperre where hash in (?, ?)")) {
                suchen.setBytes(1, hash.hash(erster));
                suchen.setBytes(2, hash.hash(zweiter));
                try (ResultSet zeile = suchen.executeQuery()) {
                    assertThat(zeile.next()).isTrue();
                    assertThat(zeile.getInt(1)).isEqualTo(2);
                }
            }
            try (PreparedStatement spalten = verbindung.prepareStatement(
                    "select column_name from information_schema.columns "
                            + "where table_schema = 'public' and table_name = 'benutzername_sperre' "
                            + "order by ordinal_position");
                 ResultSet zeilen = spalten.executeQuery()) {
                assertThat(zeilen.next()).isTrue();
                assertThat(zeilen.getString(1)).isEqualTo("hash");
                assertThat(zeilen.next()).isTrue();
                assertThat(zeilen.getString(1)).isEqualTo("erstellt");
                assertThat(zeilen.next()).isFalse();
            }
        }
    }

    @Test
    void namenssperreWirdMitDerTransaktionZurueckgerollt() throws SQLException {
        String name = "probe." + UUID.randomUUID().toString().substring(0, 8);
        assertThatThrownBy(() -> QuarkusTransaction.requiringNew().run(() -> {
            namen.sperren(name);
            throw new IllegalStateException("synthetischer Fehler");
        })).isInstanceOf(IllegalStateException.class);
        try (Connection verbindung = datenbank.getConnection();
             PreparedStatement abfrage = verbindung.prepareStatement(
                     "select count(*) from public.benutzername_sperre where hash = ?")) {
            abfrage.setBytes(1, NamenssperreHash.ausKonfiguration().orElseThrow().hash(name));
            try (ResultSet zeile = abfrage.executeQuery()) {
                assertThat(zeile.next()).isTrue();
                assertThat(zeile.getInt(1)).isZero();
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
                    .body("nachname", is("Person"))
                    .body("apiTokenVerwaltungspfad", is("/verwaltung/api-tokens"))
                    .body("apiTokenHinweis", containsString("bleiben beim Sperren oder Deaktivieren gültig"));
            RollenResource.RolleDto rechte = given().auth().oauth2(Testanmeldung.token("inhaber-urinet"))
                    .when().get(pfad + "/" + eigeneId + "/rechte").then().statusCode(200)
                    .header("Cache-Control", containsString("no-store")).extract().as(RollenResource.RolleDto.class);
            assertThat(rechte.code()).isEqualTo("mitarbeiter");
            rechtePruefen(List.of(rechte));
            given().auth().oauth2(Testanmeldung.token("inhaber-urinet"))
                    .when().get(pfad + "/" + fremdeId + "/rechte").then().statusCode(404);
            given().auth().oauth2(Testanmeldung.token("mitarbeiter-urinet"))
                    .when().get(pfad + "/" + eigeneId + "/rechte").then().statusCode(403);
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
    void zugangZuruecksetzenPrueftStepUpMandantUndBewirktAlleSchritte() {
        String pfad = "/api/v1/benutzer/";
        String token = Testanmeldung.token("inhaber-urinet");
        given().auth().oauth2(token).contentType("application/json")
                .post(pfad + UUID.randomUUID() + "/zugang-zuruecksetzen")
                .then().statusCode(401).body("fehlercode", is("STEP_UP_NOETIG"));

        QuarkusMock.installMockForType(new StepUpMitAuthTime(null, Duration.ofMinutes(5)) {
            @Override
            public void pruefen() { }
        }, StepUp.class);
        List<String> aufrufe = new java.util.ArrayList<>();
        IdentitaetsDienstStub dienst = new IdentitaetsDienstStub() {
            @Override
            public synchronized void aktionenHinzufuegen(String mandant, String id, Set<ErforderlicheAktion> aktionen) {
                aufrufe.add("aktionen");
                super.aktionenHinzufuegen(mandant, id, aktionen);
            }

            @Override
            public synchronized void otpEntfernen(String mandant, String id) {
                aufrufe.add("otp");
                super.otpEntfernen(mandant, id);
            }

            @Override
            public synchronized void sitzungenBeenden(String mandant, String id) {
                aufrufe.add("sitzungen");
                super.sitzungenBeenden(mandant, id);
            }

            @Override
            public synchronized void einladen(String mandant, String id, Set<ErforderlicheAktion> aktionen,
                                               Duration gueltigkeit) {
                aufrufe.add("einladung");
                super.einladen(mandant, id, aktionen, gueltigkeit);
            }
        };
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String ziel = dienst.anlegen("urinet", person("reset." + suffix), Rolle.MITARBEITER);
        String fremd = dienst.anlegen("vhf-gsk", person("fremd." + suffix), Rolle.MITARBEITER);
        QuarkusMock.installMockForType(dienst, IdentitaetsDienst.class);

        given().auth().oauth2(token).contentType("application/json")
                .post(pfad + fremd + "/zugang-zuruecksetzen").then().statusCode(404);
        given().auth().oauth2(token).contentType("application/json").body("{\"unerwartet\":true}")
                .post(pfad + ziel + "/zugang-zuruecksetzen").then().statusCode(400);
        assertThat(aufrufe).isEmpty();
        given().auth().oauth2(token).contentType("application/json")
                .post(pfad + ziel + "/zugang-zuruecksetzen").then().statusCode(204);
        assertThat(aufrufe).containsExactly("aktionen", "otp", "sitzungen", "einladung");
    }

    @Test
    void googleLoesenPrueftStepUpMandantUndBestehendeVerknuepfung() {
        String pfad = "/api/v1/benutzer/";
        String token = Testanmeldung.token("inhaber-urinet");
        given().auth().oauth2(token).contentType("application/json")
                .delete(pfad + UUID.randomUUID() + "/google-verknuepfung")
                .then().statusCode(401).body("fehlercode", is("STEP_UP_NOETIG"));

        QuarkusMock.installMockForType(new StepUpMitAuthTime(null, Duration.ofMinutes(5)) {
            @Override
            public void pruefen() { }
        }, StepUp.class);
        IdentitaetsDienstStub dienst = new IdentitaetsDienstStub();
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String ziel = dienst.anlegen("urinet", person("google." + suffix), Rolle.MITARBEITER);
        String fremd = dienst.anlegen("vhf-gsk", person("fremd." + suffix), Rolle.MITARBEITER);
        QuarkusMock.installMockForType(dienst, IdentitaetsDienst.class);

        given().auth().oauth2(token).contentType("application/json")
                .delete(pfad + fremd + "/google-verknuepfung").then().statusCode(404);
        given().auth().oauth2(token).contentType("application/json").body("{\"googleId\":\"verboten\"}")
                .delete(pfad + ziel + "/google-verknuepfung").then().statusCode(400);
        given().auth().oauth2(token).contentType("application/json").delete(pfad + ziel + "/google-verknuepfung")
                .then().statusCode(409).body("fehlercode", is("STATUS_KONFLIKT"));
    }

    @Test
    void emailAendernPrueftVersionMandantUndSendetBeideMitteilungen() {
        String pfad = "/api/v1/benutzer/";
        String token = Testanmeldung.token("inhaber-urinet");
        given().auth().oauth2(token).contentType("application/json").body("{\"email\":\"neu@example.invalid\"}")
                .patch(pfad + UUID.randomUUID()).then().statusCode(401).body("fehlercode", is("STEP_UP_NOETIG"));

        QuarkusMock.installMockForType(new StepUpMitAuthTime(null, Duration.ofMinutes(5)) {
            @Override
            public void pruefen() { }
        }, StepUp.class);
        IdentitaetsDienstStub dienst = new IdentitaetsDienstStub();
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String ziel = dienst.anlegen("urinet", person("email." + suffix), Rolle.MITARBEITER);
        String fremd = dienst.anlegen("vhf-gsk", person("fremd." + suffix), Rolle.MITARBEITER);
        QuarkusMock.installMockForType(dienst, IdentitaetsDienst.class);
        List<String> mitteilungen = new java.util.ArrayList<>();
        QuarkusMock.installMockForType(new MailBenutzerMitteilung(mails -> {
            for (var mail : mails) {
                mitteilungen.addAll(mail.getTo());
            }
        }), BenutzerMitteilung.class);
        String etag = given().auth().oauth2(token).get(pfad + ziel).then().statusCode(200).extract().header("ETag");
        String neu = "neu." + suffix + "@example.invalid";

        given().auth().oauth2(token).contentType("application/json").body("{}")
                .patch(pfad + ziel).then().statusCode(428).body("fehlercode", is("VERSION_FEHLT"));
        given().auth().oauth2(token).contentType("application/json").header("If-Match", "\"" + "0".repeat(64) + "\"")
                .body("{\"email\":\"ungültig\"}").patch(pfad + ziel)
                .then().statusCode(412).body("fehlercode", is("VERSION_KONFLIKT"));
        given().auth().oauth2(token).contentType("application/json").header("If-Match", etag)
                .body("{\"email\":\"ungültig\"}").patch(pfad + ziel).then().statusCode(422);
        given().auth().oauth2(token).contentType("application/json").header("If-Match", etag)
                .body("{\"email\":\"" + neu + "\"}").patch(pfad + fremd).then().statusCode(404);
        given().auth().oauth2(token).contentType("application/json").header("If-Match", etag)
                .body("{\"email\":\"" + neu + "\",\"vorname\":\"Neu" + suffix + "\"}")
                .patch(pfad + ziel).then().statusCode(204);
        assertThat(dienst.benutzerLesen("urinet", ziel).email()).isEqualTo(neu);
        assertThat(dienst.benutzerLesen("urinet", ziel).vorname()).isEqualTo("Neu" + suffix);
        assertThat(dienst.benutzerLesen("urinet", ziel).aktionen()).containsExactlyInAnyOrder(
                IdentitaetsDienst.ErforderlicheAktion.VERIFY_EMAIL,
                IdentitaetsDienst.ErforderlicheAktion.UPDATE_PASSWORD,
                IdentitaetsDienst.ErforderlicheAktion.CONFIGURE_TOTP);
        assertThat(mitteilungen).containsExactly("email." + suffix + "@example.invalid");
        given().auth().oauth2(token).contentType("application/json").header("If-Match", etag)
                .body("{\"email\":\"" + neu + "\"}").patch(pfad + ziel).then().statusCode(412);
    }

    @Test
    void namenAendernVerlangtVersionAberKeinStepUpUndProtokolliertKeineNamen() throws SQLException {
        IdentitaetsDienstStub dienst = new IdentitaetsDienstStub();
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String ziel = dienst.anlegen("urinet", person("name." + suffix), Rolle.MITARBEITER);
        QuarkusMock.installMockForType(dienst, IdentitaetsDienst.class);
        String token = Testanmeldung.token("inhaber-urinet");
        String pfad = "/api/v1/benutzer/" + ziel;
        String etag = given().auth().oauth2(token).get(pfad).then().statusCode(200).extract().header("ETag");
        String namen = "{\"vorname\":\"Neu" + suffix + "\",\"nachname\":\"Person" + suffix + "\"}";

        given().auth().oauth2(token).contentType("application/json").body(namen)
                .patch(pfad).then().statusCode(428);
        given().auth().oauth2(token).contentType("application/json")
                .header("If-Match", "\"" + "0".repeat(64) + "\"").body(namen)
                .patch(pfad).then().statusCode(412);
        given().auth().oauth2(token).contentType("application/json").header("If-Match", etag)
                .body("{\"vorname\":\"\"}").patch(pfad).then().statusCode(422);
        given().auth().oauth2(token).contentType("application/json").header("If-Match", etag)
                .body(namen).patch(pfad).then().statusCode(204);
        assertThat(dienst.benutzerLesen("urinet", ziel).vorname()).isEqualTo("Neu" + suffix);
        assertThat(dienst.benutzerLesen("urinet", ziel).nachname()).isEqualTo("Person" + suffix);
        given().auth().oauth2(token).contentType("application/json").header("If-Match", etag)
                .body(namen).patch(pfad).then().statusCode(412);
        try (Connection verbindung = datenbank.getConnection();
             PreparedStatement abfrage = verbindung.prepareStatement(
                     "select details::text, details->'felder'->>'vorname', details->'felder'->>'nachname' "
                             + "from m_urinet.zugriff_protokoll where details->>'ziel' = ? "
                             + "and details->>'vorgang' = 'NAME_AENDERN' and details->>'phase' = 'ABGESCHLOSSEN'")) {
            abfrage.setString(1, ziel);
            try (ResultSet zeile = abfrage.executeQuery()) {
                assertThat(zeile.next()).isTrue();
                assertThat(zeile.getString(1)).doesNotContain("Neu" + suffix, "Person" + suffix);
                assertThat(zeile.getString(2)).isEqualTo("geändert");
                assertThat(zeile.getString(3)).isEqualTo("geändert");
            }
        }
    }

    @Test
    void vergebeneEmailBleibtNeutralUndGibtDieVorgangssperreFrei() {
        QuarkusMock.installMockForType(new StepUpMitAuthTime(null, Duration.ofMinutes(5)) {
            @Override
            public void pruefen() { }
        }, StepUp.class);
        IdentitaetsDienstStub dienst = new IdentitaetsDienstStub() {
            private boolean konfligiert = true;

            @Override
            public synchronized void aktualisieren(String mandant, String id, Aenderung aenderung) {
                if (konfligiert) {
                    konfligiert = false;
                    throw new KonfliktException();
                }
                super.aktualisieren(mandant, id, aenderung);
            }
        };
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String ziel = dienst.anlegen("urinet", person("email." + suffix), Rolle.MITARBEITER);
        QuarkusMock.installMockForType(dienst, IdentitaetsDienst.class);
        QuarkusMock.installMockForType(new MailBenutzerMitteilung(mails -> { }), BenutzerMitteilung.class);
        String token = Testanmeldung.token("inhaber-urinet");
        String pfad = "/api/v1/benutzer/" + ziel;
        String etag = given().auth().oauth2(token).get(pfad).then().statusCode(200).extract().header("ETag");
        String eingabe = "{\"email\":\"neu." + suffix + "@example.invalid\"}";
        given().auth().oauth2(token).contentType("application/json").header("If-Match", etag).body(eingabe)
                .patch(pfad).then().statusCode(409).body("fehlercode", is("BENUTZER_EXISTIERT"))
                .body(not(containsString(suffix)));
        String zweitesZiel = dienst.anlegen("urinet", person("zweite." + suffix), Rolle.MITARBEITER);
        String zweiterPfad = "/api/v1/benutzer/" + zweitesZiel;
        String zweiterEtag = given().auth().oauth2(token).get(zweiterPfad)
                .then().statusCode(200).extract().header("ETag");
        given().auth().oauth2(token).contentType("application/json").header("If-Match", zweiterEtag).body(eingabe)
                .patch(zweiterPfad).then().statusCode(204);
    }

    @Test
    void statusAktionenSchuetzenSelbstzugriffLetztenInhaberUndMandant() {
        String akteurId = Testanmeldung.sub("inhaber-urinet");
        IdentitaetsDienstStub dienst = new IdentitaetsDienstStub() {
            @Override
            public synchronized Benutzer benutzerLesen(String mandant, String id) {
                if (id.equals(akteurId) && mandant.equals("urinet")) {
                    return new Benutzer(id, "akteur", "akteur@example.invalid", "Test", "Person", true,
                            Set.of(Rolle.INHABER), Set.of(), true, false);
                }
                return super.benutzerLesen(mandant, id);
            }

            @Override
            public synchronized long inhaberZaehlen(String mandant) {
                return 1;
            }
        };
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String inhaber = dienst.anlegen("urinet", person("inhaber." + suffix), Rolle.INHABER);
        dienst.einladen("urinet", inhaber, Set.of(), Duration.ofHours(1));
        String fremd = dienst.anlegen("vhf-gsk", person("fremd." + suffix), Rolle.MITARBEITER);
        QuarkusMock.installMockForType(dienst, IdentitaetsDienst.class);
        String token = Testanmeldung.token("inhaber-urinet");
        String pfad = "/api/v1/benutzer/";
        for (String aktion : List.of("sperren", "deaktivieren")) {
            given().auth().oauth2(token).contentType("application/json").body("{\"grund\":\"SICHERHEIT\"}")
                    .post(pfad + akteurId + "/" + aktion).then().statusCode(403)
                    .body("fehlercode", is("ESKALATION_VERBOTEN"));
            given().auth().oauth2(token).contentType("application/json").body("{\"grund\":\"SICHERHEIT\"}")
                    .post(pfad + inhaber + "/" + aktion).then().statusCode(409)
                    .body("fehlercode", is("LETZTER_INHABER"));
            given().auth().oauth2(token).contentType("application/json").body("{\"grund\":\"SICHERHEIT\"}")
                    .post(pfad + fremd + "/" + aktion).then().statusCode(404);
        }
        given().auth().oauth2(token).contentType("application/json").body("{\"grund\":\"freitext\"}")
                .post(pfad + inhaber + "/sperren").then().statusCode(422);
    }

    @Test
    void deaktivierungWiderruftAltesTokenBeiUnklarerTeilwirkung() throws SQLException {
        String id = Testanmeldung.sub("mitarbeiter-urinet");
        String token = Testanmeldung.token("mitarbeiter-urinet");
        List<String> aufrufe = new java.util.ArrayList<>();
        IdentitaetsDienstStub dienst = new TestIdentitaetsDienst(id, false) {
            @Override
            public synchronized void aktivieren(String mandant, String benutzerId, boolean aktiv) {
                aufrufe.add("aktivieren");
            }

            @Override
            public synchronized void sitzungenBeenden(String mandant, String benutzerId) {
                aufrufe.add("sitzungen");
                throw new FachFehler(Fehlercode.IDENTITAETSDIENST_FEHLER);
            }
        };
        QuarkusMock.installMockForType(dienst, IdentitaetsDienst.class);

        unklareWirkungWiderruftToken(token, "urinet", () ->
                given().auth().oauth2(Testanmeldung.token("inhaber-urinet")).contentType("application/json")
                        .body("{\"grund\":\"SICHERHEIT\"}")
                        .post("/api/v1/benutzer/" + id + "/deaktivieren").then()
                        .statusCode(502).body("fehlercode", is("IDENTITAETSDIENST_FEHLER")));
        assertThat(aufrufe).containsExactly("aktivieren", "sitzungen");
    }

    @Test
    void googleLoesenWiderruftAltesTokenBeiUnklarerTeilwirkung() throws SQLException {
        String id = Testanmeldung.sub("mitarbeiter-urinet");
        String token = Testanmeldung.token("mitarbeiter-urinet");
        List<String> aufrufe = new java.util.ArrayList<>();
        QuarkusMock.installMockForType(new StepUpMitAuthTime(null, Duration.ofMinutes(5)) {
            @Override
            public void pruefen() { }
        }, StepUp.class);
        IdentitaetsDienstStub dienst = new TestIdentitaetsDienst(id, true) {
            @Override
            public synchronized void googleLoesen(String mandant, String benutzerId) {
                aufrufe.add("google");
            }

            @Override
            public synchronized void sitzungenBeenden(String mandant, String benutzerId) {
                aufrufe.add("sitzungen");
                throw new FachFehler(Fehlercode.IDENTITAETSDIENST_FEHLER);
            }
        };
        QuarkusMock.installMockForType(dienst, IdentitaetsDienst.class);

        unklareWirkungWiderruftToken(token, "urinet", () ->
                given().auth().oauth2(Testanmeldung.token("inhaber-urinet")).contentType("application/json")
                        .delete("/api/v1/benutzer/" + id + "/google-verknuepfung").then()
                        .statusCode(502).body("fehlercode", is("IDENTITAETSDIENST_FEHLER")));
        assertThat(aufrufe).containsExactly("google", "sitzungen");
    }

    @Test
    void zugangZuruecksetzenWiderruftAltesTokenBeiUnklarerTeilwirkung() throws SQLException {
        String id = Testanmeldung.sub("mitarbeiter-urinet");
        String token = Testanmeldung.token("mitarbeiter-urinet");
        List<String> aufrufe = new java.util.ArrayList<>();
        QuarkusMock.installMockForType(new StepUpMitAuthTime(null, Duration.ofMinutes(5)) {
            @Override
            public void pruefen() { }
        }, StepUp.class);
        IdentitaetsDienstStub dienst = new TestIdentitaetsDienst(id, false) {
            @Override
            public synchronized void aktionenHinzufuegen(String mandant, String benutzerId,
                                                          Set<ErforderlicheAktion> aktionen) {
                aufrufe.add("aktionen");
            }

            @Override
            public synchronized void otpEntfernen(String mandant, String benutzerId) {
                aufrufe.add("otp");
            }

            @Override
            public synchronized void sitzungenBeenden(String mandant, String benutzerId) {
                aufrufe.add("sitzungen");
            }

            @Override
            public synchronized void einladen(String mandant, String benutzerId,
                                              Set<ErforderlicheAktion> aktionen, Duration gueltigkeit) {
                aufrufe.add("einladung");
                throw new FachFehler(Fehlercode.IDENTITAETSDIENST_FEHLER);
            }
        };
        QuarkusMock.installMockForType(dienst, IdentitaetsDienst.class);

        unklareWirkungWiderruftToken(token, "urinet", () ->
                given().auth().oauth2(Testanmeldung.token("inhaber-urinet")).contentType("application/json")
                        .post("/api/v1/benutzer/" + id + "/zugang-zuruecksetzen").then()
                        .statusCode(502).body("fehlercode", is("IDENTITAETSDIENST_FEHLER")));
        assertThat(aufrufe).containsExactly("aktionen", "otp", "sitzungen", "einladung");
    }

    private void unklareWirkungWiderruftToken(String token, String mandant, Runnable aktion)
            throws SQLException {
        try {
            given().auth().oauth2(token).get("/api/v1/mandant/funktionen").then().statusCode(200);
            aktion.run();
            assertThat(tokenWiderruf.widerrufen(Testanmeldung.sub("mitarbeiter-urinet"), java.time.Instant.EPOCH))
                    .isTrue();
            given().auth().oauth2(token).get("/api/v1/mandant/funktionen").then().statusCode(401)
                    .body("fehlercode", is("NICHT_ANGEMELDET"));
        } finally {
            tokenWiderruf.aufraeumen(java.time.Instant.now().plusSeconds(1));
            try (Connection verbindung = datenbank.getConnection();
                 PreparedStatement loeschen = verbindung.prepareStatement(
                         "delete from public.verwaltung_vorgang where mandant = ?")) {
                loeschen.setString(1, mandant);
                loeschen.executeUpdate();
            }
        }
    }

    private abstract static class TestIdentitaetsDienst extends IdentitaetsDienstStub {
        private final String ziel;
        private final boolean google;

        private TestIdentitaetsDienst(String ziel, boolean google) {
            this.ziel = ziel;
            this.google = google;
        }

        @Override
        public synchronized IdentitaetsDienst.Benutzer benutzerLesen(String mandant, String id) {
            if (mandant.equals("urinet") && id.equals(ziel)) {
                return new IdentitaetsDienst.Benutzer(id, "synthetisch", "synthetisch@example.invalid",
                        "Test", "Person", true, Set.of(Rolle.MITARBEITER), Set.of(), true, google);
            }
            return super.benutzerLesen(mandant, id);
        }
    }

    @Test
    void lesendeUndSchreibendeVerwaltungVerweigertAndereRollenMitProtokoll() throws SQLException {
        for (String[] fall : List.of(
                new String[]{"mitarbeiter-urinet", "m_urinet", "MITARBEITER"},
                new String[]{"treuhaender-urinet", "m_urinet", "TREUHAENDER"},
                new String[]{"geschaeftsstelle-vhf-gsk", "m_vhf_gsk", "GESCHAEFTSSTELLE"})) {
            long lesenVorher = verweigerungen(fall[1], fall[2], "A21");
            long schreibenVorher = verweigerungen(fall[1], fall[2], "A22");
            String token = Testanmeldung.token(fall[0]);
            given().auth().oauth2(token).get("/api/v1/benutzer").then().statusCode(403);
            given().auth().oauth2(token).contentType("application/json").body("{\"grund\":\"SICHERHEIT\"}")
                    .post("/api/v1/benutzer/" + UUID.randomUUID() + "/sperren").then().statusCode(403);
            assertThat(verweigerungen(fall[1], fall[2], "A21")).isEqualTo(lesenVorher + 1);
            assertThat(verweigerungen(fall[1], fall[2], "A22")).isEqualTo(schreibenVorher + 1);
        }
    }

    private long verweigerungen(String schema, String rolle, String recht) throws SQLException {
        try (Connection verbindung = datenbank.getConnection();
             PreparedStatement abfrage = verbindung.prepareStatement(
                     "select count(*) from " + schema + ".zugriff_protokoll "
                             + "where aktion = 'VERWEIGERT' and rolle = ? and details->>'grund' = ?")) {
            abfrage.setString(1, rolle);
            abfrage.setString(2, recht);
            try (ResultSet zeile = abfrage.executeQuery()) {
                zeile.next();
                return zeile.getLong(1);
            }
        }
    }

    @Test
    void fehlversuchssperreIstSichtbarUndKannAufgehobenWerden() {
        QuarkusMock.installMockForType(new StepUpMitAuthTime(null, Duration.ofMinutes(5)) {
            @Override
            public void pruefen() { }
        }, StepUp.class);
        IdentitaetsDienstStub dienst = new IdentitaetsDienstStub() {
            private boolean gesperrt = true;

            @Override
            public synchronized boolean anmeldungGesperrt(String mandant, String id) {
                super.anmeldungGesperrt(mandant, id);
                return gesperrt;
            }

            @Override
            public synchronized void sperreAufheben(String mandant, String id) {
                super.sperreAufheben(mandant, id);
                gesperrt = false;
            }
        };
        String ziel = dienst.anlegen("urinet", person("probe." + UUID.randomUUID().toString().substring(0, 8)),
                Rolle.MITARBEITER);
        dienst.einladen("urinet", ziel, Set.of(), Duration.ofHours(1));
        QuarkusMock.installMockForType(dienst, IdentitaetsDienst.class);
        String token = Testanmeldung.token("inhaber-urinet");
        String pfad = "/api/v1/benutzer/" + ziel;
        given().auth().oauth2(token).get(pfad).then().statusCode(200).body("voruebergehendGesperrt", is(true));
        given().auth().oauth2(token).contentType("application/json")
                .post(pfad + "/entsperren").then().statusCode(204);
        given().auth().oauth2(token).get(pfad).then().statusCode(200).body("voruebergehendGesperrt", is(false));
        given().auth().oauth2(token).contentType("application/json")
                .post(pfad + "/entsperren").then().statusCode(409);
    }

    @Test
    void kontoOhneRolleKannDeaktiviertUndZurueckgesetztWerden() {
        QuarkusMock.installMockForType(new StepUpMitAuthTime(null, Duration.ofMinutes(5)) {
            @Override
            public void pruefen() { }
        }, StepUp.class);
        IdentitaetsDienstStub dienst = new IdentitaetsDienstStub();
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String deaktivieren = dienst.anlegenOhneRolle("urinet", person("offen." + suffix));
        String zuruecksetzen = dienst.anlegenOhneRolle("urinet", person("reset." + suffix));
        QuarkusMock.installMockForType(dienst, IdentitaetsDienst.class);
        String token = Testanmeldung.token("inhaber-urinet");
        String pfad = "/api/v1/benutzer/";
        given().auth().oauth2(token).contentType("application/json").body("{\"grund\":\"KORREKTUR\"}")
                .post(pfad + deaktivieren + "/deaktivieren").then().statusCode(204);
        given().auth().oauth2(token).contentType("application/json")
                .post(pfad + zuruecksetzen + "/zugang-zuruecksetzen").then().statusCode(204);
        assertThat(dienst.benutzerLesen("urinet", deaktivieren).rollen()).isEmpty();
        assertThat(dienst.benutzerLesen("urinet", zuruecksetzen).rollen()).isEmpty();
    }

    @Test
    void rollenwechselPrueftVersionMandantUndVierAugen() {
        QuarkusMock.installMockForType(new StepUpMitAuthTime(null, Duration.ofMinutes(5)) {
            @Override
            public void pruefen() { }
        }, StepUp.class);
        IdentitaetsDienstStub dienst = new IdentitaetsDienstStub();
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String ziel = dienst.anlegen("urinet", person("rolle." + suffix), Rolle.MITARBEITER);
        String fremd = dienst.anlegen("vhf-gsk", person("fremd." + suffix), Rolle.MITARBEITER);
        QuarkusMock.installMockForType(dienst, IdentitaetsDienst.class);
        String token = Testanmeldung.token("inhaber-urinet");
        String pfad = "/api/v1/benutzer/" + ziel;
        String etag = given().auth().oauth2(token).get(pfad).then().statusCode(200).extract().header("ETag");
        given().auth().oauth2(token).contentType("application/json").body("{\"rolle\":\"treuhaender\"}")
                .put(pfad + "/rolle").then().statusCode(428);
        given().auth().oauth2(token).contentType("application/json").header("If-Match", "\"" + "0".repeat(64) + "\"")
                .body("{\"rolle\":\"treuhaender\"}").put(pfad + "/rolle").then().statusCode(412);
        given().auth().oauth2(token).contentType("application/json").header("If-Match", etag)
                .body("{\"rolle\":\"treuhaender\"}").put("/api/v1/benutzer/" + fremd + "/rolle")
                .then().statusCode(404);
        given().auth().oauth2(token).contentType("application/json").header("If-Match", etag)
                .body("{\"rolle\":\"treuhaender\"}").put(pfad + "/rolle").then().statusCode(204);
        assertThat(dienst.benutzerLesen("urinet", ziel).rollen()).containsExactly(Rolle.TREUHAENDER);

        for (int i = 0; i < 2; i++) {
            String inhaber = dienst.anlegen("urinet", person("inhaber" + i + "." + suffix), Rolle.INHABER);
            dienst.einladen("urinet", inhaber, Set.of(), Duration.ofHours(1));
        }
        etag = given().auth().oauth2(token).get(pfad).then().statusCode(200).extract().header("ETag");
        String antragId = given().auth().oauth2(token).contentType("application/json").header("If-Match", etag)
                .body("{\"rolle\":\"inhaber\"}").put(pfad + "/rolle").then().statusCode(202)
                .extract().path("antragId");
        assertThat(UUID.fromString(antragId)).isNotNull();
        assertThat(dienst.benutzerLesen("urinet", ziel).rollen()).containsExactly(Rolle.TREUHAENDER);
    }

    @Test
    void inhaberAntragErfordertZweitenAktivenInhaberUndVerfaellt() throws SQLException {
        QuarkusMock.installMockForType(new StepUpMitAuthTime(null, Duration.ofMinutes(5)) {
            @Override
            public void pruefen() { }
        }, StepUp.class);
        String akteurId = Testanmeldung.sub("inhaber-urinet");
        IdentitaetsDienstStub dienst = new IdentitaetsDienstStub() {
            @Override
            public synchronized Benutzer benutzerLesen(String mandant, String id) {
                if (mandant.equals("urinet") && id.equals(akteurId)) {
                    return new Benutzer(id, "akteur", "akteur@example.invalid", "Test", "Person", true,
                            Set.of(Rolle.INHABER), Set.of(), true, false);
                }
                return super.benutzerLesen(mandant, id);
            }
        };
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String ziel = dienst.anlegen("urinet", person("antrag." + suffix), Rolle.MITARBEITER);
        QuarkusMock.installMockForType(dienst, IdentitaetsDienst.class);
        String token = Testanmeldung.token("inhaber-urinet");
        UUID eigener = antragAnlegen(ziel, akteurId, false);
        UUID abgelaufen = antragAnlegen(ziel, UUID.randomUUID().toString(), true);
        UUID fremder = antragAnlegen(ziel, UUID.randomUUID().toString(), false);
        String pfad = "/api/v1/benutzer/antraege/";

        try (Connection verbindung = datenbank.getConnection();
             PreparedStatement aenderung = verbindung.prepareStatement(
                     "update m_urinet.verwaltung_antrag set grund = ? where id = ?")) {
            aenderung.setString(1, "private-probe@example.invalid");
            aenderung.setObject(2, eigener);
            assertThat(aenderung.executeUpdate()).isEqualTo(1);
        }
        given().auth().oauth2(token).get("/api/v1/benutzer/antraege").then().statusCode(200)
                .body(containsString("NICHT_ANZEIGBAR")).body(not(containsString("private-probe@example.invalid")));
        given().auth().oauth2(Testanmeldung.token("mitarbeiter-urinet"))
                .get("/api/v1/benutzer/antraege").then().statusCode(403);
        given().auth().oauth2(Testanmeldung.token("inhaber-vhf-gsk")).contentType("application/json")
                .post(pfad + fremder + "/bestaetigen").then().statusCode(404);
        given().auth().oauth2(token).contentType("application/json")
                .post(pfad + fremder + "/zurueckziehen").then().statusCode(403);
        given().auth().oauth2(token).contentType("application/json")
                .post(pfad + eigener + "/bestaetigen").then().statusCode(403);
        given().auth().oauth2(token).contentType("application/json")
                .post(pfad + abgelaufen + "/bestaetigen").then().statusCode(409);
        assertThat(dienst.benutzerLesen("urinet", ziel).rollen()).containsExactly(Rolle.MITARBEITER);
        given().auth().oauth2(token).contentType("application/json")
                .post(pfad + fremder + "/bestaetigen").then().statusCode(204);
        assertThat(dienst.benutzerLesen("urinet", ziel).rollen()).containsExactly(Rolle.INHABER);
        given().auth().oauth2(token).contentType("application/json")
                .post(pfad + fremder + "/bestaetigen").then().statusCode(409);
        given().auth().oauth2(token).contentType("application/json")
                .post(pfad + eigener + "/zurueckziehen").then().statusCode(204);
    }

    @Test
    void rollenkonfliktLaesstNurRollenwechselZu() {
        QuarkusMock.installMockForType(new StepUpMitAuthTime(null, Duration.ofMinutes(5)) {
            @Override
            public void pruefen() { }
        }, StepUp.class);
        IdentitaetsDienstStub dienst = new IdentitaetsDienstStub() {
            private boolean konflikt = true;

            @Override
            public synchronized Benutzer benutzerLesen(String mandant, String id) {
                Benutzer benutzer = super.benutzerLesen(mandant, id);
                return konflikt ? new Benutzer(benutzer.id(), benutzer.benutzername(), benutzer.email(),
                        benutzer.vorname(), benutzer.nachname(), benutzer.aktiv(),
                        Set.of(Rolle.MITARBEITER, Rolle.TREUHAENDER), benutzer.aktionen(), benutzer.totp(), true)
                        : benutzer;
            }

            @Override
            public synchronized void rolleSetzen(String mandant, String id, Rolle rolle) {
                super.rolleSetzen(mandant, id, rolle);
                konflikt = false;
            }
        };
        String ziel = dienst.anlegen("urinet", person("konflikt." + UUID.randomUUID().toString().substring(0, 8)),
                Rolle.MITARBEITER);
        QuarkusMock.installMockForType(dienst, IdentitaetsDienst.class);
        String token = Testanmeldung.token("inhaber-urinet");
        String pfad = "/api/v1/benutzer/" + ziel;
        String etag = given().auth().oauth2(token).get(pfad).then().statusCode(200)
                .body("rollenkonflikt", is(true)).extract().header("ETag");
        given().auth().oauth2(token).get(pfad + "/rechte").then().statusCode(409);
        given().auth().oauth2(token).contentType("application/json").header("If-Match", etag)
                .body("{\"vorname\":\"Neu\"}").patch(pfad).then().statusCode(409);
        given().auth().oauth2(token).contentType("application/json")
                .delete(pfad + "/google-verknuepfung").then().statusCode(409);
        given().auth().oauth2(token).contentType("application/json").header("If-Match", etag)
                .body("{\"rolle\":\"mitarbeiter\"}").put(pfad + "/rolle").then().statusCode(204);
        given().auth().oauth2(token).get(pfad).then().statusCode(200).body("rollenkonflikt", is(false));
    }

    @Test
    void letzterAktiverInhaberKannSeineRolleNichtVerlieren() {
        QuarkusMock.installMockForType(new StepUpMitAuthTime(null, Duration.ofMinutes(5)) {
            @Override
            public void pruefen() { }
        }, StepUp.class);
        IdentitaetsDienstStub dienst = new IdentitaetsDienstStub();
        String ziel = dienst.anlegen("urinet", person("letzter." + UUID.randomUUID().toString().substring(0, 8)),
                Rolle.INHABER);
        dienst.einladen("urinet", ziel, Set.of(), Duration.ofHours(1));
        QuarkusMock.installMockForType(dienst, IdentitaetsDienst.class);
        String token = Testanmeldung.token("inhaber-urinet");
        String pfad = "/api/v1/benutzer/" + ziel;
        String etag = given().auth().oauth2(token).get(pfad).then().statusCode(200).extract().header("ETag");
        given().auth().oauth2(token).contentType("application/json").header("If-Match", etag)
                .body("{\"rolle\":\"mitarbeiter\"}").put(pfad + "/rolle").then().statusCode(409)
                .body("fehlercode", is("LETZTER_INHABER"));
        assertThat(dienst.benutzerLesen("urinet", ziel).rollen()).containsExactly(Rolle.INHABER);
    }

    private UUID antragAnlegen(String ziel, String akteur, boolean abgelaufen) throws SQLException {
        UUID id = UUID.randomUUID();
        try (Connection verbindung = datenbank.getConnection();
             PreparedStatement einfuegen = verbindung.prepareStatement(
                     "insert into m_urinet.verwaltung_antrag "
                             + "(id, art, ziel, rolle, beantragt_von, beantragt_am, gueltig_bis, status) "
                             + "values (?, 'INHABER_ROLLE', ?, 'inhaber', ?, now() - interval '2 hours', "
                             + (abgelaufen ? "now() - interval '1 hour'" : "now() + interval '1 hour'")
                             + ", 'OFFEN')")) {
            einfuegen.setObject(1, id);
            einfuegen.setString(2, ziel);
            einfuegen.setString(3, akteur);
            einfuegen.executeUpdate();
        }
        return id;
    }

    @Test
    void anlegenPrueftRollenNamenssperreUndVierAugen() throws SQLException {
        QuarkusMock.installMockForType(new StepUpMitAuthTime(null, Duration.ofMinutes(5)) {
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
        QuarkusMock.installMockForType(new StepUpMitAuthTime(null, Duration.ofMinutes(5)) {
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
        QuarkusMock.installMockForType(new StepUpMitAuthTime(null, Duration.ofMinutes(5)) {
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
        QuarkusMock.installMockForType(new StepUpMitAuthTime(null, Duration.ofMinutes(5)) {
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
