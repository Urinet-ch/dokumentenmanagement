package ch.urinet.archiv.importe;

import ch.urinet.archiv.ablage.TestAblage;
import ch.urinet.archiv.dokument.Testdateien;
import ch.urinet.archiv.dokument.Testdokumente;
import ch.urinet.archiv.gemeinsam.Testanmeldung;
import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.sicherheit.application.ApiTokenVerwaltung;
import io.agroal.api.AgroalDataSource;
import io.cucumber.datatable.DataTable;
import io.cucumber.java.After;
import io.cucumber.java.de.Angenommen;
import io.cucumber.java.de.Dann;
import io.cucumber.java.de.Wenn;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Schritte zu Quellenregister, Quellläufen und Prüfsummen-Abfrage (Spec 09, Requirement 10 und 15.2; Aufgabe 27.6).
 * Dateien sind synthetische PDF je Kennbuchstabe; Läufe, Dokumente und angelegte Quellen werden aufgeräumt.
 */
public class QuellenSchritte {

    private record AngelegteQuelle(String mandant, String code) {
    }

    private static final String QUELLEN = "/api/v1/quellen";
    private static final String UPLOAD = "/api/v1/dokumente";
    private static final String PRUEFSUMMEN = "/api/v1/dokumente/pruefsummen";
    private static final List<String> SEED = List.of("generatoren", "drive", "mac-ordner", "gmail", "crm-csv",
            "papier");
    private static final List<String> MANDANTEN = List.of("urinet", "vhf-gsk");

    @Inject
    Instance<AgroalDataSource> datenbank;

    @Inject
    ApiTokenVerwaltung tokens;

    private final Map<String, byte[]> dateien = new HashMap<>();
    private final Map<String, UUID> dokumente = new HashMap<>();
    private final Map<String, UUID> stapelOriginale = new HashMap<>();
    private final Map<String, String> stapelMandanten = new HashMap<>();
    private final Map<String, String> stapelPruefsummen = new HashMap<>();
    private final Map<String, List<UUID>> hochgeladen = new HashMap<>();
    private final Set<AngelegteQuelle> angelegt = new HashSet<>();
    private final Map<String, Long> stand = new HashMap<>();
    private Response antwort;
    private String token;
    private String tokenMandant;
    private UUID lauf;

    @Angenommen("der Stand der Verweigerungen von «{word}» ist gemerkt")
    public void standMerken(String mandant) throws SQLException {
        stand.put(mandant, zahl("select coalesce(max(id), 0) from " + schema(mandant) + ".zugriff_protokoll"));
    }

    @After("@quellenregister")
    public void aufraeumen() throws SQLException {
        for (Map.Entry<String, UUID> eintrag : stapelOriginale.entrySet()) {
            ausfuehren("delete from " + stapelMandanten.get(eintrag.getKey()) + ".stapel where id = ?",
                    eintrag.getValue());
        }
        for (Map.Entry<String, List<UUID>> eintrag : hochgeladen.entrySet()) {
            ausfuehren("delete from public.auftrag where typ = 'VERARBEITEN' "
                    + "and nutzlast->>'dokumentId' = any (string_to_array(?, ','))",
                    String.join(",", eintrag.getValue().stream().map(UUID::toString).toList()));
            Testdokumente.loeschen(datenbank.get(), eintrag.getKey(), eintrag.getValue());
            Set<String> ordner = new HashSet<>(eintrag.getValue().stream().map(id -> "/" + id + "/").toList());
            TestAblage.loeschen(eintrag.getKey(), "eingang/", s -> ordner.stream().anyMatch(s::contains));
        }
        for (String mandant : MANDANTEN) {
            String schema = schema(mandant);
            ausfuehren("update " + schema + ".dokument set quelle_lauf_id = null where quelle_lauf_id is not null");
            ausfuehren("update " + schema + ".quelle set letzter_lauf = null");
            ausfuehren("delete from " + schema + ".quelle_lauf");
            ausfuehren("update " + schema + ".quelle set status = 'OFFEN', bemerkung = null, geheimnis = null");
        }
        for (AngelegteQuelle quelle : angelegt) {
            ausfuehren("delete from " + schema(quelle.mandant()) + ".quelle where code = ?", quelle.code());
        }
    }

