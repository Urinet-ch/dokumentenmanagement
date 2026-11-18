package ch.urinet.archiv.wissen;

import ch.urinet.archiv.gemeinsam.Testanmeldung;
import ch.urinet.archiv.sicherheit.application.ApiTokenVerwaltung;
import ch.urinet.archiv.sicherheit.application.WebseitenSchluesselVerwaltung;
import ch.urinet.archiv.sicherheit.domain.ApiToken;
import io.agroal.api.AgroalDataSource;
import io.cucumber.java.After;
import io.cucumber.java.de.Angenommen;
import io.cucumber.java.de.Dann;
import io.cucumber.java.de.Wenn;
import io.restassured.response.Response;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Lesen der Wissensbasis über REST (Aufgabe 13.6). Anders als die SQL-Szenarien von 13.5 (Rollback) committet die
 * Einrichtung, weil die REST-Abrufe eigene Transaktionen öffnen. Die Zeilen in den append-only Tabellen
 * ({@code wissensseite_version}, {@code wissensseite_version_dokument}, {@code wissens_log}) lassen sich danach nicht
 * mehr löschen; darum sind alle Pfade, Titel und Tags per {@code kennung} eindeutig (nie {@code ueberblick}), und die
 * Testdokumente stehen auf {@code VERWORFEN}, damit sie Export, Suche, Chat und Prüfungen der Belege nicht berühren.
 * Die Sicht der Wissensbasis hängt nur von Vertraulichkeit und Bestätigung ab, nicht vom Status.
 */
public class WissenLesenSchritte {

    private static final String SCHEMA = "m_urinet";
    private static final String ANDERER_SCHEMA = "m_vhf_gsk";
    private static final String MITARBEITER = "mitarbeiter-urinet";

    @Inject
    Instance<AgroalDataSource> datenbank;

    @Inject
    ApiTokenVerwaltung tokenVerwaltung;

    @Inject
    WebseitenSchluesselVerwaltung webseitenSchluessel;

    private final UUID normaleQuelle = UUID.randomUUID();
    private final UUID interneQuelle = UUID.randomUUID();
    private final UUID wissensquelle = UUID.randomUUID();
    private final UUID entwurf = UUID.randomUUID();
    private final UUID internerEntwurf = UUID.randomUUID();
    private final UUID normaleSeite = UUID.randomUUID();
    private final UUID zweiteNormaleSeite = UUID.randomUUID();
    private final UUID interneSeite = UUID.randomUUID();
    private final UUID quellenSeite = UUID.randomUUID();
    private final UUID verweisendeSeite = UUID.randomUUID();
    private final String kennung = UUID.randomUUID().toString();
    private final String tag = "leseprobe-" + kennung;
    private OffsetDateTime probeBeginn;
    private String normaleSeitePfad;
    private String interneSeiteTitel;
    private Response indexErsteSeite;
    private Response indexZweiteSeite;
    private Response seite;
    private Response seitePerPfad;
    private Response interneSeiteAntwort;
    private Response versionen;
    private Response sichtbareVersionAntwort;
    private Response interneVersion;
    private Response diff;
    private Response eingehend;
    private Response log;
    private Response suche;
    private Response positiverDiff;
    private Response versionMitDaten;
    private Response versionenMitDaten;
    private Response seiteTreuhaender;
    private long pfadAnzeigenVorher;
    private long pfadAnzeigenNachher;
    private long versionVerweigertVorher;
    private long versionVerweigertNachher;
    private final List<String> tokenIds = new ArrayList<>();
    private final Map<String, Response> tokenAntworten = new LinkedHashMap<>();

