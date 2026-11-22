package ch.urinet.archiv.konfiguration;

import ch.urinet.archiv.gemeinsam.Testanmeldung;
import ch.urinet.archiv.mandant.domain.MandantCode;
import io.agroal.api.AgroalDataSource;
import io.cucumber.java.After;
import io.cucumber.java.de.Angenommen;
import io.cucumber.java.de.Dann;
import io.cucumber.java.de.Wenn;
import io.restassured.RestAssured;
import io.restassured.response.Response;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

public class MandantEinstellungenSchritte {
    private static final String PFAD = "/api/v1/mandant/einstellungen";
    private static final String DATEI = "/api/v1/konfiguration";
    private static final String SCHEMA = new MandantCode("urinet").schema();

    @Inject Instance<AgroalDataSource> datenbank;

    private Response antwort;
    private Response ausgang;
    private Response andererMandant;
    private String etag;
    private String neuerName;
    private String neuesGeschaeftsjahr;
    private String uidVorher;
    private String uidNeu;
    private String statuszeile;
    private int ausgangsVersion;
    private long protokollAnzahl;
    private boolean geaendert;

    @Angenommen("die Ausgangsstände der Einstellungen sind gemerkt")
    public void ausgangsstaendeMerken() throws SQLException {
        ausgang = export("inhaber-urinet");
        andererMandant = export("inhaber-vhf-gsk");
        assertThat(ausgang.statusCode()).as(ausgang.asString()).isEqualTo(200);
        assertThat(andererMandant.statusCode()).as(andererMandant.asString()).isEqualTo(200);
        etag = ausgang.header("ETag");
        ausgangsVersion = ausgang.jsonPath().getInt("basisVersion");
        String bisher = ausgang.jsonPath().getString("einstellungen.branding.name");
        neuerName = "Testarchiv".equals(bisher) ? "Probe Archiv" : "Testarchiv";
        neuesGeschaeftsjahr = "06-30".equals(ausgang.jsonPath().getString("einstellungen.geschaeftsjahrEnde"))
                ? "09-30" : "06-30";
        uidVorher = ausgang.jsonPath().getString("einstellungen.kennungen.uid");
        protokollAnzahl = protokollAnzahl();
        geaendert = false;
    }

    @Wenn("«{word}» den Einstellungskatalog liest")
    public void katalogLesen(String benutzer) {
        antwort = given().auth().oauth2(Testanmeldung.token(benutzer)).get(PFAD);
    }

    @Dann("enthält der REST-Katalog Werte und Metadaten sowie den Betriebsstand")
    public void katalogPruefen() {
        assertThat(antwort.statusCode()).as(antwort.asString()).isEqualTo(200);
        assertThat(antwort.header("ETag")).isEqualTo(etag);
        assertThat(antwort.jsonPath().<Map<String, Object>>getList("katalog")).hasSize(12).allSatisfy(eintrag ->
                assertThat(eintrag).containsKeys("schluessel", "gruppe", "name", "beschreibung", "typ", "einheit",
                        "minimum", "maximum", "standard", "aktuellerWert", "eigenerWert", "riskant", "wirksamAb"));
        assertThat(antwort.jsonPath().getMap("betrieb.oeffentlicherChat"))
                .containsKeys("monat", "budgetTokens", "verbrauchteTokens");
        assertThat(antwort.jsonPath().getLong("betrieb.wissenMonatsbudgetTokens")).isPositive();
        assertThat(antwort.asString()).doesNotContain("alarmEmpfaenger", "secret", "passwort");
    }

    @Dann("enthält der REST-Katalog den Branding-Standard des zweiten Mandanten")
    public void zweitesBranding() {
        assertThat(antwort.statusCode()).as(antwort.asString()).isEqualTo(200);
        assertThat(antwort.jsonPath().getString("katalog.find { it.schluessel == 'branding.name' }.standard"))
                .isEqualTo("VHF/GSK Archiv");
    }

    @Wenn("die riskante ungültige REST-Änderung ohne Version gesendet wird")
    public void riskantOhneVersion() {
        riskant(null);
    }