    // --- Benutzer --------------------------------------------------------------------------------------------------

    @Wenn("«{word}» das Quellenregister abfragt")
    public void registerAbfragen(String benutzer) {
        antwort = alsBenutzer(benutzer).when().get(QUELLEN);
    }

    @Wenn("«{word}» die Quelle «{word}» vom Typ «{word}» anlegt")
    public void anlegen(String benutzer, String code, String typ) {
        antwort = alsBenutzer(benutzer).contentType(ContentType.JSON)
                .body(Map.of("code", code, "name", "Probe " + code, "typ", typ)).when().post(QUELLEN);
        if (antwort.statusCode() == 201) {
            angelegt.add(new AngelegteQuelle(mandant(benutzer), code));
        }
    }

    @Wenn("«{word}» die Quelle «{word}» auf Status «{word}» mit Bemerkung «{}» setzt")
    public void setzenMitBemerkung(String benutzer, String code, String status, String bemerkung) throws SQLException {
        antwort = aendern(alsBenutzer(benutzer), code, version(benutzer, code),
                Map.of("status", status, "bemerkung", bemerkung));
    }

    @Wenn("«{word}» die Quelle «{word}» auf Status «{word}» setzt")
    public void setzen(String benutzer, String code, String status) throws SQLException {
        antwort = aendern(alsBenutzer(benutzer), code, version(benutzer, code), Map.of("status", status));
    }

    @Wenn("«{word}» die Quelle «{word}» mit einer veralteten Version ändert")
    public void veraltet(String benutzer, String code) throws SQLException {
        antwort = aendern(alsBenutzer(benutzer), code, version(benutzer, code) - 1, Map.of("bemerkung", "alt"));
    }

    @Wenn("«{word}» das Geheimnis der Quelle «{word}» setzt")
    public void geheimnis(String benutzer, String code) {
        antwort = alsBenutzer(benutzer).contentType(ContentType.JSON).body(Map.of("geheimnis", "synthetisch-123"))
                .when().put(QUELLEN + "/" + code + "/geheimnis");
    }

    @Dann("antwortet das Quellenregister mit Status {int}")
    public void status(int status) {
        assertThat(antwort.statusCode()).as(antwort.asString()).isEqualTo(status);
    }

    @Dann("antwortet das Quellenregister mit Status {int} und Fehlercode «{word}»")
    public void statusMitCode(int status, String code) {
        status(status);
        assertThat(antwort.jsonPath().getString("fehlercode")).isEqualTo(code);
    }

    @Dann("enthält das Quellenregister diese Quellen:")
    public void enthaelt(DataTable tabelle) {
        List<Map<String, Object>> quellen = antwort.jsonPath().getList("$");
        for (Map<String, String> zeile : tabelle.asMaps()) {
            Map<String, Object> quelle = quellen.stream().filter(q -> zeile.get("code").equals(q.get("code")))
                    .findFirst().orElseThrow(() -> new AssertionError("Quelle fehlt: " + zeile.get("code")));
            assertThat(quelle.get("typ")).isEqualTo(zeile.get("typ"));
            assertThat(quelle.get("status")).isEqualTo(zeile.get("status"));
            assertThat(quelle.get("geheimnisGesetzt")).isEqualTo("ja".equals(zeile.get("geheimnis")));
        }
    }

    @Dann("enthält das Quellenregister keine der allgemeinen Quellen von urinet")
    public void keineAllgemeinen() {
        List<String> codes = antwort.jsonPath().getList("code");
        assertThat(codes).doesNotContainAnyElementsOf(SEED);
    }

    @Dann("die Antwort des Quellenregisters enthält das Geheimnis nicht")
    public void ohneGeheimnis() {
        assertThat(antwort.asString()).doesNotContain("synthetisch-123").doesNotContain("\"geheimnis\"");
    }

