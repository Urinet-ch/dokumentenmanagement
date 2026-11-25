package ch.urinet.archiv.ki.chat;

import ch.urinet.archiv.benutzer.adapter.out.stepup.StepUpMitAuthTime;
import ch.urinet.archiv.benutzer.application.StepUp;
import ch.urinet.archiv.gemeinsam.Testanmeldung;
import ch.urinet.archiv.ki.domain.chat.Chatmodus;
import ch.urinet.archiv.sicherheit.application.ApiTokenVerwaltung;
import ch.urinet.archiv.sicherheit.application.WebseitenSchluesselVerwaltung;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agroal.api.AgroalDataSource;
import io.cucumber.java.After;
import io.cucumber.java.Before;
import io.cucumber.java.de.Angenommen;
import io.cucumber.java.de.Dann;
import io.cucumber.java.de.Wenn;
import io.quarkus.arc.ClientProxy;
import io.quarkus.test.junit.QuarkusMock;
import io.restassured.response.Response;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.MatchResult;
import java.util.regex.Pattern;
import java.util.zip.ZipInputStream;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

public class ChatAuswertungSchritte {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String FRAGE = "Ada Muster, ada@example.test, Telefon +41 79 123 45 67, "
            + "IBAN CH93 0076 2011 6238 5295 7, UID CHE-123.456.789 MWST, AHV 756.1234.5678.97, "
            + "CHF 1'234.50, Datum 08.11.2026";
    private static final String ANTWORT = "Synthetische Antwort für Ada Muster, ada@example.test.";

    @Inject
    Instance<AgroalDataSource> datenbank;

    @Inject
    StepUp stepUp;

    @Inject
    ApiTokenVerwaltung apiTokens;

    @Inject
    WebseitenSchluesselVerwaltung webseitenSchluessel;

    private final Map<String, List<UUID>> gespraeche = new HashMap<>();
    private final Map<String, List<UUID>> exportKontakte = new HashMap<>();
    private final Map<String, List<UUID>> exportDokumente = new HashMap<>();
    private final List<String> apiClientIds = new ArrayList<>();
    private Response letzteAntwort;
    private Map<String, String> zipDateien = Map.of();
    private String frageZusatz = "";
    private String apiToken;
    private String webseitenToken;
    private StepUp urspruenglichesStepUp;
    private boolean stepUpGeprueft;

    @Before
    public void vorbereiten() {
        gespraeche.clear();
        exportKontakte.clear();
        exportDokumente.clear();
        apiClientIds.clear();
        letzteAntwort = null;
        zipDateien = Map.of();
        frageZusatz = "";
        apiToken = null;
        webseitenToken = null;
        stepUpGeprueft = false;
    }

    @After
    public void aufraeumen() throws SQLException {
        if (urspruenglichesStepUp != null) {
            QuarkusMock.installMockForType(urspruenglichesStepUp, StepUp.class);
            urspruenglichesStepUp = null;
        }
        try (Connection verbindung = datenbank.get().getConnection()) {
            for (String id : apiClientIds) {
                try (PreparedStatement loeschen = verbindung.prepareStatement(
                        "delete from public.api_client where id = ?")) {
                    loeschen.setString(1, id);
                    loeschen.executeUpdate();
                }
            }
            for (Map.Entry<String, List<UUID>> eintrag : gespraeche.entrySet()) {
                for (UUID gespraech : eintrag.getValue()) {
                    try (PreparedStatement befehl = verbindung.prepareStatement(
                            "delete from " + schema(eintrag.getKey()) + ".chat_gespraech where id = ?")) {
                        befehl.setObject(1, gespraech);
                        befehl.executeUpdate();
                    }
                }
            }
            for (Map.Entry<String, List<UUID>> eintrag : exportDokumente.entrySet()) {
                for (UUID dokument : eintrag.getValue()) {
                    try (PreparedStatement befehl = verbindung.prepareStatement(
                            "delete from " + schema(eintrag.getKey()) + ".dokument where id = ?")) {
                        befehl.setObject(1, dokument);
                        befehl.executeUpdate();
                    }
                }
            }
            for (Map.Entry<String, List<UUID>> eintrag : exportKontakte.entrySet()) {
                for (UUID kontakt : eintrag.getValue()) {
                    try (PreparedStatement befehl = verbindung.prepareStatement(
                            "delete from " + schema(eintrag.getKey()) + ".kontakt where id = ?")) {
                        befehl.setObject(1, kontakt);
                        befehl.executeUpdate();
                    }
                }
            }
        }
    }