    @Wenn("die riskante ungültige REST-Änderung mit veralteter Version gesendet wird")
    public void riskantVeraltet() {
        riskant("\"0\"");
    }

    @Wenn("die riskante ungültige REST-Änderung mit aktueller Version gesendet wird")
    public void riskantAktuell() {
        riskant(etag);
    }

    private void riskant(String version) {
        var anfrage = given().auth().oauth2(token()).contentType("application/merge-patch+json")
                .body("{\"chat\":{\"aufbewahrungMonate\":0}}");
        if (version != null) {
            anfrage.header("If-Match", version);
        }
        antwort = anfrage.patch(PFAD);
    }

    @Dann("antwortet die Einstellungsverwaltung mit Status {int}")
    public void status(int status) {
        assertThat(antwort.statusCode()).as(antwort.asString()).isEqualTo(status);
    }

    @Dann("antwortet die Einstellungsverwaltung mit Status {int} und ETag des Ausgangsstands")
    public void statusUndEtag(int status) {
        status(status);
        assertThat(antwort.header("ETag")).isEqualTo(etag);
    }

    @Dann("die REST-Antwort nennt die Auswirkung und «{word}»")
    public void auswirkung(String code) {
        assertThat(antwort.jsonPath().getString("fehlercode")).isEqualTo(code);
        assertThat(antwort.jsonPath().<Map<String, Object>>getList("auswirkung")).isNotEmpty()
                .allSatisfy(wirkung -> assertThat(wirkung).containsKeys("schluessel", "text", "betroffeneDatensaetze"));
    }

    @Wenn("die Änderung des Geschäftsjahresendes als REST-Vorschau gesendet wird")
    public void geschaeftsjahrVorschau() {
        antwort = given().auth().oauth2(token()).header("If-Match", etag)
                .contentType("application/merge-patch+json").body(geschaeftsjahr()).post(PFAD + "/vorschau");
    }

    @Dann("enthält die REST-Vorschau die Auswirkung zum Geschäftsjahresende")
    public void geschaeftsjahrAuswirkung() {
        assertThat(antwort.jsonPath().<Map<String, Object>>getList("auswirkungen")).anySatisfy(wirkung -> {
            assertThat(wirkung.get("schluessel")).isEqualTo("geschaeftsjahrEnde");
            assertThat(wirkung.get("text")).isNotNull();
        });
    }

    @Wenn("die Änderung des Geschäftsjahresendes ohne Bestätigung gesendet wird")
    public void geschaeftsjahrUnbestaetigt() {
        antwort = given().auth().oauth2(token()).header("If-Match", etag)
                .contentType("application/merge-patch+json").body(geschaeftsjahr()).patch(PFAD);
    }

    @Wenn("die Änderung des Geschäftsjahresendes mit Bestätigung gespeichert wird")
    public void geschaeftsjahrBestaetigt() {
        antwort = given().auth().oauth2(token()).header("If-Match", etag)
                .queryParam("bestaetigt", "geschaeftsjahrEnde")
                .contentType("application/merge-patch+json").body(geschaeftsjahr()).patch(PFAD);
        aenderungMerken();
    }

    @Wenn("ein ungültiger Archivname über REST gesendet wird")
    public void ungueltigerName() {
        antwort = given().auth().oauth2(token()).header("If-Match", etag)
                .contentType("application/merge-patch+json").body("{\"branding\":{\"name\":42}}").patch(PFAD);
    }

    @Dann("enthält die REST-Antwort einen Befund zum Archivnamen")
    public void befund() {
        assertThat(antwort.jsonPath().getString("fehlercode")).isEqualTo("KONFIGURATION_UNGUELTIG");
        assertThat(antwort.jsonPath().<Map<String, Object>>getList("befunde")).anySatisfy(eintrag -> {
            assertThat(eintrag.get("pfad")).isEqualTo("einstellungen.branding.name");
            assertThat(eintrag.get("meldung")).isNotNull();
        });
    }