    @Dann("hat das Zugriffsprotokoll von «{word}» für «{word}» {word} Verweigerung «{word}»")
    public void verweigerung(String mandant, String benutzer, String anzahl, String aktion) throws SQLException {
        long gezaehlt = zahl("select count(*) from " + schema(mandant) + ".zugriff_protokoll where id > ? "
                + "and aktion = 'VERWEIGERT' and benutzer = ? and details->>'grund' = ? "
                + "and details->>'fehlercode' = 'NICHT_ERLAUBT'", stand.get(mandant), Testanmeldung.sub(benutzer),
                aktion);
        assertThat(gezaehlt).isEqualTo("eine".equals(anzahl) ? 1 : 0);
    }

    // --- Werkzeug mit Token vom Scope import -----------------------------------------------------------------------

    @Angenommen("ein Werkzeug von «{word}» hat ein Token vom Scope «import»")
    public void tokenAnlegen(String mandant) {
        token = tokens.erstellen(mandant, "Probe Quellen", "import").token().text();
        tokenMandant = mandant;
    }

    @Wenn("das Werkzeug das Quellenregister abfragt")
    public void werkzeugRegister() {
        antwort = alsWerkzeug().when().get(QUELLEN);
    }

    @Wenn("das Werkzeug die Quelle «{word}» auf Status «{word}» setzt")
    public void werkzeugSetzt(String code, String status) {
        antwort = aendern(alsWerkzeug(), code, 0, Map.of("status", status));
    }

    @Wenn("das Werkzeug einen Lauf «{word}» der Quelle «{word}» startet")
    public void laufStarten(String art, String code) {
        antwort = alsWerkzeug().contentType(ContentType.JSON).body(Map.of("art", art)).when()
                .post(QUELLEN + "/" + code + "/laeufe");
        if (antwort.statusCode() == 201) {
            lauf = UUID.fromString(antwort.jsonPath().getString("id"));
        }
    }

    @Wenn("das Werkzeug die Datei «{word}» in den Lauf hochlädt")
    public void inLaufHochladen(String datei) {
        hochladen(datei, "quelllauf", lauf.toString());
    }

    @Wenn("das Werkzeug die Datei «{word}» mit der Quelle «{word}» hochlädt")
    public void mitQuelleHochladen(String datei, String code) {
        hochladen(datei, "quelle", code);
    }

    @Angenommen("das Werkzeug hat die Datei «{word}» ohne Lauf hochgeladen")
    public void ohneLauf(String datei) {
        hochladen(datei, null, null);
        assertThat(antwort.statusCode()).isEqualTo(202);
    }

    @Dann("ist die Datei im Upload «{word}»")
    public void uploadStatus(String status) {
        assertThat(antwort.statusCode()).as(antwort.asString()).isEqualTo(202);
        assertThat(antwort.jsonPath().getString("[0].status")).isEqualTo(status);
    }

    @Dann("lehnt das Archiv den Upload in den Quelllauf mit Status {int} und Fehlercode «{word}» ab")
    public void uploadAbgelehnt(int status, String code) {
        statusMitCode(status, code);
    }

    @Wenn("das Werkzeug den Lauf mit {int} übersprungenen Dateien abschliesst")
    public void abschliessen(int uebersprungen) {
        antwort = abschluss(Map.of("uebersprungen", uebersprungen, "meldungen", List.of("synthetischer Lauf")));
    }

    @Wenn("das Werkzeug den Lauf mit den Zählern gefunden {int}, neu {int}, Dublette nach Prüfsumme {int}, Fehler {int} "
            + "abschliesst")
    public void trockenAbschliessen(int gefunden, int neu, int dublette, int fehler) {
        antwort = abschluss(Map.of("zaehler", Map.of("gefunden", gefunden, "neu", neu, "dublettePruefsumme", dublette,
                "dubletteFachlich", 0, "uebersprungen", 0, "fehler", fehler)));
    }

    @Dann("hat der Lauf die Zähler gefunden {int}, neu {int}, Dublette nach Prüfsumme {int}, übersprungen {int}, "
            + "Fehler {int}, und sie gehen auf")
    public void zaehler(int gefunden, int neu, int dublette, int uebersprungen, int fehler) {
        assertThat(antwort.jsonPath().getMap("zaehler")).containsEntry("gefunden", gefunden)
                .containsEntry("neu", neu).containsEntry("dublettePruefsumme", dublette)
                .containsEntry("uebersprungen", uebersprungen).containsEntry("fehler", fehler)
                .containsEntry("dubletteFachlich", 0);
        assertThat(gefunden).isEqualTo(neu + dublette + uebersprungen + fehler);
        assertThat(antwort.jsonPath().getString("status")).isEqualTo("FERTIG");
    }