    @Angenommen("im Mandanten «{word}» sind synthetische Kontakt- und Partnernamen für den Export erfasst")
    public void bekannteExportNamenAnlegen(String mandant) throws SQLException {
        String s = schema(mandant);
        UUID kontakt = UUID.randomUUID();
        UUID dokument = UUID.randomUUID();
        try (Connection verbindung = datenbank.get().getConnection();
             PreparedStatement einfuegen = verbindung.prepareStatement("insert into " + s
                     + ".kontakt (id, typ, name, name_normalisiert, quelle) values (?, 'ORGANISATION', "
                     + "'Müller AG', 'mueller', 'MANUELL')")) {
            einfuegen.setObject(1, kontakt);
            einfuegen.executeUpdate();
        }
        exportKontakte.computeIfAbsent(mandant, ignored -> new ArrayList<>()).add(kontakt);
        try (Connection verbindung = datenbank.get().getConnection();
             PreparedStatement einfuegen = verbindung.prepareStatement("insert into " + s
                     + ".dokument (id, quelle, erstellt_von, status, partner_id, partner_name) "
                     + "values (?, 'UPLOAD', 'probe', 'NEU', ?, 'Müller GmbH')")) {
            einfuegen.setObject(1, dokument);
            einfuegen.setObject(2, kontakt);
            einfuegen.executeUpdate();
        }
        exportDokumente.computeIfAbsent(mandant, ignored -> new ArrayList<>()).add(dokument);
        frageZusatz = ", Müller AG, Müller GmbH, mueller";
    }

    @Angenommen("im Mandanten «{word}» gibt es eine synthetische Chat-Bewertung mit {int} Sternen")
    public void bewertungAnlegen(String mandant, int sterne) throws SQLException {
        bewertungAnlegen(mandant, sterne, Chatmodus.ARCHIV.name(), FRAGE + frageZusatz);
    }

    @Angenommen("im Mandanten «{word}» gibt es eine synthetische Chat-Bewertung mit {int} Sternen im Modus «{word}»")
    public void bewertungAnlegen(String mandant, int sterne, String modus) throws SQLException {
        bewertungAnlegen(mandant, sterne, modus, FRAGE + frageZusatz);
    }

    @Angenommen("im Mandanten «{word}» gibt es eine synthetische Chat-Bewertung mit {int} Sternen und der Frage «{}»")
    public void bewertungAnlegenMitFrage(String mandant, int sterne, String frage) throws SQLException {
        bewertungAnlegen(mandant, sterne, Chatmodus.ARCHIV.name(), frage);
    }

