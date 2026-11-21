package ch.urinet.archiv.benutzer.adapter.in.rest;

import ch.urinet.archiv.benutzer.application.IdentitaetsDienst;
import ch.urinet.archiv.gemeinsam.Testanmeldung;
import ch.urinet.archiv.mandant.application.MandantProvisionierung;
import ch.urinet.archiv.sicherheit.domain.Aktion;
import ch.urinet.archiv.sicherheit.domain.Rechtematrix;
import ch.urinet.archiv.sicherheit.domain.Rolle;
import io.agroal.api.AgroalDataSource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.common.mapper.TypeRef;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

@QuarkusTest
class VerwaltungCacheHttpTest {

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
                    .when().post(pfad + "/" + eigeneId + "/einladung").then().statusCode(403);
            given().auth().oauth2(Testanmeldung.token("inhaber-urinet"))
                    .when().post(pfad + "/" + fremdeId + "/einladung").then().statusCode(404);
            given().auth().oauth2(Testanmeldung.token("inhaber-urinet"))
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
        } finally {
            identitaet.loeschen("urinet", eigeneId);
            identitaet.loeschen("vhf-gsk", fremdeId);
        }
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
