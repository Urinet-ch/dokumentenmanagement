package ch.urinet.archiv.benutzer.adapter.in.rest;

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

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;

@QuarkusTest
class VerwaltungCacheHttpTest {

    @Inject
    AgroalDataSource datenbank;

    @Inject
    MandantProvisionierung provisionierung;

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