    private void bewertungAnlegen(String mandant, int sterne, String modus, String frageText) throws SQLException {
        String s = schema(mandant);
        UUID gespraech = UUID.randomUUID();
        Instant jetzt = Instant.now();
        String benutzer = Testanmeldung.sub("inhaber-" + mandant);
        try (Connection verbindung = datenbank.get().getConnection();
             PreparedStatement gespräch = verbindung.prepareStatement("insert into " + s
                     + ".chat_gespraech (id, benutzer, titel, modus, erstellt, geaendert) "
                     + "values (?, ?, ?, ?, ?, ?)")) {
            gespräch.setObject(1, gespraech);
            gespräch.setString(2, benutzer);
            gespräch.setString(3, "Synthetische Auswertung");
            gespräch.setString(4, Chatmodus.valueOf(modus).name());
            gespräch.setTimestamp(5, Timestamp.from(jetzt));
            gespräch.setTimestamp(6, Timestamp.from(jetzt));
            gespräch.executeUpdate();
        }
        gespraeche.computeIfAbsent(mandant, ignored -> new ArrayList<>()).add(gespraech);
        try (Connection verbindung = datenbank.get().getConnection();
             PreparedStatement frage = verbindung.prepareStatement("insert into " + s
                     + ".chat_nachricht (gespraech_id, rolle, inhalt, zeit) values (?, 'BENUTZER', ?, ?)")) {
            frage.setObject(1, gespraech);
            frage.setString(2, frageText);
            frage.setTimestamp(3, Timestamp.from(jetzt));
            frage.executeUpdate();
        }
        long nachricht;
        try (Connection verbindung = datenbank.get().getConnection();
             PreparedStatement antwort = verbindung.prepareStatement("insert into " + s
                     + ".chat_nachricht (gespraech_id, rolle, inhalt, status, quellen, zeit) "
                     + "values (?, 'ASSISTENT', ?, 'OK', ?::jsonb, ?) returning id")) {
            antwort.setObject(1, gespraech);
            antwort.setString(2, ANTWORT);
            antwort.setString(3, "[]");
            antwort.setTimestamp(4, Timestamp.from(jetzt));
            try (ResultSet rs = antwort.executeQuery()) {
                rs.next();
                nachricht = rs.getLong(1);
            }
        }
        try (Connection verbindung = datenbank.get().getConnection();
             PreparedStatement bewertung = verbindung.prepareStatement("insert into " + s
                     + ".chat_bewertung (nachricht_id, benutzer, sterne, grund, freitext, erstellt, geaendert) "
                     + "values (?, ?, ?, ?, ?, ?, ?)")) {
            bewertung.setLong(1, nachricht);
            bewertung.setString(2, benutzer);
            bewertung.setInt(3, sterne);
            bewertung.setString(4, sterne <= 2 ? "FALSCH" : null);
            bewertung.setString(5, "Synthetischer Exporttext");
            bewertung.setTimestamp(6, Timestamp.from(jetzt));
            bewertung.setTimestamp(7, Timestamp.from(jetzt));
            bewertung.executeUpdate();
        }
    }

    @Angenommen("im Mandanten «{word}» gibt es {int} synthetische Chat-Bewertungen")
    public void vieleBewertungenAnlegen(String mandant, int anzahl) throws SQLException {
        String s = schema(mandant);
        UUID gespraech = UUID.randomUUID();
        Instant jetzt = Instant.now();
        String benutzer = Testanmeldung.sub("inhaber-" + mandant);
        try (Connection verbindung = datenbank.get().getConnection();
             PreparedStatement gespräch = verbindung.prepareStatement("insert into " + s
                     + ".chat_gespraech (id, benutzer, titel, erstellt, geaendert) values (?, ?, ?, ?, ?)")) {
            gespräch.setObject(1, gespraech);
            gespräch.setString(2, benutzer);
            gespräch.setString(3, "Synthetische Auswertung");
            gespräch.setTimestamp(4, Timestamp.from(jetzt));
            gespräch.setTimestamp(5, Timestamp.from(jetzt));
            gespräch.executeUpdate();
        }
        gespraeche.computeIfAbsent(mandant, ignored -> new ArrayList<>()).add(gespraech);
        try (Connection verbindung = datenbank.get().getConnection();
             PreparedStatement daten = verbindung.prepareStatement("with antworten as ("
                     + "insert into " + s + ".chat_nachricht "
                     + "(gespraech_id, rolle, inhalt, status, quellen, zeit) "
                     + "select ?, 'ASSISTENT', 'Synthetische Antwort', 'OK', '[]'::jsonb, "
                     + "?::timestamptz + nummer * interval '1 millisecond' "
                     + "from generate_series(1, ?) as reihe(nummer) "
                     + "returning id) insert into " + s + ".chat_bewertung "
                     + "(nachricht_id, benutzer, sterne, erstellt, geaendert) "
                     + "select id, ?, 4, ?, ? from antworten")) {
            daten.setObject(1, gespraech);
            daten.setTimestamp(2, Timestamp.from(jetzt));
            daten.setInt(3, anzahl);
            daten.setString(4, benutzer);
            daten.setTimestamp(5, Timestamp.from(jetzt));
            daten.setTimestamp(6, Timestamp.from(jetzt));
            assertThat(daten.executeUpdate()).isEqualTo(anzahl);
        }
    }

