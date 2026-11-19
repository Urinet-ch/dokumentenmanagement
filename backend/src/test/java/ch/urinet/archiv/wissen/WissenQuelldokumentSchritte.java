package ch.urinet.archiv.wissen;

import ch.urinet.archiv.dokument.Testdokumente;
import ch.urinet.archiv.gemeinsam.Testanmeldung;
import io.agroal.api.AgroalDataSource;
import io.cucumber.java.After;
import io.cucumber.java.de.Angenommen;
import io.cucumber.java.de.Dann;
import io.cucumber.java.de.Wenn;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;

public class WissenQuelldokumentSchritte {
    @Inject
    Instance<AgroalDataSource> datenbank;

    private UUID dokument;
    private UUID seite;

    @Angenommen("eine normale Wissensseite verweist auf ein normales Quelldokument")
    public void normaleQuelle() throws Exception {
        dokument = Testdokumente.anlegen(datenbank.get(),
                new Testdokumente.Angaben("urinet", "WISSENSQUELLE", "NORMAL", true, "probe", null, "ARCHIVIERT"));
        seite = UUID.randomUUID();
        try (Connection verbindung = datenbank.get().getConnection()) {
            ausfuehren(verbindung, """
                    insert into m_urinet.wissensseite (id, pfad, typ, titel, beschreibung, vertraulichkeit, inhalt,
                        version, erzeugt_von, erzeugt_am, freigegeben_von, freigegeben_am)
                    values (?, ?, 'Konzept', 'Probe', 'Probe', 'NORMAL', 'Unveränderter Inhalt', 1, 'probe',
                        now(), 'probe', now())
                    """, seite, "konzepte/quelle-" + seite);
            ausfuehren(verbindung, """
                    insert into m_urinet.wissensseite_quelle (seite_id, schluessel, dokument_id, seite_von)
                    values (?, 'q1', ?, 1)
                    """, seite, dokument);
            ausfuehren(verbindung, "insert into m_urinet.wissensseite_dokument (seite_id, dokument_id) values (?, ?)",
                    seite, dokument);
        }
        given().auth().oauth2(Testanmeldung.token("mitarbeiter-urinet"))
                .when().get("/api/v1/wissensseiten/" + seite).then().statusCode(200);
    }

    @Wenn("der Inhaber die Vertraulichkeit des Quelldokuments auf «{word}» ändert")
    public void quelleVertraulicher(String stufe) {
        String inhaber = Testanmeldung.token("inhaber-urinet");
        String pfad = "/api/v1/dokumente/" + dokument;
        String etag = given().auth().oauth2(inhaber).when().get(pfad).then().statusCode(200)
                .extract().header("ETag");
        given().auth().oauth2(inhaber).header("If-Match", etag).contentType("application/json")
                .body(Map.of("vertraulichkeit", stufe, "grund", "Synthetische Quellenprüfung"))
                .when().patch(pfad).then().statusCode(200);
    }

    @Dann("sieht der Mitarbeiter die Wissensseite nicht mehr")
    public void mitarbeiterSiehtSeiteNicht() {
        given().auth().oauth2(Testanmeldung.token("mitarbeiter-urinet"))
                .when().get("/api/v1/wissensseiten/" + seite).then().statusCode(404);
    }

    @Dann("der Inhaber sieht auf der unveränderten Wissensseite den Hinweis «Quelle prüfen»")
    public void inhaberSiehtPruefhinweis() {
        given().auth().oauth2(Testanmeldung.token("inhaber-urinet"))
                .when().get("/api/v1/wissensseiten/" + seite).then().statusCode(200)
                .body("quellePruefen", is(true)).body("version", is(1))
                .body("inhaltHtml", is("<p>Unveränderter Inhalt</p>\n"));
    }

    @Wenn("der Inhaber die Quellenprüfung der Wissensseite bestätigt")
    public void quellenpruefungBestaetigen() {
        String inhaber = Testanmeldung.token("inhaber-urinet");
        String pfad = "/api/v1/wissensseiten/" + seite;
        String etag = given().auth().oauth2(inhaber).when().get(pfad).then().statusCode(200)
                .extract().header("ETag");
        given().auth().oauth2(inhaber).header("If-Match", etag)
                .when().post(pfad + "/quelle-geprueft").then().statusCode(204);
    }

    @Dann("ist der Quellenhinweis entfernt und die Bestätigung protokolliert")
    public void bestaetigungProtokolliert() throws Exception {
        given().auth().oauth2(Testanmeldung.token("inhaber-urinet"))
                .when().get("/api/v1/wissensseiten/" + seite).then().statusCode(200)
                .body("quellePruefen", is(false)).body("version", is(1));
        try (Connection verbindung = datenbank.get().getConnection();
             PreparedStatement befehl = verbindung.prepareStatement("""
                     select count(*) from m_urinet.wissens_log
                     where aktion = 'UPDATE' and ? = any(seiten)
                       and angaben @> '{"quelle_geprueft":true}'::jsonb
                     """)) {
            befehl.setObject(1, seite);
            try (var ergebnis = befehl.executeQuery()) {
                assertThat(ergebnis.next()).isTrue();
                assertThat(ergebnis.getInt(1)).isEqualTo(1);
            }
        }
    }

    @After("@quellen-aenderung")
    public void aufraeumen() throws Exception {
        if (seite != null) {
            try (Connection verbindung = datenbank.get().getConnection()) {
                ausfuehren(verbindung, "delete from m_urinet.wissensseite where id = ?", seite);
            }
        }
        if (dokument != null) {
            Testdokumente.loeschen(datenbank.get(), "urinet", List.of(dokument));
        }
    }

    private static void ausfuehren(Connection verbindung, String sql, Object... werte) throws Exception {
        try (PreparedStatement befehl = verbindung.prepareStatement(sql)) {
            for (int i = 0; i < werte.length; i++) {
                befehl.setObject(i + 1, werte[i]);
            }
            befehl.executeUpdate();
        }
    }
}