    @Angenommen("synthetische Wissensleseproben sind angelegt")
    public void probeAnlegen() throws Exception {
        probeBeginn = OffsetDateTime.now(ZoneOffset.UTC);
        normaleSeitePfad = "konzepte/leseprobe-" + kennung;
        interneSeiteTitel = "Interne Leseprobe " + kennung;
        String zweiterPfad = "konzepte/leseprobe-zweit-" + kennung;
        String quellenPfad = "quellen/leseprobe-" + kennung;
        String verweisPfad = "konzepte/leseprobe-verweis-" + kennung;
        try (Connection verbindung = datenbank.get().getConnection()) {
            verbindung.setAutoCommit(true);
            dokument(verbindung, SCHEMA, normaleQuelle, "NORMAL");
            dokument(verbindung, SCHEMA, interneQuelle, "INTERN");
            dokument(verbindung, SCHEMA, wissensquelle, "NORMAL");
            String zweiterInhaltAlt = "Einleitung\nAlte Aussage";
            String zweiterInhaltNeu = "Einleitung\nNeue Aussage mit [" + interneSeiteTitel
                    + "](/konzepte/leseprobe-intern-" + kennung + ") und [Normale Leseprobe](/" + normaleSeitePfad + ")";
            seite(verbindung, SCHEMA, normaleSeite, normaleSeitePfad, "Konzept", "Normale Leseprobe " + kennung, tag,
                    "NORMAL", null);
            seite(verbindung, SCHEMA, zweiteNormaleSeite, zweiterPfad, "Konzept", "Zweite Leseprobe " + kennung, tag,
                    "NORMAL", null);
            seite(verbindung, SCHEMA, interneSeite, "konzepte/leseprobe-intern-" + kennung, "Konzept",
                    interneSeiteTitel, tag, "INTERN", null);
            seite(verbindung, SCHEMA, quellenSeite, quellenPfad, "Quelle", "Quellseite " + kennung, tag, "NORMAL",
                    wissensquelle);
            seite(verbindung, SCHEMA, verweisendeSeite, verweisPfad, "Konzept", "Verweisende Seite " + kennung, tag,
                    "NORMAL", null);
            veraltetMachen(verbindung, SCHEMA, verweisendeSeite);
            dokument(verbindung, ANDERER_SCHEMA, interneQuelle, "INTERN");
            seite(verbindung, ANDERER_SCHEMA, normaleSeite, normaleSeitePfad, "Konzept",
                    "Fremdmandant " + kennung, tag, "NORMAL", null);
            huelle(verbindung, ANDERER_SCHEMA, normaleSeite, interneQuelle);
            quelle(verbindung, normaleSeite, "normal", normaleQuelle);
            quelle(verbindung, interneSeite, "intern", interneQuelle);
            quelle(verbindung, quellenSeite, "quelle", wissensquelle);
            quelle(verbindung, verweisendeSeite, "quelle", wissensquelle);
            huelle(verbindung, SCHEMA, normaleSeite, normaleQuelle);
            huelle(verbindung, SCHEMA, interneSeite, interneQuelle);
            huelle(verbindung, SCHEMA, quellenSeite, wissensquelle);
            huelle(verbindung, SCHEMA, verweisendeSeite, wissensquelle);
            entwurf(verbindung, SCHEMA, entwurf, normaleQuelle);
            entwurf(verbindung, SCHEMA, internerEntwurf, interneQuelle);
            version(verbindung, normaleSeite, 1, "{\"vertraulichkeit_mindest\":\"NORMAL\"}", null,
                    "Synthetischer Inhalt");
            version(verbindung, normaleSeite, 2, "{\"vertraulichkeit_mindest\":\"NORMAL\"}", entwurf,
                    "Synthetischer Inhalt");
            versionsHuelle(verbindung, normaleSeite, 1, interneQuelle);
            versionsHuelle(verbindung, normaleSeite, 2, normaleQuelle);
            version(verbindung, zweiteNormaleSeite, 1, "{\"vertraulichkeit_mindest\":\"NORMAL\","
                    + "\"titel\":\"Alte Leseprobe\",\"tags\":[\"" + tag + "\"],"
                    + "\"quellen\":[{\"quell_seite_id\":\"" + interneSeite + "\","
                    + "\"auszug\":\"geheimer_auszug_" + kennung + "\"}],"
                    + "\"unbekannt\":\"geheime_daten_" + kennung + "\"}", null, zweiterInhaltAlt);
            version(verbindung, zweiteNormaleSeite, 2, "{\"vertraulichkeit_mindest\":\"NORMAL\"}", null,
                    zweiterInhaltNeu);
            versionsHuelle(verbindung, zweiteNormaleSeite, 1, normaleQuelle);
            versionsHuelle(verbindung, zweiteNormaleSeite, 2, normaleQuelle);
            log(verbindung, new UUID[]{normaleSeite, interneSeite}, "synthetic_log_payload_" + kennung, null);
            log(verbindung, new UUID[]{interneSeite}, "hidden_log_payload_" + kennung, null);
            log(verbindung, new UUID[]{normaleSeite}, "visible_draft_" + kennung, entwurf);
            log(verbindung, new UUID[]{normaleSeite}, "hidden_draft_" + kennung, internerEntwurf);
            inhalt(verbindung, SCHEMA, normaleSeite, "Aussage.[^q1]\n"
                    + "[Interne Seite](/konzepte/leseprobe-intern-" + kennung + ") "
                    + "[Fehlende Seite](/konzepte/leseprobe-fehlt-" + kennung + ") "
                    + "[Sichtbare Seite](/" + zweiterPfad + ")");
        }
    }