    @Angenommen("für die Chat-Auswertung gibt es einen API-Token ohne Inhaberrolle")
    public void apiTokenAnlegen() {
        ApiTokenVerwaltung.Erstellt erstellt = apiTokens.erstellen("urinet", "Probe Chat-Auswertung", "import");
        apiClientIds.add(erstellt.client().id());
        apiToken = erstellt.token().text();
    }

    @Angenommen("für die Chat-Auswertung gibt es einen Webseiten-Schlüssel ohne Inhaberrolle")
    public void webseitenTokenAnlegen() {
        WebseitenSchluesselVerwaltung.Erstellt erstellt =
                webseitenSchluessel.erstellen("urinet", "probe-auswertung", "lesen", "oeffentlich", false);
        apiClientIds.add(erstellt.client().id());
        webseitenToken = erstellt.token().text();
    }

    @Wenn("«{word}» liest die Chat-Auswertung für die letzten 30 Tage")
    public void auswertungLesen(String benutzer) {
        LocalDate bis = LocalDate.now(ZoneId.of("Europe/Zurich"));
        letzteAntwort = given().auth().oauth2(Testanmeldung.token(benutzer))
                .queryParam("von", bis.minusDays(30).toString()).queryParam("bis", bis.toString())
                .when().get("/api/v1/chat/auswertung");
    }

    @Wenn("«{word}» exportiert die Chat-Auswertung für die letzten 30 Tage")
    public void auswertungExportieren(String benutzer) throws IOException {
        LocalDate bis = LocalDate.now(ZoneId.of("Europe/Zurich"));
        String token = Testanmeldung.anmelden(benutzer, true).path("access_token");
        urspruenglichesStepUp = ClientProxy.unwrap(stepUp);
        QuarkusMock.installMockForType(new StepUpMitAuthTime(null, Duration.ofMinutes(5)) {
            @Override
            public void pruefen() {
                stepUpGeprueft = true;
            }
        }, StepUp.class);
        letzteAntwort = given().auth().oauth2(token).contentType("application/json")
                .body(Map.of("von", bis.minusDays(30).toString(), "bis", bis.toString()))
                .when().post("/api/v1/chat/auswertung/export");
        if (letzteAntwort.statusCode() == 200) {
            zipDateien = entpacken(letzteAntwort.asByteArray());
        }
    }

    @Wenn("«{word}» exportiert die Chat-Auswertung ohne frische Anmeldung")
    public void auswertungOhneFrischeAnmeldungExportieren(String benutzer) {
        LocalDate bis = LocalDate.now(ZoneId.of("Europe/Zurich"));
        letzteAntwort = given().auth().oauth2(Testanmeldung.token(benutzer)).contentType("application/json")
                .body(Map.of("von", bis.minusDays(30).toString(), "bis", bis.toString()))
                .when().post("/api/v1/chat/auswertung/export");
    }

    @Wenn("der API-Token die Chat-Auswertung exportiert")
    public void apiTokenExportiert() {
        letzteAntwort = exportMitToken(apiToken);
    }

