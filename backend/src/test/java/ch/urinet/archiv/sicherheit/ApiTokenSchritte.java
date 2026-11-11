package ch.urinet.archiv.sicherheit;

import ch.urinet.archiv.gemeinsam.LogMitschnitt;
import ch.urinet.archiv.gemeinsam.Testanmeldung;
import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.sicherheit.adapter.in.kommando.TokenKommando;
import ch.urinet.archiv.sicherheit.application.ApiTokenVerwaltung;
import ch.urinet.archiv.sicherheit.domain.ApiToken;
import io.agroal.api.AgroalDataSource;
import io.cucumber.java.After;
import io.cucumber.java.de.Angenommen;
import io.cucumber.java.de.Dann;
import io.cucumber.java.de.Wenn;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Schritte zu Req 2.5 und 2.20 über die Verwaltung {@code /api/v1/api-tokens} und die Probe-Endpunkte unter src/test
 * ({@code ProbeTokenResource}). Tokens entstehen zur Laufzeit; jedes angelegte Token wird nach dem Szenario gelöscht.
 */
public class ApiTokenSchritte {

    private static final String VERWALTUNG = "/api/v1/api-tokens";

    @Inject
    ApiTokenVerwaltung verwaltung;

    @Inject
    Instance<AgroalDataSource> datenbank;

    @Inject
    TokenKommando kommando;

    /** Name im Szenario → Token-Text. */
    private final Map<String, String> tokens = new LinkedHashMap<>();
    private final List<String> tokenIds = new ArrayList<>();
    private final List<String> mandanten = new ArrayList<>();
    private final Map<String, Long> stand = new HashMap<>();
    /** Alles, was ein Szenario als Bearer-Wert gesendet hat: weder der ganze Wert noch sein Geheimnis darf ins Log. */
    private final List<String> gesendet = new ArrayList<>();
    private final SecureRandom zufall = new SecureRandom();
    private int exitCode;
    private String kommandoFehler;
    private LogMitschnitt mitschnitt;
    private Response antwort;

    @Angenommen("alle Logeinträge werden mitgeschnitten")
    public void mitschneiden() {
        mitschnitt = LogMitschnitt.starten();
    }

    @Angenommen("für «{word}» gibt es das Token «{word}» mit Scope «{word}»")
    public void tokenAnlegen(String mandant, String name, String scope) {
        ApiTokenVerwaltung.Erstellt erstellt = verwaltung.erstellen(mandant, "Probe " + name, scope);
        merken(name, erstellt.token().text());
    }

    @Angenommen("der aktive Mandant «{word}» steht nur im Register")
    public void mandantImRegister(String code) throws SQLException {
        mandanten.add(code);
        ausfuehren("insert into public.mandant (code, name, schema_name, bucket, aktiv) values (?, ?, ?, ?, true) "
                + "on conflict (code) do update set aktiv = true", code, code, new MandantCode(code).schema(),
                "urinet-archiv-" + code);
    }

    @Angenommen("der Mandant «{word}» wird deaktiviert")
    public void deaktivieren(String code) throws SQLException {
        ausfuehren("update public.mandant set aktiv = false where code = ?", code);
    }

    @Angenommen("der Stand des Zugriffsprotokolls von «{word}» ist für die Tokens gemerkt")
    public void standMerken(String mandant) throws SQLException {
        stand.put(mandant, Long.parseLong(abfragen("select coalesce(max(id), 0) as stand from " + schema(mandant)
                + ".zugriff_protokoll").getFirst().get("stand")));
    }

    @Wenn("das Token «{word}» an «{}» sendet")
    public void senden(String name, String pfad) {
        antwort = mitToken(tokens.get(name)).when().post(pfad);
    }

    @Wenn("das Token «{word}» {word} «{}» mit einem leeren JSON-Objekt aufruft")
    public void aufrufen(String name, String methode, String pfad) {
        antwort = mitToken(tokens.get(name)).contentType(ContentType.JSON).body("{}").when().request(methode, pfad);
    }

