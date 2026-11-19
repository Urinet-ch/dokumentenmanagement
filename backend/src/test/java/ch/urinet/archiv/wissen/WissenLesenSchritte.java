package ch.urinet.archiv.wissen;

import ch.urinet.archiv.gemeinsam.Testanmeldung;
import io.agroal.api.AgroalDataSource;
import io.cucumber.java.de.Angenommen;
import io.cucumber.java.de.Dann;
import io.cucumber.java.de.Wenn;
import io.restassured.response.Response;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

public class WissenLesenSchritte {

    private static final String SCHEMA = "m_urinet";
    private static final String ANDERER_SCHEMA = "m_vhf_gsk";
    private static final String MITARBEITER = "mitarbeiter-urinet";

    @Inject
    Instance<AgroalDataSource> datenbank;

    private final UUID normaleQuelle = UUID.randomUUID();
    private final UUID interneQuelle = UUID.randomUUID();
    private final UUID wissensquelle = UUID.randomUUID();
    private final UUID entwurf = UUID.randomUUID();
    private final UUID interneEntwurf = UUID.randomUUID();
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
    private Response dokumentIndex;
    private Response unsichtbarerDokumentIndex;
    private Response fremderDokumentIndex;
    private Response ungueltigerDokumentIndex;
    private Response seite;
    private Response seitePerPfad;
    private Response interneSeiteAntwort;
    private Response versionen;
    private Response sichtbareVersionAntwort;
    private Response interneVersion;
    private Response diff;
    private Response sichtbarerDiff;
    private Response eingehend;
    private Response log;
    private Response logErsteSeite;
    private Response logZweiteSeite;
    private List<Response> ungueltigeCursor;
    private List<Response> ungueltigeIds;

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
            quelle(verbindung, ANDERER_SCHEMA, normaleSeite, "intern", interneQuelle);
            quelle(verbindung, normaleSeite, "normal", normaleQuelle);
            quelle(verbindung, interneSeite, "intern", interneQuelle);
            quelle(verbindung, quellenSeite, "quelle", wissensquelle);
            quelle(verbindung, verweisendeSeite, "quelle", wissensquelle);
            try (PreparedStatement query = verbindung.prepareStatement(
                    "update m_urinet.wissensseite set quelle_pruefen = true where id = ?")) {
                query.setObject(1, normaleSeite);
                query.executeUpdate();
            }
            huelle(verbindung, SCHEMA, normaleSeite, normaleQuelle);
            huelle(verbindung, SCHEMA, interneSeite, interneQuelle);
            huelle(verbindung, SCHEMA, quellenSeite, wissensquelle);
            huelle(verbindung, SCHEMA, verweisendeSeite, wissensquelle);
            entwurf(verbindung, SCHEMA, entwurf, normaleQuelle);
            entwurf(verbindung, SCHEMA, interneEntwurf, interneQuelle);
            String normal = "{\"vertraulichkeit_mindest\":\"NORMAL\"}";
            version(verbindung, normaleSeite, 1, normal, null, "Synthetischer Inhalt");
            version(verbindung, normaleSeite, 2, normal, entwurf, "Kopf\nAlte Aussage\nFuss");
            version(verbindung, normaleSeite, 3, normal, null, "Kopf\nNeue Aussage\nFuss");
            versionsHuelle(verbindung, normaleSeite, 1, interneQuelle);
            versionsHuelle(verbindung, normaleSeite, 2, normaleQuelle);
            versionsHuelle(verbindung, normaleSeite, 3, normaleQuelle);
            aktuelleVersion(verbindung, normaleSeite, 3);
            log(verbindung, SCHEMA, new UUID[]{normaleSeite, interneSeite}, "synthetic_log_payload_" + kennung,
                    "human:probe-a-" + kennung, entwurf);
            log(verbindung, SCHEMA, new UUID[]{normaleSeite}, "intern_entwurf_payload_" + kennung,
                    "human:probe-b-" + kennung, interneEntwurf);
            log(verbindung, SCHEMA, new UUID[]{interneSeite}, "hidden_log_payload_" + kennung,
                    "human:probe-c-" + kennung, null);
            log(verbindung, ANDERER_SCHEMA, new UUID[]{normaleSeite}, "fremd_log_payload_" + kennung,
                    "human:fremd-" + kennung, null);
            inhalt(verbindung, SCHEMA, normaleSeite, "Aussage.[^q1]\n"
                    + "[Interne Seite](/konzepte/leseprobe-intern-" + kennung + ") "
                    + "[Fehlende Seite](/konzepte/leseprobe-fehlt-" + kennung + ") "
                    + "[Sichtbare Seite](/" + zweiterPfad + ")");
        }
    }

    @Wenn("die Wissensseiten nach einem Quelldokument gefiltert werden")
    public void dokumentIndexLesen() {
        dokumentIndex = get("/api/v1/wissensseiten?tag=" + tag + "&dokumentId=" + normaleQuelle);
        unsichtbarerDokumentIndex = get("/api/v1/wissensseiten?tag=" + tag + "&dokumentId=" + interneQuelle);
        fremderDokumentIndex = given().auth().oauth2(Testanmeldung.token("inhaber-vhf-gsk")).when()
                .get("/api/v1/wissensseiten?tag=" + tag + "&dokumentId=" + interneQuelle).andReturn();
        ungueltigerDokumentIndex = get("/api/v1/wissensseiten?dokumentId=keine-uuid");
    }

    @Dann("zeigt der Index nur sichtbare Seiten des eigenen Mandanten und deren Prüfhinweis")
    public void dokumentIndexPruefen() {
        dokumentIndex.then().statusCode(200);
        assertThat(dokumentIndex.jsonPath().getLong("gesamt")).isEqualTo(1);
        assertThat(dokumentIndex.jsonPath().getBoolean("gruppen.find { it.typ == 'Konzept' }.seiten[0].quellePruefen"))
                .isTrue();
        assertThat(dokumentIndex.body().asString()).contains(normaleSeitePfad).doesNotContain(interneSeiteTitel);
        unsichtbarerDokumentIndex.then().statusCode(200);
        assertThat(unsichtbarerDokumentIndex.jsonPath().getLong("gesamt")).isZero();
        fremderDokumentIndex.then().statusCode(200);
        assertThat(fremderDokumentIndex.jsonPath().getLong("gesamt")).isEqualTo(1);
        assertThat(fremderDokumentIndex.body().asString()).contains("Fremdmandant " + kennung)
                .doesNotContain("Normale Leseprobe " + kennung);
        ungueltigerDokumentIndex.then().statusCode(400);
    }

    @Wenn("«mitarbeiter» Index, Seite, Versionen, Diff, Links und Log abruft")
    public void lesen() {
        indexErsteSeite = get("/api/v1/wissensseiten?typ=Konzept&tag=" + tag + "&groesse=1&offset=0")
                .then().extract().response();
        indexZweiteSeite = get("/api/v1/wissensseiten?typ=Konzept&tag=" + tag + "&groesse=1&offset=1")
                .then().extract().response();
        seite = get("/api/v1/wissensseiten/" + normaleSeite)
                .then().extract().response();
        seitePerPfad = get("/api/v1/wissensseiten?pfad=" + normaleSeitePfad)
                .then().extract().response();
        interneSeiteAntwort = get("/api/v1/wissensseiten/" + interneSeite)
                .then().extract().response();
        versionen = get("/api/v1/wissensseiten/" + normaleSeite + "/versionen")
                .then().extract().response();
        sichtbareVersionAntwort = get("/api/v1/wissensseiten/" + normaleSeite + "/versionen/2")
                .then().extract().response();
        interneVersion = get("/api/v1/wissensseiten/" + normaleSeite + "/versionen/1")
                .then().extract().response();
        diff = get("/api/v1/wissensseiten/" + normaleSeite + "/diff?von=1&bis=2")
                .then().extract().response();
        sichtbarerDiff = get("/api/v1/wissensseiten/" + normaleSeite + "/diff?von=2&bis=3")
                .then().extract().response();
        eingehend = get("/api/v1/wissensseiten/" + quellenSeite + "/eingehend")
                .then().extract().response();
        log = get("/api/v1/wissensbasis/log?groesse=100")
                .then().extract().response();
        logErsteSeite = get("/api/v1/wissensbasis/log?groesse=1")
                .then().extract().response();
        logZweiteSeite = get("/api/v1/wissensbasis/log?groesse=1&vor="
                + logErsteSeite.jsonPath().getString("naechstesVor")).then().extract().response();
        ungueltigeCursor = java.util.stream.Stream.of("eAox", "!!!", cursor("+300000-01-01T00:00:00Z\n1"),
                        cursor("2026-01-01T00:00:00Z\n0"), cursor("2026-01-01T00:00:00Z"))
                .map(vor -> get("/api/v1/wissensbasis/log?vor=" + vor).then().extract().response()).toList();
        ungueltigeIds = java.util.stream.Stream.of("", "/versionen", "/versionen/1", "/diff?von=1&bis=2",
                        "/eingehend")
                .map(rest -> get("/api/v1/wissensseiten/keine-uuid" + rest).then().extract().response()).toList();
    }

    private static String cursor(String wert) {
        return java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString(wert.getBytes(java.nio.charset.StandardCharsets.UTF_8));
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

        seite.then().statusCode(200).header("ETag", "\"" + 3 + "\"");
        assertThat(seite.jsonPath().getString("titel")).isEqualTo("Normale Leseprobe " + kennung);
        seitePerPfad.then().statusCode(200);
        interneSeiteAntwort.then().statusCode(404);
        assertThat(seite.body().asString()).doesNotContain(interneSeiteTitel);
        assertThat(seite.jsonPath().getList("quellen.dokumentId", String.class))
                .containsExactly(normaleQuelle.toString());

        versionen.then().statusCode(200);
        List<Map<String, Object>> versionZeilen = versionen.jsonPath().getList("versionen");
        assertThat(versionZeilen).hasSize(3);
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
        diff.then().statusCode(404);
        sichtbarerDiff.then().statusCode(200).header("ETag", "\"2-3\"");
        assertThat(sichtbarerDiff.jsonPath().getList("vorher", String.class)).containsExactly("Kopf");
        assertThat(sichtbarerDiff.jsonPath().getList("entfernt", String.class)).containsExactly("Alte Aussage");
        assertThat(sichtbarerDiff.jsonPath().getList("hinzugefuegt", String.class)).containsExactly("Neue Aussage");
        assertThat(sichtbarerDiff.jsonPath().getList("nachher", String.class)).containsExactly("Fuss");

        eingehend.then().statusCode(200);
        assertThat(eingehend.jsonPath().getList("pfad", String.class)).contains("konzepte/leseprobe-verweis-" + kennung);

        log.then().statusCode(200);
        String logText = log.body().asString();
        assertThat(logText).contains("Normale Leseprobe " + kennung)
                .doesNotContain(interneSeiteTitel, "synthetic_log_payload_" + kennung,
                        "hidden_log_payload_" + kennung);
        assertThat(logText).doesNotContain(interneSeite.toString(), interneEntwurf.toString(),
                "human:probe-c-" + kennung, "human:fremd-" + kennung, "fremd_log_payload_" + kennung,
                "intern_entwurf_payload_" + kennung);
        Map<String, Object> mitEntwurf = logEintrag(log, "human:probe-a-" + kennung);
        assertThat(mitEntwurf).containsEntry("entwurfId", entwurf.toString());
        Map<String, Object> mitInternemEntwurf = logEintrag(log, "human:probe-b-" + kennung);
        assertThat(mitInternemEntwurf.get("entwurfId")).isNull();

        logErsteSeite.then().statusCode(200);
        assertThat(logErsteSeite.jsonPath().getList("eintraege.akteur", String.class))
                .containsExactly("human:probe-b-" + kennung);
        logZweiteSeite.then().statusCode(200);
        assertThat(logZweiteSeite.jsonPath().getList("eintraege.akteur", String.class))
                .containsExactly("human:probe-a-" + kennung);
        for (Response ungueltig : ungueltigeCursor) {
            assertThat(ch.urinet.archiv.sicherheit.ProblemDetails.pruefen(ungueltig, 400))
                    .isEqualTo("ANFRAGE_UNGUELTIG");
        }
        for (Response ungueltig : ungueltigeIds) {
            assertThat(ch.urinet.archiv.sicherheit.ProblemDetails.pruefen(ungueltig, 404))
                    .isEqualTo("NICHT_GEFUNDEN");
        }
        assertThat(seite.jsonPath().getString("inhaltHtml")).contains("Sichtbare Seite", "Seite nicht vorhanden")
                .doesNotContain("Interne Seite", "Fehlende Seite");
        zugriffsprotokollPruefen();
    }

    private static Map<String, Object> logEintrag(Response antwort, String akteur) {
        List<Map<String, Object>> eintraege = antwort.jsonPath().getList("eintraege");
        return eintraege.stream().filter(e -> akteur.equals(e.get("akteur"))).findFirst().orElseThrow();
    }

    private Response get(String path) {
        return given().auth().oauth2(Testanmeldung.token(MITARBEITER)).when().get(path).andReturn();
    }

    private void zugriffsprotokollPruefen() throws Exception {
        try (Connection verbindung = datenbank.get().getConnection();
             PreparedStatement angezeigt = verbindung.prepareStatement("""
                     select count(*) from m_urinet.zugriff_protokoll
                     where aktion = 'WISSEN_ANZEIGEN' and dokument_id = ? and zeit >= ?
                     """);
             PreparedStatement verweigert = verbindung.prepareStatement("""
                     select count(*) from m_urinet.zugriff_protokoll
                     where aktion = 'VERWEIGERT' and rolle = 'MITARBEITER' and details ->> 'grund' = 'A15'
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
            // Seite, ?pfad=, Versionen, Version 2 und Diff 2–3; Fehlgriffe (404) rollen ihren Eintrag zurück
            try (var zeilen = angezeigt.executeQuery()) {
                assertThat(zeilen.next()).isTrue();
                assertThat(zeilen.getLong(1)).isEqualTo(5);
            }
            angezeigt.setObject(1, quellenSeite);
            try (var zeilen = angezeigt.executeQuery()) {
                assertThat(zeilen.next()).isTrue();
                assertThat(zeilen.getLong(1)).isEqualTo(1);
            }
            // interne Seite, Version 1 und Diff 1–2, dazu je Endpunkt mit {id} eine ungültige ID (Req 14.4)
            try (var zeilen = verweigert.executeQuery()) {
                assertThat(zeilen.next()).isTrue();
                assertThat(zeilen.getLong(1)).isEqualTo(8);
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
                values (?, 'UPLOAD', ?, 'synthetisch', 'ARCHIVIERT', ?, 'inhaber')
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
        quelle(verbindung, SCHEMA, seite, schluessel, dokument);
    }

    private static void quelle(Connection verbindung, String schema, UUID seite, String schluessel, UUID dokument)
            throws Exception {
        try (PreparedStatement query = verbindung.prepareStatement("""
                insert into %s.wissensseite_quelle (seite_id, schluessel, dokument_id, seite_von, auszug)
                values (?, ?, ?, 1, 'synthetischer Auszug')
                """.formatted(schema))) {
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

    private static void aktuelleVersion(Connection verbindung, UUID seite, int nummer) throws Exception {
        try (PreparedStatement query = verbindung.prepareStatement(
                "update m_urinet.wissensseite set version = ? where id = ?")) {
            query.setInt(1, nummer);
            query.setObject(2, seite);
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

    private static void log(Connection verbindung, String schema, UUID[] seiten, String angaben, String akteur,
                            UUID entwurfId) throws Exception {
        String uuidArray = "{" + String.join(",", java.util.Arrays.stream(seiten).map(UUID::toString).toList()) + "}";
        try (PreparedStatement query = verbindung.prepareStatement("""
                insert into %s.wissens_log (zeit, aktion, akteur, seiten, angaben, entwurf_id)
                values (?, 'UPDATE', ?, cast(? as uuid[]), cast(? as jsonb), ?)
                """.formatted(schema))) {
            query.setObject(1, OffsetDateTime.now(ZoneOffset.UTC));
            query.setString(2, akteur);
            query.setString(3, uuidArray);
            query.setString(4, "{\"probe\":\"" + angaben + "\"}");
            query.setObject(5, entwurfId);
            query.executeUpdate();
        }
    }
}