    @Wenn("der API-Token die Chat-Auswertung liest")
    public void apiTokenLiest() {
        letzteAntwort = auswertungMitToken(apiToken);
    }

    @Wenn("der Webseiten-Schlüssel die Chat-Auswertung exportiert")
    public void webseitenTokenExportiert() {
        letzteAntwort = exportMitToken(webseitenToken);
    }

    @Wenn("der Webseiten-Schlüssel die Chat-Auswertung liest")
    public void webseitenTokenLiest() {
        letzteAntwort = auswertungMitToken(webseitenToken);
    }

    @Dann("ist die Chat-Auswertung mit Status {int} beantwortet")
    public void auswertungStatus(int status) {
        assertThat(letzteAntwort.statusCode()).as("Problem-JSON: %s", letzteAntwort.asString()).isEqualTo(status);
    }

    @Dann("enthält die Auswertung {long} Bewertungen")
    public void auswertungAnzahl(long anzahl) {
        assertThat(letzteAntwort.jsonPath().getLong("anzahl")).isEqualTo(anzahl);
    }

    @Dann("ist der Chat-Auswertungs-Export mit Status {int} beantwortet")
    public void exportStatus(int status) {
        assertThat(letzteAntwort.statusCode()).as("Problem-JSON: %s", letzteAntwort.asString()).isEqualTo(status);
        if (status == 200) {
            assertThat(stepUpGeprueft).isTrue();
            assertThat(letzteAntwort.header("Content-Type")).startsWith("application/zip");
            assertThat(letzteAntwort.header("Cache-Control")).isEqualTo("no-store");
        }
    }

    @Dann("ist der Fehlercode der Chat-Auswertung «{}»")
    public void fehlercodePruefen(String fehlercode) {
        assertThat(letzteAntwort.jsonPath().getString("fehlercode")).isEqualTo(fehlercode);
    }

    private static Response exportMitToken(String token) {
        LocalDate bis = LocalDate.now(ZoneId.of("Europe/Zurich"));
        return given().header("Authorization", "Bearer " + token).contentType("application/json")
                .body(Map.of("von", bis.minusDays(30).toString(), "bis", bis.toString()))
                .when().post("/api/v1/chat/auswertung/export");
    }

    private static Response auswertungMitToken(String token) {
        LocalDate bis = LocalDate.now(ZoneId.of("Europe/Zurich"));
        return given().header("Authorization", "Bearer " + token)
                .queryParam("von", bis.minusDays(30).toString()).queryParam("bis", bis.toString())
                .when().get("/api/v1/chat/auswertung");
    }

    @Dann("enthält der Chat-Auswertungs-Export die Dateien «{}», «{}» und «{}»")
    public void exportDateien(String eins, String zwei, String drei) {
        assertThat(zipDateien.keySet()).contains(eins, zwei, drei);
    }

    @Dann("enthält der Chat-Auswertungs-Export {int} Bewertungen")
    public void exportAnzahl(int anzahl) {
        assertThat(zipDateien.get("bewertungen.jsonl").lines().count()).isEqualTo(anzahl);
    }

    @Dann("enthält der Chat-Auswertungs-Export den Text «{}» nicht")
    public void exportOhneText(String text) {
        assertThat(String.join("\n", zipDateien.values())).doesNotContain(text);
    }

    @Dann("enthält der Chat-Auswertungs-Export den Modus «{word}»")
    public void exportModus(String modus) throws IOException {
        JsonNode export = JSON.readTree(zipDateien.get("bewertungen.jsonl").lines().findFirst().orElseThrow());
        assertThat(export.path("modus").asText()).isEqualTo(modus);
    }

    @Dann("enthält der Chat-Auswertungs-Export die Modi ARCHIV und WISSEN")
    public void exportEnthaeltModi() throws IOException {
        ObjectMapper json = new ObjectMapper();
        List<String> modi = new ArrayList<>();
        for (String zeile : zipDateien.get("bewertungen.jsonl").lines().toList()) {
            modi.add(json.readTree(zeile).path("modus").asText());
        }
        assertThat(modi).containsExactlyInAnyOrder("ARCHIV", "WISSEN");
    }