    @Dann("verweisen die Dokumente der Dateien «{}» auf den Lauf mit der Quelle «{word}»")
    public void verweisen(String liste, String quelle) throws SQLException {
        for (String datei : liste.split(",\\s*")) {
            assertThat(dokumentLauf(datei)).isEqualTo(List.of(lauf.toString(), quelle));
        }
    }

    @Dann("verweist das Dokument der Datei «{word}» auf den automatischen Lauf von «{word}» mit der Quelle «{word}»")
    public void automatisch(String datei, String code, String quelle) throws SQLException {
        List<String> werte = dokumentLauf(datei);
        assertThat(werte.get(1)).isEqualTo(quelle);
        assertThat(text("select l.art || ' ' || q.code from " + schema(tokenMandant) + ".quelle_lauf l join "
                + schema(tokenMandant) + ".quelle q on q.id = l.quelle_id where l.id = ?::uuid", werte.get(0)))
                .isEqualTo("AUTOMATISCH " + code);
    }

    @Dann("hat der Trockenlauf kein Dokument angelegt")
    public void keinDokument() throws SQLException {
        assertThat(zahl("select count(*) from " + schema(tokenMandant) + ".dokument where quelle_lauf_id = ?", lauf))
                .isZero();
        assertThat(zahl("select gefunden from " + schema(tokenMandant) + ".quelle_lauf where id = ?", lauf)).isZero();
        assertThat(hochgeladen.getOrDefault(tokenMandant, List.of())).isEmpty();
    }

    @Wenn("das Werkzeug die Prüfsummen der Dateien «{}» abfragt")
    public void pruefsummenAlsWerkzeug(String liste) {
        antwort = alsWerkzeug().contentType(ContentType.JSON).body(Map.of("sha256", pruefsummen(liste))).when()
                .post(PRUEFSUMMEN);
    }

    private List<String> pruefsummen(String liste) {
        List<String> werte = new ArrayList<>();
        for (String datei : liste.split(",\\s*")) {
            werte.add(stapelPruefsummen.getOrDefault(datei, sha256(datei(datei))));
        }
        return werte;
    }

    @Angenommen("das Stapel-Original der Datei «{word}» liegt im Status «{word}»")
    public void stapelOriginal(String datei, String status) throws SQLException {
        UUID id = UUID.randomUUID();
        String checksum = sha256(("synthetischer Stapel " + datei).getBytes(StandardCharsets.UTF_8));
        ausfuehren("insert into " + schema(tokenMandant) + ".stapel "
                        + "(id, profil, eingang, sha256, groesse, s3_schluessel, status, empfangen) "
                        + "values (?, 'STAPEL', 'SFTP', ?, 1, ?, ?, clock_timestamp())",
                id, checksum, "eingang/stapel/" + id + ".pdf", status);
        stapelOriginale.put(datei, id);
        stapelMandanten.put(datei, schema(tokenMandant));
        stapelPruefsummen.put(datei, checksum);
    }

    @Wenn("«{word}» die Prüfsummen der Dateien «{}» abfragt")
    public void pruefsummenAlsBenutzer(String benutzer, String liste) {
        antwort = alsBenutzer(benutzer).contentType(ContentType.JSON).body(Map.of("sha256", pruefsummen(liste)))
                .when().post(PRUEFSUMMEN);
    }

    /** Ein Dokument des Uploads gilt als bestätigt (Spec 01, 2.21); synthetisch direkt in der Datenbank. */
    @Angenommen("das Dokument der Datei «{word}» ist bestätigt mit der Vertraulichkeit «{word}»")
    public void bestaetigt(String datei, String vertraulichkeit) throws SQLException {
        ausfuehren("update " + schema(tokenMandant) + ".dokument set bestaetigt = clock_timestamp(), "
                + "vertraulichkeit = ?, person_benutzer = null where id = ?", vertraulichkeit, dokumente.get(datei));
    }

