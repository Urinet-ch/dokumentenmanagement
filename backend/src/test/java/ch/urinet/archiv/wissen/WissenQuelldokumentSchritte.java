package ch.urinet.archiv.wissen;

import ch.urinet.archiv.dokument.Testdokumente;
import ch.urinet.archiv.dokument.application.DokumentEreignisse;
import ch.urinet.archiv.gemeinsam.Testanmeldung;
import ch.urinet.archiv.mandant.application.MandantKontext;
import ch.urinet.archiv.mandant.domain.MandantCode;
import io.agroal.api.AgroalDataSource;
import io.cucumber.java.After;
import io.cucumber.java.de.Angenommen;
import io.cucumber.java.de.Dann;
import io.cucumber.java.de.Wenn;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;

public class WissenQuelldokumentSchritte {
    @Inject
    Instance<AgroalDataSource> datenbank;
    @Inject
    DokumentEreignisse ereignisse;
    @Inject
    MandantKontext kontext;

    private UUID dokument;
    private UUID seite;
    private UUID entwurf;
    private String zustandVorher;

    @Angenommen("eine normale Wissensseite verweist auf ein normales Quelldokument")
    public void normaleQuelle() throws Exception {
        quelleMitStatus("ARCHIVIERT");
    }

    @Angenommen("eine normale Wissensseite verweist auf ein Quelldokument im Status «{word}»")
    public void quelleMitStatus(String status) throws Exception {
        dokument = Testdokumente.anlegen(datenbank.get(),
                new Testdokumente.Angaben("urinet", "PROJEKTDOKUMENT", "NORMAL", true, "probe", null, status));
        Testdokumente.alsWissensquelleMarkieren(datenbank.get(), "urinet", dokument);
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

    @Wenn("der Inhaber am Quelldokument die Änderung «{word}» auslöst")
    public void statusOderArtAendern(String aenderung) {
        String inhaber = Testanmeldung.token("inhaber-urinet");
        String pfad = "/api/v1/dokumente/" + dokument;
        String etag = given().auth().oauth2(inhaber).when().get(pfad).then().statusCode(200)
                .extract().header("ETag");
        switch (aenderung) {
            case "VERWERFEN" -> given().auth().oauth2(inhaber).header("If-Match", etag)
                    .contentType("application/json").body(Map.of("grund", "Synthetische Quellenprüfung"))
                    .when().post(pfad + "/verwerfen").then().statusCode(204);
            case "WIEDERHOLEN" -> given().auth().oauth2(inhaber).header("If-Match", etag)
                    .when().post(pfad + "/wiederholen").then().statusCode(202);
            case "ART" -> given().auth().oauth2(inhaber).header("If-Match", etag).contentType("application/json")
                    .body(Map.of("art", "PROJEKTDOKUMENT", "projekt", "Synthetisches Projekt",
                            "grund", "Synthetische Quellenprüfung"))
                    .when().patch(pfad).then().statusCode(200);
            default -> throw new IllegalArgumentException(aenderung);
        }
    }

    @Angenommen("die Wissensseite hat die Mindeststufe «INTERN»")
    public void mindeststufeIntern() throws Exception {
        try (Connection verbindung = datenbank.get().getConnection()) {
            ausfuehren(verbindung, "update m_urinet.wissensseite set vertraulichkeit_mindest = 'INTERN', "
                    + "vertraulichkeit = 'INTERN' where id = ?", seite);
        }
    }

    @Wenn("der Inhaber den Titel des Quelldokuments ändert")
    public void titelAendern() {
        String inhaber = Testanmeldung.token("inhaber-urinet");
        String pfad = "/api/v1/dokumente/" + dokument;
        String etag = given().auth().oauth2(inhaber).when().get(pfad).then().statusCode(200)
                .extract().header("ETag");
        given().auth().oauth2(inhaber).header("If-Match", etag).contentType("application/json")
                .body(Map.of("titel", "Neuer synthetischer Titel", "grund", "Quellenprüfung"))
                .when().patch(pfad).then().statusCode(200);
    }

    @Angenommen("das Quelldokument hat Volltext und eine PDF-A-Fassung")
    public void volltextUndPdfa() throws Exception {
        try (Connection verbindung = datenbank.get().getConnection()) {
            ausfuehren(verbindung, "update m_urinet.dokument set volltext = 'Seite 1' || chr(12), seiten = 1, "
                    + "s3_pdfa = 'archiv/probe.pdf', s3_version_pdfa = 'v1', pdfa_fehlt = false where id = ?", dokument);
        }
    }

    @Angenommen("der Zustand des Quelldokuments mit Dokumentzeile und Dateizeilen ist festgehalten")
    public void zustandFesthalten() throws Exception {
        zustandVorher = zustand();
    }

    @Dann("sind Dokumentzeile und Dateizeilen des Quelldokuments unverändert")
    public void zustandUnveraendert() throws Exception {
        assertThat(zustand()).isEqualTo(zustandVorher);
    }

    private String zustand() throws Exception {
        try (Connection verbindung = datenbank.get().getConnection();
             PreparedStatement befehl = verbindung.prepareStatement("""
                     select (select row_to_json(d)::text from m_urinet.dokument d where d.id = ?)
                         || '|' || coalesce((select string_agg(row_to_json(f)::text, '|' order by f.rolle)
                         from m_urinet.dokument_datei f where f.dokument_id = ?), '')
                     """)) {
            befehl.setObject(1, dokument);
            befehl.setObject(2, dokument);
            try (var ergebnis = befehl.executeQuery()) {
                assertThat(ergebnis.next()).isTrue();
                return ergebnis.getString(1);
            }
        }
    }

    @Wenn("der Inhaber eine Änderung dieser Wissensseite freigibt")
    public void seitenAenderungFreigeben() throws Exception {
        volltextUndPdfa();
        String inhaber = Testanmeldung.token("inhaber-urinet");
        Map<String, Object> daten = Map.of("seiteId", seite, "pfad", "konzepte/quelle-" + seite,
                "typ", "Konzept", "titel", "Probe", "beschreibung", "Ein Satz.", "inhalt", "Aussage.[^q1]",
                "quellen", List.of(Map.of("schluessel", "q1", "dokumentId", dokument,
                        "seiteVon", 1, "seiteBis", 1, "auszug", "Seite 1")));
        entwurf = UUID.fromString(given().auth().oauth2(inhaber).contentType("application/json").body(daten)
                .when().post("/api/v1/wissensentwuerfe").then().statusCode(201).extract().path("id"));
        given().auth().oauth2(inhaber).header("If-Match", "\"0\"").contentType("application/json").body("{}")
                .when().post("/api/v1/wissensentwuerfe/" + entwurf + "/aenderungen/1/annehmen")
                .then().statusCode(204);
    }

    @Dann("ist der Quellenhinweis durch die Freigabe entfernt")
    public void freigabeEntferntHinweis() {
        given().auth().oauth2(Testanmeldung.token("inhaber-urinet"))
                .when().get("/api/v1/wissensseiten/" + seite).then().statusCode(200)
                .body("quellePruefen", is(false)).body("version", is(2));
    }

    @Wenn("das Dokumentereignis vor einer späteren Löschung simuliert wird")
    public void loeschereignisSimulieren() throws Exception {
        CompletableFuture.runAsync(() -> kontext.mit(new MandantCode("urinet"),
                () -> QuarkusTransaction.requiringNew().run(() -> {
                    ereignisse.vorAenderung();
                    ereignisse.geaendert(dokument, Set.of("loeschung"));
                }))).get(1, TimeUnit.MINUTES);
    }

    @Dann("zeigt der Quelleneintrag den neuen Titel")
    public void quelleneintragZeigtTitel() {
        given().auth().oauth2(Testanmeldung.token("inhaber-urinet"))
                .when().get("/api/v1/wissensseiten/" + seite).then().statusCode(200)
                .body("quellen[0].dokumentTitel", is("Neuer synthetischer Titel"));
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
                if (entwurf != null) {
                    verbindung.setAutoCommit(false);
                    try (var befehl = verbindung.createStatement()) {
                        befehl.execute("set local session_replication_role = replica");
                    }
                    ausfuehren(verbindung, "delete from public.auftrag where typ = 'WISSEN_EMBEDDING' "
                            + "and nutzlast->>'seiteId' = ?", seite.toString());
                    ausfuehren(verbindung, "delete from m_urinet.wissensseite_version_dokument where seite_id = ?",
                            seite);
                    ausfuehren(verbindung, "delete from m_urinet.wissensseite_version where entwurf_id = ?", entwurf);
                    ausfuehren(verbindung, "delete from m_urinet.wissens_log where entwurf_id = ?", entwurf);
                    ausfuehren(verbindung, "delete from m_urinet.zugriff_protokoll where dokument_id = ?", entwurf);
                    ausfuehren(verbindung, "delete from m_urinet.wissens_aenderung where entwurf_id = ?", entwurf);
                    ausfuehren(verbindung, "delete from m_urinet.wissens_entwurf where id = ?", entwurf);
                    ausfuehren(verbindung, "delete from m_urinet.wissensseite_quelle where seite_id = ?", seite);
                    ausfuehren(verbindung, "delete from m_urinet.wissensseite_dokument where seite_id = ?", seite);
                    ausfuehren(verbindung, "delete from m_urinet.wissensseite_link where von_seite_id = ?", seite);
                }
                ausfuehren(verbindung, "delete from m_urinet.wissensseite where id = ?", seite);
                if (entwurf != null) {
                    verbindung.commit();
                }
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
