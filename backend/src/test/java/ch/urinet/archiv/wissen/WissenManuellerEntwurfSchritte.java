package ch.urinet.archiv.wissen;

import ch.urinet.archiv.gemeinsam.Testanmeldung;
import io.agroal.api.AgroalDataSource;
import io.cucumber.java.After;
import io.cucumber.java.de.Dann;
import io.cucumber.java.de.Wenn;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

public class WissenManuellerEntwurfSchritte {
    @Inject
    Instance<AgroalDataSource> datenbank;

    private final String pfad = "konzepte/manuell-" + UUID.randomUUID();
    private UUID entwurf;
    private int status;

    @Wenn("der Inhaber einen manuellen Wissensentwurf ohne Titel anlegt")
    public void inhaberLegtEntwurfAn() {
        String id = given().auth().oauth2(Testanmeldung.token("inhaber-urinet"))
                .contentType("application/json").body(eingabe())
                .when().post("/api/v1/wissensentwuerfe").then().statusCode(201).extract().path("id");
        entwurf = UUID.fromString(id);
    }

    @Dann("ist der Entwurf offen und hat den Befund «{word}»")
    public void entwurfHatBefund(String code) throws Exception {
        try (Connection verbindung = datenbank.get().getConnection();
             PreparedStatement befehl = verbindung.prepareStatement("""
                     select count(*) from m_urinet.wissens_entwurf e
                     join m_urinet.wissens_aenderung a on a.entwurf_id = e.id
                     where e.id = ? and e.anlass = 'MANUELL' and e.status = 'OFFEN'
                       and a.befunde @> jsonb_build_array(jsonb_build_object('code', cast(? as text)))
                     """)) {
            befehl.setObject(1, entwurf);
            befehl.setString(2, code);
            try (var ergebnis = befehl.executeQuery()) {
                assertThat(ergebnis.next()).isTrue();
                assertThat(ergebnis.getInt(1)).isEqualTo(1);
            }
        }
    }

    @Wenn("der Treuhänder denselben manuellen Wissensentwurf anlegen möchte")
    public void treuhaenderLegtEntwurfAn() {
        status = given().auth().oauth2(Testanmeldung.token("treuhaender-urinet"))
                .contentType("application/json").body(eingabe())
                .when().post("/api/v1/wissensentwuerfe").statusCode();
    }

    @Dann("erhält er 403 und es entsteht kein weiterer Wissensentwurf")
    public void treuhaenderAbgewiesen() throws Exception {
        assertThat(status).isEqualTo(403);
        try (Connection verbindung = datenbank.get().getConnection();
             PreparedStatement befehl = verbindung.prepareStatement(
                     "select count(*) from m_urinet.wissens_aenderung where pfad = ?")) {
            befehl.setString(1, pfad);
            try (var ergebnis = befehl.executeQuery()) {
                assertThat(ergebnis.next()).isTrue();
                assertThat(ergebnis.getInt(1)).isEqualTo(1);
            }
        }
    }

    @After("@wissen-manuell")
    public void aufraeumen() throws Exception {
        if (entwurf == null) {
            return;
        }
        try (Connection verbindung = datenbank.get().getConnection()) {
            for (String tabelle : new String[]{"wissens_aenderung", "wissens_entwurf"}) {
                try (PreparedStatement befehl = verbindung.prepareStatement("delete from m_urinet." + tabelle
                        + (tabelle.equals("wissens_aenderung") ? " where entwurf_id = ?" : " where id = ?"))) {
                    befehl.setObject(1, entwurf);
                    befehl.executeUpdate();
                }
            }
        }
    }

    private Map<String, Object> eingabe() {
        return Map.of("pfad", pfad, "typ", "Konzept", "titel", "", "beschreibung", "Probe", "inhalt", "Probe.");
    }
}