    @Wenn("das Token «{word}» mit falschem Geheimnis an «{}» sendet")
    public void falschesGeheimnis(String name, String pfad) {
        String id = ApiToken.lesen(tokens.get(name)).orElseThrow().id();
        String fremdesGeheimnis = ApiToken.neu(zufall).geheimnis();
        antwort = gesendet(ApiToken.PRAEFIX + id + "_" + fremdesGeheimnis).when().post(pfad);
    }

    @Wenn("das Token «{word}» mit fremder ID an «{}» sendet")
    public void fremdeId(String name, String pfad) {
        String geheimnis = ApiToken.lesen(tokens.get(name)).orElseThrow().geheimnis();
        String fremdeId = ApiToken.neu(zufall).id();
        antwort = gesendet(ApiToken.PRAEFIX + fremdeId + "_" + geheimnis).when().post(pfad);
    }

    @Wenn("ein Token mit unbekannter ID an «{}» sendet")
    public void unbekannteId(String pfad) {
        antwort = gesendet(ApiToken.neu(zufall).text()).when().post(pfad);
    }

    @Wenn("ein Bearer-Token «{word}» an «{}» sendet")
    public void kaputtesToken(String wert, String pfad) {
        antwort = gesendet(wert).when().post(pfad);
    }

    @Wenn("«{word}» ein Token «{word}» mit Scope «{word}» anlegt")
    public void perRestAnlegen(String benutzer, String name, String scope) {
        antwort = given().auth().oauth2(Testanmeldung.token(benutzer)).contentType(ContentType.JSON)
                .body(Map.of("name", "Probe " + name, "scope", scope)).when().post(VERWALTUNG);
        if (antwort.statusCode() == 201) {
            merken(name, antwort.path("token"));
        }
    }

    @Wenn("«{word}» die Tokens auflistet")
    public void auflisten(String benutzer) {
        antwort = given().auth().oauth2(Testanmeldung.token(benutzer)).when().get(VERWALTUNG);
    }

    @Wenn("«{word}» das Token «{word}» widerruft")
    public void widerrufen(String benutzer, String name) {
        antwort = given().auth().oauth2(Testanmeldung.token(benutzer)).when()
                .delete(VERWALTUNG + "/" + ApiToken.lesen(tokens.get(name)).orElseThrow().id());
    }

    @Wenn("«{word}» ein Token mit der ID «{word}» widerruft")
    public void widerrufenId(String benutzer, String id) {
        antwort = given().auth().oauth2(Testanmeldung.token(benutzer)).when().delete(VERWALTUNG + "/" + id);
    }

    @Wenn("das Token «{word}» die Tokens auflistet")
    public void tokenListet(String name) {
        antwort = mitToken(tokens.get(name)).when().get(VERWALTUNG);
    }

    @Wenn("das Token «{word}» ein Token anlegt")
    public void tokenLegtAn(String name) {
        antwort = mitToken(tokens.get(name)).contentType(ContentType.JSON)
                .body(Map.of("name", "Probe von Token", "scope", "import")).when().post(VERWALTUNG);
    }

    @Wenn("das Token «{word}» das Token «{word}» widerruft")
    public void tokenWiderruft(String name, String ziel) {
        antwort = mitToken(tokens.get(name)).when()
                .delete(VERWALTUNG + "/" + ApiToken.lesen(tokens.get(ziel)).orElseThrow().id());
    }

    @Wenn("«{word}» ein Token mit unbekannter gültiger ID widerruft")
    public void widerrufenUnbekannt(String benutzer) {
        widerrufenId(benutzer, ApiToken.neu(zufall).id());
    }