    @Angenommen("das Dokument der Datei «{word}» ist bestätigt als «PERSONAL» von «{word}»")
    public void personal(String datei, String benutzer) throws SQLException {
        ausfuehren("update " + schema(tokenMandant) + ".dokument set bestaetigt = clock_timestamp(), "
                + "vertraulichkeit = 'PERSONAL', person_benutzer = ? where id = ?", Testanmeldung.sub(benutzer),
                dokumente.get(datei));
    }

    @Dann("meldet die Prüfsummen-Abfrage genau die Dateien «{}» als vorhanden")
    public void genauVorhanden(String liste) {
        status(200);
        assertThat(antwort.jsonPath().getList("vorhanden", String.class))
                .containsExactlyInAnyOrderElementsOf(pruefsummen(liste));
    }

    @Wenn("das Werkzeug {int} Prüfsummen abfragt")
    public void vielePruefsummen(int anzahl) {
        List<String> werte = IntStream.range(0, anzahl).mapToObj(i -> String.format("%064x", i)).toList();
        antwort = alsWerkzeug().contentType(ContentType.JSON).body(Map.of("sha256", werte)).when().post(PRUEFSUMMEN);
    }

    @Dann("antwortet die Prüfsummen-Abfrage mit Status {int}")
    public void pruefsummenStatus(int status) {
        status(status);
    }

    @Dann("meldet die Prüfsummen-Abfrage nur die Datei «{word}» als vorhanden, ohne IDs")
    public void nurVorhanden(String datei) {
        assertThat(antwort.jsonPath().getList("vorhanden")).containsExactly(
                stapelPruefsummen.getOrDefault(datei, sha256(datei(datei))));
        dokumente.values().forEach(id -> assertThat(antwort.asString()).doesNotContain(id.toString()));
        stapelOriginale.values().forEach(id -> assertThat(antwort.asString()).doesNotContain(id.toString()));
        assertThat(antwort.jsonPath().getMap("$")).containsOnlyKeys("vorhanden");
    }

    @Dann("meldet die Prüfsummen-Abfrage keine Datei als vorhanden")
    public void keineVorhanden() {
        status(200);
        assertThat(antwort.jsonPath().getList("vorhanden")).isEmpty();
    }

    /**
     * Mandantentrennung (MandantenIsolationOpenApiTest): Quellen und Läufe von urinet gibt es für vhf-gsk nicht, weder
     * für den Inhaber noch für ein Token; der Lauf von urinet bleibt unverändert.
     */
    @Dann("bleiben Quellen und Läufe von urinet für vhf-gsk unbekannt")
    public void fremdeQuellenUnsichtbar() throws SQLException {
        assertThat(text("select 1 from m_vhf_gsk.quelle where code = 'drive'")).isNull();
        RequestSpecification inhaber = alsBenutzer("inhaber-vhf-gsk");
        assertThat(aendern(inhaber, "drive", 0, Map.of("status", "LAUFEND")).statusCode()).isEqualTo(404);
        assertThat(alsBenutzer("inhaber-vhf-gsk").contentType(ContentType.JSON).body(Map.of("geheimnis", "x"))
                .when().put(QUELLEN + "/drive/geheimnis").statusCode()).isEqualTo(404);
        assertThat(alsBenutzer("inhaber-vhf-gsk").when().get(QUELLEN + "/drive/laeufe").statusCode()).isEqualTo(404);
        String fremd = tokens.erstellen("vhf-gsk", "Probe Quellen fremd", "import").token().text();
        assertThat(given().header("Authorization", "Bearer " + fremd).contentType(ContentType.JSON)
                .body(Map.of("art", "ECHT")).when().post(QUELLEN + "/drive/laeufe").statusCode()).isEqualTo(404);
        assertThat(given().header("Authorization", "Bearer " + fremd).contentType(ContentType.JSON)
                .body(Map.of("uebersprungen", 1)).when().post(QUELLEN + "/laeufe/" + lauf + "/abschliessen")
                .statusCode()).isEqualTo(404);
        assertThat(text("select status from m_urinet.quelle_lauf where id = ?", lauf)).isEqualTo("LAEUFT");
    }