    @Wenn("«mitarbeiter» Index, Seite, Versionen, Diff, Links und Log abruft")
    public void lesen() {
        indexErsteSeite = get("/api/v1/wissensseiten?typ=Konzept&tag=" + tag + "&groesse=1&offset=0")
                .then().extract().response();
        indexZweiteSeite = get("/api/v1/wissensseiten?typ=Konzept&tag=" + tag + "&groesse=1&offset=1")
                .then().extract().response();
        seite = get("/api/v1/wissensseiten/" + normaleSeite)
                .then().extract().response();
        pfadAnzeigenVorher = protokollZeilen("WISSEN_ANZEIGEN", normaleSeite);
        seitePerPfad = get("/api/v1/wissensseiten?pfad=" + normaleSeitePfad)
                .then().extract().response();
        pfadAnzeigenNachher = protokollZeilen("WISSEN_ANZEIGEN", normaleSeite);
        interneSeiteAntwort = get("/api/v1/wissensseiten/" + interneSeite)
                .then().extract().response();
        versionen = get("/api/v1/wissensseiten/" + normaleSeite + "/versionen")
                .then().extract().response();
        sichtbareVersionAntwort = get("/api/v1/wissensseiten/" + normaleSeite + "/versionen/2")
                .then().extract().response();
        versionVerweigertVorher = verweigertZeilen();
        interneVersion = get("/api/v1/wissensseiten/" + normaleSeite + "/versionen/1")
                .then().extract().response();
        versionVerweigertNachher = verweigertZeilen();
        diff = get("/api/v1/wissensseiten/" + normaleSeite + "/diff?von=1&bis=2")
                .then().extract().response();
        eingehend = get("/api/v1/wissensseiten/" + quellenSeite + "/eingehend")
                .then().extract().response();
        log = get("/api/v1/wissensbasis/log?groesse=100")
                .then().extract().response();
        suche = get("/api/v1/wissensseiten?q=Normale&tag=" + tag).then().extract().response();
        positiverDiff = get("/api/v1/wissensseiten/" + zweiteNormaleSeite + "/diff?von=1&bis=2")
                .then().extract().response();
        versionMitDaten = get("/api/v1/wissensseiten/" + zweiteNormaleSeite + "/versionen/1")
                .then().extract().response();
        versionenMitDaten = get("/api/v1/wissensseiten/" + zweiteNormaleSeite + "/versionen")
                .then().extract().response();
        seiteTreuhaender = given().auth().oauth2(Testanmeldung.token("treuhaender-urinet"))
                .when().get("/api/v1/wissensseiten/" + normaleSeite).andReturn();
    }

    @Wenn("ein API-Token und ein Webseiten-Schlüssel die Wissensbasis lesen wollen")
    public void tokenLesen() {
        String generator = tokenVerwaltung.erstellen("urinet", "Probe Wissen " + kennung.substring(0, 8),
                "generator").token().text();
        tokenIds.add(ApiToken.lesen(generator).orElseThrow().id());
        String webseite = webseitenSchluessel.erstellen("urinet", "probe-wissen-" + kennung.substring(0, 8),
                "lesen", "oeffentlich", false).token().text();
        tokenIds.add(ApiToken.lesen(webseite).orElseThrow().id());
        Map<String, String> tokens = Map.of("generator", generator, "webseite-lesen", webseite);
        tokens.forEach((name, text) -> {
            for (String pfad : List.of("/api/v1/wissensseiten?tag=" + tag, "/api/v1/wissensseiten/" + normaleSeite,
                    "/api/v1/wissensbasis/log")) {
                tokenAntworten.put(name + " " + pfad, given().header("Authorization", "Bearer " + text)
                        .when().get(pfad).andReturn());
            }
        });
    }