    @Wenn("das Kommando «token erstellen {word} {word} {word}» läuft")
    public void kommandoLaeuft(String mandant, String name, String scope) {
        ByteArrayOutputStream aus = new ByteArrayOutputStream();
        ByteArrayOutputStream fehler = new ByteArrayOutputStream();
        exitCode = kommando.ausfuehren(List.of("erstellen", mandant, name, scope),
                new PrintStream(aus, true, StandardCharsets.UTF_8), new PrintStream(fehler, true, StandardCharsets.UTF_8));
        assertThat(aus.toString(StandardCharsets.UTF_8)).as("kein Token auf stdout").isEmpty();
        kommandoFehler = fehler.toString(StandardCharsets.UTF_8);
    }

    @Dann("endet das Kommando mit Exit {int} und meldet «{word}»")
    public void kommandoEndet(int erwartet, String fehlercode) {
        assertThat(exitCode).as(kommandoFehler).isEqualTo(erwartet);
        assertThat(kommandoFehler).contains(fehlercode);
    }

    @Dann("antwortet das Archiv auf die Token-Anfrage mit Status {int}")
    public void status(int status) {
        assertThat(antwort.statusCode()).as(antwort.asString()).isEqualTo(status);
    }

    @Dann("antwortet das Archiv auf die Token-Anfrage mit Status {int} und Fehlercode «{word}»")
    public void fehlercode(int status, String fehlercode) {
        assertThat(ProblemDetails.pruefen(antwort, status)).isEqualTo(fehlercode);
        geheimnisseFehlenIn(antwort.asString());
    }

    @Dann("enthält die Antwort das Token «{word}» genau dieses eine Mal, nicht zwischenspeicherbar")
    public void tokenEinmal(String name) {
        assertThat(antwort.statusCode()).isEqualTo(201);
        assertThat(antwort.header("Cache-Control")).isEqualTo("no-store");
        String token = tokens.get(name);
        assertThat(ApiToken.lesen(token)).isPresent();
        assertThat(antwort.<String>path("id")).isEqualTo(ApiToken.lesen(token).orElseThrow().id());
        assertThat(antwort.<String>path("scope")).isNotBlank();
    }

    @Dann("enthält die Liste das Token «{word}» mit aktiv {word}, ohne Geheimnis und ohne Hash")
    public void inListe(String name, String aktiv) {
        assertThat(antwort.statusCode()).isEqualTo(200);
        String id = ApiToken.lesen(tokens.get(name)).orElseThrow().id();
        List<Map<String, Object>> liste = antwort.jsonPath().getList("");
        Map<String, Object> eintrag = liste.stream().filter(e -> id.equals(e.get("id"))).findFirst().orElseThrow();
        assertThat(eintrag).containsOnlyKeys("id", "name", "scope", "aktiv", "erstellt", "zuletzt")
                .containsEntry("name", "Probe " + name).containsEntry("aktiv", Boolean.parseBoolean(aktiv));
        assertThat(eintrag.get("erstellt")).isNotNull();
        assertThat(antwort.asString()).doesNotContain("argon2");
        geheimnisseFehlenIn(antwort.asString());
    }

    @Dann("ist beim Token «{word}» die letzte Verwendung eingetragen")
    public void zuletzt(String name) {
        String id = ApiToken.lesen(tokens.get(name)).orElseThrow().id();
        List<Map<String, Object>> liste = antwort.jsonPath().getList("");
        assertThat(liste).filteredOn(e -> id.equals(e.get("id"))).singleElement()
                .satisfies(e -> assertThat(e.get("zuletzt")).isNotNull());
    }

    @Dann("enthält die Liste das Token «{word}» nicht")
    public void nichtInListe(String name) {
        assertThat(antwort.statusCode()).isEqualTo(200);
        assertThat(antwort.asString()).doesNotContain(ApiToken.lesen(tokens.get(name)).orElseThrow().id());
    }