    // --- Hilfen ----------------------------------------------------------------------------------------------------

    private void hochladen(String datei, String feld, String wert) {
        RequestSpecification anfrage = alsWerkzeug().multiPart("dateien", datei + ".pdf", datei(datei),
                "application/pdf");
        if (feld != null) {
            anfrage = anfrage.multiPart(feld, wert);
        }
        antwort = anfrage.when().post(UPLOAD);
        if (antwort.statusCode() == 202 && "NEU".equals(antwort.jsonPath().getString("[0].status"))) {
            UUID id = UUID.fromString(antwort.jsonPath().getString("[0].id"));
            dokumente.put(datei, id);
            hochgeladen.computeIfAbsent(tokenMandant, m -> new ArrayList<>()).add(id);
        }
    }

    private Response abschluss(Map<String, Object> body) {
        return alsWerkzeug().contentType(ContentType.JSON).body(body).when()
                .post(QUELLEN + "/laeufe/" + lauf + "/abschliessen");
    }

    private static Response aendern(RequestSpecification anfrage, String code, int version, Map<String, ?> body) {
        return anfrage.contentType(ContentType.JSON).header("If-Match", "\"" + version + "\"").body(body).when()
                .patch(QUELLEN + "/" + code);
    }

    private List<String> dokumentLauf(String datei) throws SQLException {
        UUID id = dokumente.get(datei);
        assertThat(id).as("Dokument der Datei " + datei).isNotNull();
        try (Connection verbindung = datenbank.get().getConnection(); PreparedStatement abfrage =
                verbindung.prepareStatement("select quelle_lauf_id::text, quelle from " + schema(tokenMandant)
                        + ".dokument where id = ?")) {
            abfrage.setObject(1, id);
            try (ResultSet zeile = abfrage.executeQuery()) {
                assertThat(zeile.next()).isTrue();
                return List.of(String.valueOf(zeile.getString(1)), zeile.getString(2));
            }
        }
    }

    /** Version der Quelle im Mandanten des Benutzers; unbekannte Quelle: 0 (die Anfrage scheitert ohnehin). */
    private int version(String benutzer, String code) throws SQLException {
        String mandant = mandant(benutzer);
        String wert = text("select version::text from " + schema(mandant) + ".quelle where code = ?", code);
        return wert == null ? 0 : Integer.parseInt(wert);
    }

    private static String mandant(String benutzer) {
        return benutzer.endsWith("-vhf-gsk") ? "vhf-gsk" : "urinet";
    }

    private byte[] datei(String name) {
        return dateien.computeIfAbsent(name, n -> Testdateien.pdf());
    }

    private static RequestSpecification alsBenutzer(String benutzer) {
        return given().auth().oauth2(Testanmeldung.token(benutzer));
    }

    private RequestSpecification alsWerkzeug() {
        return given().header("Authorization", "Bearer " + token);
    }

    private static String sha256(byte[] inhalt) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(inhalt));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String schema(String mandant) {
        return new MandantCode(mandant).schema();
    }

    private long zahl(String sql, Object... werte) throws SQLException {
        String wert = text(sql, werte);
        return wert == null ? 0 : Long.parseLong(wert);
    }

    private String text(String sql, Object... werte) throws SQLException {
        try (Connection verbindung = datenbank.get().getConnection();
             PreparedStatement abfrage = verbindung.prepareStatement(sql)) {
            for (int i = 0; i < werte.length; i++) {
                abfrage.setObject(i + 1, werte[i]);
            }
            try (ResultSet zeile = abfrage.executeQuery()) {
                return zeile.next() ? zeile.getString(1) : null;
            }
        }
    }

    private void ausfuehren(String sql, Object... werte) throws SQLException {
        try (Connection verbindung = datenbank.get().getConnection();
             PreparedStatement befehl = verbindung.prepareStatement(sql)) {
            for (int i = 0; i < werte.length; i++) {
                befehl.setObject(i + 1, werte[i]);
            }
            befehl.executeUpdate();
        }
    }
}