    @Wenn("eine übergrosse REST-Anfrage ohne Inhaltslänge gesendet wird")
    public void chunkedZuGross() throws IOException {
        try (Socket socket = new Socket("127.0.0.1", RestAssured.port)) {
            socket.setSoTimeout(10_000);
            String kopf = "PATCH " + PFAD + " HTTP/1.1\r\nHost: localhost\r\n"
                    + "Authorization: Bearer " + token() + "\r\n"
                    + "Content-Type: application/json\r\nTransfer-Encoding: chunked\r\nConnection: close\r\n\r\n";
            var aus = socket.getOutputStream();
            aus.write(kopf.getBytes(StandardCharsets.US_ASCII));
            aus.write("4001\r\n".getBytes(StandardCharsets.US_ASCII));
            aus.write(" ".repeat(16 * 1024 + 1).getBytes(StandardCharsets.US_ASCII));
            aus.write("\r\n".getBytes(StandardCharsets.US_ASCII));
            aus.flush();
            statuszeile = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8)).readLine();
        }
    }

    @Dann("wird die REST-Anfrage mit Status {int} abgewiesen")
    public void chunkedStatus(int status) {
        assertThat(statuszeile).contains(" " + status + " ");
    }

    @Wenn("eine UID über REST geändert wird")
    public void uidAendern() {
        uidNeu = "CHE-123.456.789".equals(uidVorher) ? "CHE-987.654.321" : "CHE-123.456.789";
        patch("{\"kennungen\":{\"uid\":\"" + uidNeu + "\"}}");
    }

    @Dann("enthält das Änderungsprotokoll nur den Pfad und nicht die UID-Werte")
    public void uidNichtImProtokoll() throws SQLException {
        assertThat(antwort.jsonPath().getBoolean("geaendert")).isTrue();
        try (Connection verbindung = datenbank.get().getConnection(); Statement befehl = verbindung.createStatement();
             ResultSet zeile = befehl.executeQuery("select details::text from " + SCHEMA
                     + ".zugriff_protokoll where aktion = 'KONFIGURATION_AENDERN' order by id desc limit 1")) {
            assertThat(zeile.next()).isTrue();
            String details = zeile.getString(1);
            assertThat(details).contains("einstellungen.kennungen.uid", "geändert").doesNotContain(uidNeu);
            if (uidVorher != null) {
                assertThat(details).doesNotContain(uidVorher);
            }
        }
    }

    @Dann("sind Version und Änderungsprotokoll unverändert")
    public void unveraendert() throws SQLException {
        assertThat(export("inhaber-urinet").header("ETag")).isEqualTo(etag);
        assertThat(protokollAnzahl()).isEqualTo(protokollAnzahl);
    }

    @Wenn("die REST-Änderung des Archivnamens als Vorschau gesendet wird")
    public void vorschau() {
        antwort = given().auth().oauth2(token()).header("If-Match", etag)
                .contentType("application/merge-patch+json").body(branding(neuerName)).post(PFAD + "/vorschau");
    }

    @Wenn("die REST-Änderung des Archivnamens gespeichert wird")
    public void archivnameAendern() {
        patch(branding(neuerName));
    }

    @Wenn("der Archivname über REST auf null gesetzt wird")
    public void archivnameAufStandard() {
        patch("{\"branding\":{\"name\":null}}");
    }

    @Wenn("die Gruppe Branding über REST zurückgesetzt wird")
    public void gruppeZuruecksetzen() {
        zuruecksetzen("{\"gruppe\":\"BRANDING\"}");
    }

    @Wenn("die ursprüngliche Einstellungsversion über REST wiederhergestellt wird")
    public void versionZuruecksetzen() {
        zuruecksetzen("{\"version\":" + ausgangsVersion + "}");
    }

    private void patch(String body) {
        antwort = given().auth().oauth2(token()).header("If-Match", etag)
                .contentType("application/merge-patch+json").body(body).patch(PFAD);
        aenderungMerken();
    }

    private void zuruecksetzen(String body) {
        antwort = given().auth().oauth2(token()).header("If-Match", etag)
                .queryParam("bestaetigt", "geschaeftsjahrEnde,chat.aufbewahrungMonate,"
                        + "webseitenChat.aufbewahrungTage,wissen.monatsbudgetTokens")
                .contentType("application/json").body(body).post(PFAD + "/zuruecksetzen");
        aenderungMerken();
    }

    private void aenderungMerken() {
        assertThat(antwort.statusCode()).as(antwort.asString()).isEqualTo(200);
        etag = antwort.header("ETag");
        geaendert |= antwort.jsonPath().getBoolean("geaendert");
    }

    @Dann("ist eine neue APP-Version mit Änderungsprotokoll entstanden")
    public void neueVersionMitProtokoll() throws SQLException {
        assertThat(antwort.jsonPath().getBoolean("geaendert")).isTrue();
        assertThat(antwort.jsonPath().getInt("version")).isGreaterThan(ausgangsVersion);
        assertThat(protokollAnzahl()).isEqualTo(protokollAnzahl + 1);
        try (Connection verbindung = datenbank.get().getConnection(); Statement befehl = verbindung.createStatement();
             ResultSet zeile = befehl.executeQuery("select quelle from " + SCHEMA
                     + ".konfiguration_version order by version desc limit 1")) {
            assertThat(zeile.next()).isTrue();
            assertThat(zeile.getString(1)).isEqualTo("APP");
        }
    }

    @Dann("entsteht keine weitere Konfigurationsversion")
    public void keineVersion() throws SQLException {
        assertThat(antwort.jsonPath().getBoolean("geaendert")).isFalse();
        assertThat(protokollAnzahl()).isEqualTo(protokollAnzahl + 1);
    }

    @Dann("gilt für den Archivnamen der Standard")
    public void standard() {
        Response katalog = given().auth().oauth2(token()).get(PFAD);
        Map<String, Object> eintrag = katalog.jsonPath().getMap("katalog.find { it.schluessel == 'branding.name' }");
        assertThat(eintrag.get("eigenerWert")).isEqualTo(false);
        assertThat(eintrag.get("aktuellerWert")).isEqualTo(eintrag.get("standard"));
    }

    @Dann("entsprechen die Einstellungen wieder dem Ausgangsstand")
    public void ausgangWiederhergestellt() {
        assertThat(export("inhaber-urinet").jsonPath().<String, Object>getMap("einstellungen"))
                .isEqualTo(ausgang.jsonPath().getMap("einstellungen"));
        geaendert = false;
    }

    @Dann("ist der zweite Einstellungsstand unverändert")
    public void zweiterStandUnveraendert() {
        Response jetzt = export("inhaber-vhf-gsk");
        assertThat(jetzt.header("ETag")).isEqualTo(andererMandant.header("ETag"));
        assertThat(jetzt.asString()).isEqualTo(andererMandant.asString());
    }

    @After("@20-2-rest")
    public void aufraeumen() {
        if (geaendert) {
            etag = export("inhaber-urinet").header("ETag");
            versionZuruecksetzen();
            geaendert = false;
        }
    }

    private long protokollAnzahl() throws SQLException {
        try (Connection verbindung = datenbank.get().getConnection(); Statement befehl = verbindung.createStatement();
             ResultSet zeile = befehl.executeQuery("select count(*) from " + SCHEMA
                     + ".zugriff_protokoll where aktion = 'KONFIGURATION_AENDERN'")) {
            assertThat(zeile.next()).isTrue();
            return zeile.getLong(1);
        }
    }

    private static Response export(String benutzer) {
        return given().auth().oauth2(Testanmeldung.token(benutzer)).get(DATEI);
    }

    private static String token() {
        return Testanmeldung.token("inhaber-urinet");
    }

    private static String branding(String name) {
        return "{\"branding\":{\"name\":\"" + name + "\"}}";
    }

    private String geschaeftsjahr() {
        return "{\"geschaeftsjahrEnde\":\"" + neuesGeschaeftsjahr + "\"}";
    }
}