    @Dann("das Zugriffsprotokoll von «{word}» hat für das Token «{word}» genau einen Eintrag «{word}» ohne Rolle")
    public void protokoll(String mandant, String name, String aktion) throws SQLException {
        String id = ApiToken.lesen(tokens.get(name)).orElseThrow().id();
        List<Map<String, String>> eintraege = abfragen("select benutzer, rolle, aktion from " + schema(mandant)
                + ".zugriff_protokoll where benutzer = ?", ApiToken.principal(id));
        assertThat(eintraege).singleElement().satisfies(e -> {
            assertThat(e.get("aktion")).isEqualTo(aktion);
            assertThat(e.get("rolle")).isNull();
        });
    }

    @Dann("das Zugriffsprotokoll von «{word}» hat seit dem gemerkten Stand keinen Eintrag eines Tokens")
    public void keinTokenEintrag(String mandant) throws SQLException {
        assertThat(abfragen("select benutzer from " + schema(mandant) + ".zugriff_protokoll where id > "
                + stand.get(mandant) + " and benutzer like 'token:%'")).isEmpty();
    }

    @Dann("kein Logeintrag enthält das Geheimnis eines Tokens")
    public void logOhneGeheimnis() {
        assertThat(tokens).isNotEmpty();
        geheimnisseFehlenIn(mitschnitt.volltext());
    }

    @Dann("kein Logeintrag enthält einen gesendeten Token-Wert oder das Geheimnis eines Tokens")
    public void logOhneGesendeteWerte() {
        assertThat(gesendet).isNotEmpty();
        logOhneGeheimnis();
    }

    @After
    public void aufraeumen() throws SQLException {
        if (mitschnitt != null) {
            mitschnitt.close();
        }
        for (String id : tokenIds) {
            ausfuehren("delete from public.api_client where id = ?", id);
        }
        for (String code : mandanten) {
            ausfuehren("delete from public.api_client where mandant = ?", code);
            ausfuehren("delete from public.mandant where code = ?", code);
        }
    }

    private void merken(String name, String token) {
        tokens.put(name, token);
        tokenIds.add(ApiToken.lesen(token).orElseThrow().id());
    }

    private void geheimnisseFehlenIn(String text) {
        for (String token : tokens.values()) {
            assertThat(text).doesNotContain(ApiToken.lesen(token).orElseThrow().geheimnis());
        }
        for (String wert : gesendet) {
            assertThat(text).as("gesendeter Wert im Text").doesNotContain(wert);
            ApiToken.lesen(wert).ifPresent(t -> assertThat(text).doesNotContain(t.geheimnis()));
        }
    }

    private RequestSpecification gesendet(String wert) {
        gesendet.add(wert);
        return mitToken(wert);
    }

    private static RequestSpecification mitToken(String token) {
        return given().header("Authorization", "Bearer " + token);
    }

    private static String schema(String mandant) {
        return new MandantCode(mandant).schema();
    }

    private void ausfuehren(String sql, String... werte) throws SQLException {
        try (Connection verbindung = datenbank.get().getConnection();
             PreparedStatement befehl = verbindung.prepareStatement(sql)) {
            for (int i = 0; i < werte.length; i++) {
                befehl.setString(i + 1, werte[i]);
            }
            befehl.executeUpdate();
        }
    }

    private List<Map<String, String>> abfragen(String sql, String... werte) throws SQLException {
        try (Connection verbindung = datenbank.get().getConnection();
             PreparedStatement befehl = verbindung.prepareStatement(sql)) {
            for (int i = 0; i < werte.length; i++) {
                befehl.setString(i + 1, werte[i]);
            }
            List<Map<String, String>> zeilen = new ArrayList<>();
            try (ResultSet zeile = befehl.executeQuery()) {
                int spalten = zeile.getMetaData().getColumnCount();
                while (zeile.next()) {
                    Map<String, String> werteDerZeile = new LinkedHashMap<>();
                    for (int s = 1; s <= spalten; s++) {
                        werteDerZeile.put(zeile.getMetaData().getColumnLabel(s), zeile.getString(s));
                    }
                    zeilen.add(werteDerZeile);
                }
            }
            return zeilen;
        }
    }
}