    @Dann("umfasst der Wissensverbrauchsindex in «{word}» den Wissenschat")
    public void wissenChatIndex(String mandant) throws SQLException {
        try (Connection verbindung = datenbank.get().getConnection();
             PreparedStatement abfrage = verbindung.prepareStatement(
                     "select indexdef from pg_indexes where schemaname = ? and indexname = 'ki_verbrauch_wissen_zeit_ix'")) {
            abfrage.setString(1, schema(mandant));
            try (ResultSet rs = abfrage.executeQuery()) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString(1)).contains("WISSEN_INGEST", "WISSEN_LINT", "WISSEN_EMBEDDING", "WISSEN_CHAT");
            }
        }
    }

    @Dann("enthält der Chat-Auswertungs-Export keine Benutzer- oder Mandantenkennung")
    public void exportOhneIdentifikatoren() {
        String inhalt = String.join("\n", zipDateien.values());
        assertThat(inhalt).doesNotContain("inhaber-urinet", "m_urinet", "vhf-gsk",
                Testanmeldung.sub("inhaber-urinet"));
    }

    @Dann("ist der Export ohne Inhalte protokolliert")
    public void exportProtokolliert() throws SQLException {
        String sub = Testanmeldung.sub("inhaber-urinet");
        try (Connection verbindung = datenbank.get().getConnection();
             PreparedStatement abfrage = verbindung.prepareStatement("select details ->> 'grund' "
                     + "from m_urinet.zugriff_protokoll where aktion = 'CHAT_BEWERTUNG_EXPORT' and benutzer = ? "
                     + "order by zeit desc limit 1")) {
            abfrage.setString(1, sub);
            try (ResultSet rs = abfrage.executeQuery()) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString(1)).contains("von=", "bis=", "anzahl=1", "modus=GESCHWAERZT")
                        .doesNotContain("Ada Muster", "ada@example.test");
            }
        }
    }

    @Dann("enthält der Chat-Auswertungs-Export die Platzhalter für die synthetischen Personendaten")
    public void exportSchwaerztPersonendaten() {
        String inhalt = zipDateien.get("bewertungen.jsonl");
        assertThat(inhalt).contains("[E-MAIL]", "[TELEFON]", "[IBAN]", "[UID]", "[AHV-NUMMER]",
                "[BETRAG]", "[DATUM]")
                .doesNotContain("ada@example.test", "123 45 67", "CHE-123", "756.1234", "1'234.50", "08.11.2026");
    }

    @Dann("schwärzt der Export die bekannten Kontakt- und Partnernamen mit demselben Platzhalter")
    public void exportSchwaerztKontaktPartner() {
        String inhalt = zipDateien.get("bewertungen.jsonl");
        List<String> platzhalter = Pattern.compile("\\[PARTNER-\\d+]")
                .matcher(inhalt).results().map(MatchResult::group).toList();
        assertThat(platzhalter).hasSize(3).containsOnly(platzhalter.getFirst());
        assertThat(inhalt).doesNotContain("Müller AG", "Müller GmbH", "mueller");
    }

    private static Map<String, String> entpacken(byte[] bytes) throws IOException {
        Map<String, String> dateien = new HashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes), StandardCharsets.UTF_8)) {
            for (var eintrag = zip.getNextEntry(); eintrag != null; eintrag = zip.getNextEntry()) {
                dateien.put(eintrag.getName(), new String(zip.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        return dateien;
    }

    private static String schema(String mandant) {
        return switch (mandant) {
            case "urinet" -> "m_urinet";
            case "vhf-gsk" -> "m_vhf_gsk";
            default -> throw new IllegalArgumentException("Unbekannter Testmandant");
        };
    }
}