    @Dann("erhalten beide 403 ohne Inhalt der Wissensbasis")
    public void tokenAbgewiesen() {
        assertThat(tokenAntworten).hasSize(6);
        tokenAntworten.forEach((anfrage, antwort) -> {
            assertThat(antwort.statusCode()).as(anfrage).isEqualTo(403);
            assertThat(antwort.body().asString()).as(anfrage).doesNotContain(kennung);
        });
    }

    @After
    public void aufraeumen() throws Exception {
        for (String id : tokenIds) {
            try (Connection verbindung = datenbank.get().getConnection();
                 PreparedStatement befehl = verbindung.prepareStatement("delete from public.api_client where id = ?")) {
                befehl.setString(1, id);
                befehl.executeUpdate();
            }
        }
        tokenIds.clear();
    }

    @Dann("verraten Zähler, Seiten, Versionen, Links und Log keine unsichtbaren Daten")
    public void sichtbarkeitGeprueft() throws Exception {
        indexErsteSeite.then().statusCode(200);
        indexZweiteSeite.then().statusCode(200);
        assertThat(indexErsteSeite.jsonPath().getLong("gesamt")).isEqualTo(3);
        assertThat(indexZweiteSeite.jsonPath().getLong("gesamt")).isEqualTo(3);
        assertThat(indexErsteSeite.jsonPath().getList("gruppen.find { it.typ == 'Konzept' }.seiten"))
                .hasSize(1);
        assertThat(indexZweiteSeite.jsonPath().getList("gruppen.find { it.typ == 'Konzept' }.seiten"))
                .hasSize(1);
        assertThat((String) indexZweiteSeite.jsonPath()
                .getMap("gruppen.find { it.typ == 'Konzept' }.seiten[0]").get("status")).isEqualTo("VERALTET");

        seite.then().statusCode(200).header("ETag", "\"" + 2 + "\"");
        assertThat(seite.jsonPath().getString("titel")).isEqualTo("Normale Leseprobe " + kennung);
        seitePerPfad.then().statusCode(200).header("ETag", "\"" + 2 + "\"");
        assertThat(seitePerPfad.jsonPath().getString("id")).isEqualTo(normaleSeite.toString());
        assertThat(pfadAnzeigenNachher - pfadAnzeigenVorher).isEqualTo(1);
        seiteTreuhaender.then().statusCode(200);
        assertThat(seiteTreuhaender.jsonPath().getString("titel")).isEqualTo("Normale Leseprobe " + kennung);
        interneSeiteAntwort.then().statusCode(404);
        assertThat(seite.body().asString()).doesNotContain(interneSeiteTitel);
        assertThat(seite.jsonPath().getList("quellen.dokumentId", String.class))
                .containsExactly(normaleQuelle.toString());

        versionen.then().statusCode(200);
        List<Map<String, Object>> versionZeilen = versionen.jsonPath().getList("versionen");
        assertThat(versionZeilen).hasSize(2);
        Map<String, Object> versteckteVersion = versionZeilen.stream()
                .filter(row -> ((Number) row.get("nummer")).intValue() == 1).findFirst().orElseThrow();
        assertThat(versteckteVersion).containsOnlyKeys("nummer");
        Map<String, Object> sichtbareVersionZeile = versionZeilen.stream()
                .filter(row -> ((Number) row.get("nummer")).intValue() == 2).findFirst().orElseThrow();
        assertThat(sichtbareVersionZeile).containsEntry("erzeugtVon", "human:probe")
                .containsEntry("freigegebenVon", "human:probe")
                .containsEntry("entwurfId", entwurf.toString())
                .containsEntry("entwurfAnlass", "INGEST")
                .containsEntry("dokumentId", normaleQuelle.toString());
        sichtbareVersionAntwort.then().statusCode(200);
        assertThat(sichtbareVersionAntwort.jsonPath().getString("entwurfId")).isEqualTo(entwurf.toString());
        assertThat(sichtbareVersionAntwort.jsonPath().getString("entwurfAnlass")).isEqualTo("INGEST");
        assertThat(sichtbareVersionAntwort.jsonPath().getString("dokumentId"))
                .isEqualTo(normaleQuelle.toString());
        interneVersion.then().statusCode(404);
        assertThat(versionVerweigertNachher - versionVerweigertVorher).isEqualTo(1);
        diff.then().statusCode(404);

        suche.then().statusCode(200);
        assertThat(suche.jsonPath().getList("gruppen.seiten.flatten().titel", String.class))
                .containsExactly("Normale Leseprobe " + kennung);

        positiverDiff.then().statusCode(200);
        assertThat(positiverDiff.jsonPath().getList("vorher", String.class)).containsExactly("Einleitung");
        assertThat(positiverDiff.jsonPath().getList("hinzugefuegt", String.class)).singleElement()
                .asString().contains("Seite nicht vorhanden", "[Normale Leseprobe](/" + normaleSeitePfad + ")");
        assertThat(positiverDiff.body().asString())
                .doesNotContain(interneSeiteTitel, "leseprobe-intern-" + kennung);

        versionMitDaten.then().statusCode(200);
        assertThat(versionMitDaten.jsonPath().getMap("daten")).containsOnlyKeys("vertraulichkeit_mindest", "titel",
                "tags");
        for (Response antwort : List.of(versionMitDaten, versionenMitDaten)) {
            antwort.then().statusCode(200);
            assertThat(antwort.body().asString()).doesNotContain("geheime_daten_" + kennung,
                    "geheimer_auszug_" + kennung, interneSeite.toString());
        }

        eingehend.then().statusCode(200);
        assertThat(eingehend.jsonPath().getList("pfad", String.class)).contains("konzepte/leseprobe-verweis-" + kennung);

        log.then().statusCode(200);
        String logText = log.body().asString();
        assertThat(logText).contains("Normale Leseprobe " + kennung)
                .doesNotContain(interneSeiteTitel, "synthetic_log_payload_" + kennung,
                        "hidden_log_payload_" + kennung);
        assertThat(logText).doesNotContain(interneSeite.toString(), internerEntwurf.toString())
                .contains(entwurf.toString());
        for (Response antwort : List.of(indexErsteSeite, indexZweiteSeite, seite, seitePerPfad, versionen,
                sichtbareVersionAntwort, eingehend, log, suche, positiverDiff, versionMitDaten, versionenMitDaten,
                seiteTreuhaender)) {
            assertThat(antwort.body().asString()).doesNotContain("Fremdmandant " + kennung);
        }
        assertThat(seite.jsonPath().getString("inhaltHtml")).contains("Sichtbare Seite", "Seite nicht vorhanden")
                .doesNotContain("Interne Seite", "Fehlende Seite");
        zugriffsprotokollPruefen();
    }

    private Response get(String path) {
        return given().auth().oauth2(Testanmeldung.token(MITARBEITER)).when().get(path).andReturn();
    }

    /** Verweigerungen der Wissensbasis tragen keine Seiten-ID (Berechtigung.wissensseitenAktion). */
    private long verweigertZeilen() {
        try (Connection verbindung = datenbank.get().getConnection();
             PreparedStatement zaehlen = verbindung.prepareStatement("""
                     select count(*) from m_urinet.zugriff_protokoll
                     where aktion = 'VERWEIGERT' and rolle = 'MITARBEITER' and details ->> 'grund' = 'A15'
                       and details ->> 'fehlercode' = 'NICHT_GEFUNDEN' and zeit >= ?
                     """)) {
            zaehlen.setObject(1, probeBeginn);
            try (var zeilen = zaehlen.executeQuery()) {
                assertThat(zeilen.next()).isTrue();
                return zeilen.getLong(1);
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private long protokollZeilen(String aktion, UUID dokumentId) {
        try (Connection verbindung = datenbank.get().getConnection();
             PreparedStatement zaehlen = verbindung.prepareStatement("""
                     select count(*) from m_urinet.zugriff_protokoll
                     where aktion = ? and dokument_id = ? and zeit >= ?
                     """)) {
            zaehlen.setString(1, aktion);
            zaehlen.setObject(2, dokumentId);
            zaehlen.setObject(3, probeBeginn);
            try (var zeilen = zaehlen.executeQuery()) {
                assertThat(zeilen.next()).isTrue();
                return zeilen.getLong(1);
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private void zugriffsprotokollPruefen() throws Exception {
        try (Connection verbindung = datenbank.get().getConnection();
             PreparedStatement angezeigt = verbindung.prepareStatement("""
                     select count(*) from m_urinet.zugriff_protokoll
                     where aktion = 'WISSEN_ANZEIGEN' and dokument_id = ? and zeit >= ?
                     """);
             PreparedStatement verweigert = verbindung.prepareStatement("""
                     select count(*) from m_urinet.zugriff_protokoll
                     where aktion = 'VERWEIGERT' and rolle = 'MITARBEITER'
                       and details ->> 'fehlercode' = 'NICHT_GEFUNDEN' and zeit >= ?
                     """);
             PreparedStatement details = verbindung.prepareStatement("""
                     select coalesce(string_agg(details::text, ' '), '')
                     from m_urinet.zugriff_protokoll
                     where aktion in ('WISSEN_ANZEIGEN', 'VERWEIGERT') and zeit >= ?
                     """);
             PreparedStatement listen = verbindung.prepareStatement("""
                     select count(*) from m_urinet.zugriff_protokoll
                     where aktion = 'WISSEN_ANZEIGEN' and dokument_id is null and zeit >= ?
                     """)) {
            angezeigt.setObject(1, normaleSeite);
            angezeigt.setObject(2, probeBeginn);
            verweigert.setObject(1, probeBeginn);
            details.setObject(1, probeBeginn);
            listen.setObject(1, probeBeginn);
            try (var zeilen = angezeigt.executeQuery()) {
                assertThat(zeilen.next()).isTrue();
                assertThat(zeilen.getLong(1)).isPositive();
            }
            try (var zeilen = verweigert.executeQuery()) {
                assertThat(zeilen.next()).isTrue();
                assertThat(zeilen.getLong(1)).isPositive();
            }
            try (var zeilen = details.executeQuery()) {
                assertThat(zeilen.next()).isTrue();
                assertThat(zeilen.getString(1)).doesNotContain("synthetic_log_payload_", "hidden_log_payload_",
                        interneSeiteTitel, "Normale Leseprobe " + kennung);
            }
            try (var zeilen = listen.executeQuery()) {
                assertThat(zeilen.next()).isTrue();
                assertThat(zeilen.getLong(1)).isZero();
            }
        }
    }

    private static void dokument(Connection verbindung, String schema, UUID id, String vertraulichkeit)
            throws Exception {
        try (PreparedStatement query = verbindung.prepareStatement("""
                insert into %s.dokument (id, quelle, vertraulichkeit, erstellt_von, status, bestaetigt,
                                                bestaetigt_von)
                values (?, 'UPLOAD', ?, 'synthetisch', 'VERWORFEN', ?, 'inhaber')
                """.formatted(schema))) {
            query.setObject(1, id);
            query.setString(2, vertraulichkeit);
            query.setObject(3, OffsetDateTime.now(ZoneOffset.UTC));
            query.executeUpdate();
        }
    }

    private static void seite(Connection verbindung, String schema, UUID id, String pfad, String typ, String titel,
                              String tag, String vertraulichkeit, UUID wissensquelleId) throws Exception {
        try (PreparedStatement query = verbindung.prepareStatement("""
                insert into %s.wissensseite
                    (id, pfad, typ, titel, beschreibung, tags, vertraulichkeit, vertraulichkeit_mindest, inhalt,
                     version, erzeugt_von, erzeugt_am, freigegeben_von, freigegeben_am, wissensquelle_id)
                values (?, ?, ?, ?, 'Synthetische Beschreibung', array[?]::text[], ?, ?, 'Aussage.[^q1]', 2,
                        'human:probe', ?, 'human:probe', ?, ?)
                """.formatted(schema))) {
            OffsetDateTime zeit = OffsetDateTime.now(ZoneOffset.UTC);
            query.setObject(1, id);
            query.setString(2, pfad);
            query.setString(3, typ);
            query.setString(4, titel);
            query.setString(5, tag);
            query.setString(6, vertraulichkeit);
            query.setString(7, vertraulichkeit);
            query.setObject(8, zeit);
            query.setObject(9, zeit);
            query.setObject(10, wissensquelleId);
            query.executeUpdate();
        }
    }

    private static void quelle(Connection verbindung, UUID seite, String schluessel, UUID dokument) throws Exception {
        try (PreparedStatement query = verbindung.prepareStatement("""
                insert into m_urinet.wissensseite_quelle (seite_id, schluessel, dokument_id, seite_von, auszug)
                values (?, ?, ?, 1, 'synthetischer Auszug')
                """)) {
            query.setObject(1, seite);
            query.setString(2, schluessel);
            query.setObject(3, dokument);
            query.executeUpdate();
        }
    }

    private static void huelle(Connection verbindung, String schema, UUID seite, UUID dokument) throws Exception {
        try (PreparedStatement query = verbindung.prepareStatement("""
                insert into %s.wissensseite_dokument (seite_id, dokument_id) values (?, ?)
                """.formatted(schema))) {
            query.setObject(1, seite);
            query.setObject(2, dokument);
            query.executeUpdate();
        }
    }

    private static void version(Connection verbindung, UUID seite, int nummer, String daten, UUID entwurf,
                                String inhalt) throws Exception {
        try (PreparedStatement query = verbindung.prepareStatement("""
                insert into m_urinet.wissensseite_version
                    (seite_id, version, daten, inhalt, erzeugt_von, freigegeben_von, zeit, entwurf_id)
                values (?, ?, cast(? as jsonb), ?, 'human:probe', 'human:probe', ?, ?)
                """)) {
            query.setObject(1, seite);
            query.setInt(2, nummer);
            query.setString(3, daten);
            query.setString(4, inhalt);
            query.setObject(5, OffsetDateTime.now(ZoneOffset.UTC));
            query.setObject(6, entwurf);
            query.executeUpdate();
        }
    }

    private static void entwurf(Connection verbindung, String schema, UUID id, UUID dokument) throws Exception {
        try (PreparedStatement query = verbindung.prepareStatement("""
                insert into %s.wissens_entwurf
                    (id, anlass, dokument_id, status, erstellt_von, erstellt)
                values (?, 'INGEST', ?, 'ERLEDIGT', 'human:probe', ?)
                """.formatted(schema))) {
            query.setObject(1, id);
            query.setObject(2, dokument);
            query.setObject(3, OffsetDateTime.now(ZoneOffset.UTC));
            query.executeUpdate();
        }
    }

    private static void inhalt(Connection verbindung, String schema, UUID seite, String inhalt) throws Exception {
        try (PreparedStatement query = verbindung.prepareStatement("""
                update %s.wissensseite set inhalt = ? where id = ?
                """.formatted(schema))) {
            query.setString(1, inhalt);
            query.setObject(2, seite);
            query.executeUpdate();
        }
    }

    private static void veraltetMachen(Connection verbindung, String schema, UUID seite) throws Exception {
        try (PreparedStatement query = verbindung.prepareStatement("""
                update %s.wissensseite set status = 'VERALTET', veraltet_ab = date '2026-01-01' where id = ?
                """.formatted(schema))) {
            query.setObject(1, seite);
            query.executeUpdate();
        }
    }

    private static void versionsHuelle(Connection verbindung, UUID seite, int nummer, UUID dokument)
            throws Exception {
        try (PreparedStatement query = verbindung.prepareStatement("""
                insert into m_urinet.wissensseite_version_dokument (seite_id, version, dokument_id)
                values (?, ?, ?)
                """)) {
            query.setObject(1, seite);
            query.setInt(2, nummer);
            query.setObject(3, dokument);
            query.executeUpdate();
        }
    }

    private void log(Connection verbindung, UUID[] seiten, String angaben, UUID entwurfId) throws Exception {
        String uuidArray = "{" + String.join(",", java.util.Arrays.stream(seiten).map(UUID::toString).toList()) + "}";
        try (PreparedStatement query = verbindung.prepareStatement("""
                insert into m_urinet.wissens_log (zeit, aktion, akteur, seiten, angaben, entwurf_id)
                values (?, 'UPDATE', ?, cast(? as uuid[]), cast(? as jsonb), ?)
                """)) {
            query.setObject(1, OffsetDateTime.now(ZoneOffset.UTC));
            query.setString(2, "human:probe-" + kennung);
            query.setString(3, uuidArray);
            query.setString(4, "{\"probe\":\"" + angaben + "\"}");
            query.setObject(5, entwurfId);
            query.executeUpdate();
        }
    }
}
