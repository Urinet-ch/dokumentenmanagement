package ch.urinet.archiv.dokument;

import ch.urinet.archiv.gemeinsam.Testanmeldung;
import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.sicherheit.ProblemDetails;
import io.agroal.api.AgroalDataSource;
import io.cucumber.datatable.DataTable;
import io.cucumber.java.After;
import io.cucumber.java.de.Angenommen;
import io.cucumber.java.de.Dann;
import io.cucumber.java.de.Wenn;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Schritte zu Aufgabe 2.2 (Req 1.4, 2.3, 2.8–2.10, 2.21, 2.24, 9.5, 10.1, 10.3). Dokumente entstehen per JDBC
 * ({@link Testdokumente}) und werden nach jedem Szenario samt Dateien, Belegnummern und Fassungen gelöscht.
 */
public class DokumentSchritte {

    private record Ort(String mandant, UUID id) {
    }

    @Inject
    Instance<AgroalDataSource> datenbank;

    private final Map<String, Ort> dokumente = new LinkedHashMap<>();
    private Response antwort;
    private SQLException datenbankFehler;

    @After
    public void aufraeumen() throws SQLException {
        Map<String, List<UUID>> jeMandant = new HashMap<>();
        dokumente.values().forEach(o -> jeMandant.computeIfAbsent(o.mandant(), m -> new ArrayList<>()).add(o.id()));
        for (Map.Entry<String, List<UUID>> eintrag : jeMandant.entrySet()) {
            Testdokumente.loeschen(datenbank.get(), eintrag.getKey(), eintrag.getValue());
        }
        dokumente.clear();
    }

    @Angenommen("im Mandanten «{word}» liegen diese Dokumente:")
    public void dokumenteAnlegen(String mandant, DataTable tabelle) throws SQLException {
        for (Map<String, String> zeile : tabelle.asMaps()) {
            String person = zeile.get("person");
            UUID id = Testdokumente.anlegen(datenbank.get(), new Testdokumente.Angaben(mandant, zeile.get("art"),
                    zeile.get("vertraulichkeit"), "ja".equals(zeile.get("bestaetigt")),
                    Testanmeldung.sub(zeile.get("erfasser")), person == null ? null : Testanmeldung.sub(person),
                    zeile.get("status")));
            dokumente.put(zeile.get("name"), new Ort(mandant, id));
        }
    }

    @Wenn("«{word}» das Dokument «{word}» liest")
    public void lesen(String benutzer, String name) {
        antwort = given().auth().oauth2(Testanmeldung.token(benutzer)).when().get("/api/v1/dokumente/" + id(name));
    }

    @Dann("sehen die Benutzer die Dokumente so:")
    public void sichtbarkeit(DataTable tabelle) {
        for (Map<String, String> zeile : tabelle.asMaps()) {
            lesen(zeile.get("benutzer"), zeile.get("dokument"));
            String fall = zeile.get("benutzer") + " → " + zeile.get("dokument") + " (" + zeile.get("grund") + ")";
            int status = Integer.parseInt(zeile.get("status"));
            if (status == 200) {
                assertThat(antwort.statusCode()).as(fall).isEqualTo(200);
                assertThat(antwort.<String>path("id")).as(fall).isEqualTo(id(zeile.get("dokument")).toString());
            } else {
                assertThat(ProblemDetails.pruefen(antwort, status)).as(fall).isEqualTo("NICHT_GEFUNDEN");
            }
        }
    }

    @Wenn("«{word}» den Eingangskorb abruft")
    public void eingangskorb(String benutzer) {
        antwort = given().auth().oauth2(Testanmeldung.token(benutzer)).when().get("/api/v1/eingangskorb");
    }

    @Wenn("«{word}» den Eingangskorb mit Seite {int} und Grösse {int} abruft")
    public void eingangskorbSeite(String benutzer, int seite, int groesse) {
        antwort = given().auth().oauth2(Testanmeldung.token(benutzer)).queryParam("seite", seite)
                .queryParam("groesse", groesse).when().get("/api/v1/eingangskorb");
    }

    @Dann("liefert der Eingangskorb die Gesamtzahl {int} und diese Dokumente:")
    public void eingangskorbInhalt(int gesamt, List<String> namen) {
        assertThat(antwort.statusCode()).as(antwort.asString()).isEqualTo(200);
        assertThat(antwort.jsonPath().getLong("gesamt")).isEqualTo(gesamt);
        assertThat(antwort.jsonPath().<String>getList("eintraege.id"))
                .containsExactlyInAnyOrderElementsOf(namen.stream().map(n -> id(n).toString()).toList());
    }

    @Dann("liefert der Eingangskorb die Gesamtzahl {int} und {int} Dokument(e)")
    public void eingangskorbAnzahl(int gesamt, int anzahl) {
        assertThat(antwort.statusCode()).as(antwort.asString()).isEqualTo(200);
        assertThat(antwort.jsonPath().getLong("gesamt")).isEqualTo(gesamt);
        assertThat(antwort.jsonPath().<String>getList("eintraege")).hasSize(anzahl);
    }

    @Dann("antwortet der Dokument-Endpunkt mit Status {int}")
    public void status(int status) {
        assertThat(antwort.statusCode()).as(antwort.asString()).isEqualTo(status);
    }

    @Dann("antwortet der Dokument-Endpunkt mit Status {int} und Fehlercode «{word}»")
    public void fehlercode(int status, String fehlercode) {
        assertThat(ProblemDetails.pruefen(antwort, status)).as(antwort.asString()).isEqualTo(fehlercode);
    }

    /** Werte als Text; {@code null} heisst fehlend oder JSON-null. */
    @Dann("hat das Dokument in der Antwort diese Werte:")
    public void werte(Map<String, String> erwartet) {
        Map<String, Object> dokument = antwort.jsonPath().getMap("");
        erwartet.forEach((feld, wert) -> assertThat(String.valueOf(dokument.get(feld))).as(feld)
                .isEqualTo(wert == null ? "null" : wert));
    }

    @Dann("trägt die Antwort das ETag der Version des Dokuments")
    public void etag() {
        assertThat(antwort.header("ETag")).isEqualTo("\"" + antwort.<Integer>path("version") + "\"");
    }

    @Wenn("«{word}» die Zusatzfelder des Dokuments «{word}» auf {string} setzt")
    public void zusatz(String benutzer, String name, String zusatz) throws SQLException {
        zusatzSetzen(benutzer, name, version(name), zusatz, null);
    }

    @Wenn("«{word}» die Zusatzfelder des Dokuments «{word}» mit dem Grund {string} auf {string} setzt")
    public void zusatzMitGrund(String benutzer, String name, String grund, String zusatz) throws SQLException {
        zusatzSetzen(benutzer, name, version(name), zusatz, grund);
    }

    @Wenn("«{word}» die Zusatzfelder des Dokuments «{word}» mit der Version {int} auf {string} setzt")
    public void zusatzMitVersion(String benutzer, String name, int version, String zusatz) {
        zusatzSetzen(benutzer, name, version, zusatz, null);
    }

    @Angenommen("«{word}» hat die Zusatzfelder des Dokuments «{word}» mit einem Grund geändert")
    public void zusatzGeaendert(String benutzer, String name) throws SQLException {
        zusatzMitGrund(benutzer, name, "Korrektur", "{}");
        status(200);
    }

    @Dann("hat das Dokument «{word}» {int} Fassung(en) in dokument_version")
    public void fassungen(String name, int anzahl) throws SQLException {
        assertThat(fassungenLesen(name)).hasSize(anzahl);
    }

    /** {@code geaendert_von} als Testbenutzer, {@code zusatz} aus der gesicherten Fassung, {@code gesetzt} = nicht null. */
    @Dann("hält die letzte Fassung des Dokuments «{word}» fest:")
    public void letzteFassung(String name, Map<String, String> erwartet) throws SQLException {
        List<Map<String, String>> fassungen = fassungenLesen(name);
        assertThat(fassungen).isNotEmpty();
        Map<String, String> letzte = fassungen.getLast();
        erwartet.forEach((feld, wert) -> {
            switch (feld) {
                case "geaendert_von" -> assertThat(letzte.get(feld)).as(feld).isEqualTo(Testanmeldung.sub(wert));
                case "zeit" -> assertThat(letzte.get(feld)).as(feld).isNotNull();
                default -> assertThat(letzte.get(feld)).as(feld).isEqualTo("null".equals(wert) ? null : wert);
            }
        });
    }

    @Dann("verweigert die Datenbank von «{word}» {word} auf dokument_version")
    public void nurEinfuegen(String mandant, String befehl) {
        String tabelle = new MandantCode(mandant).schema() + ".dokument_version";
        String sql = switch (befehl) {
            case "UPDATE" -> "update " + tabelle + " set grund = 'geändert'";
            case "DELETE" -> "delete from " + tabelle;
            case "TRUNCATE" -> "truncate " + tabelle;
            default -> throw new IllegalArgumentException(befehl);
        };
        assertThatThrownBy(() -> {
            try (Connection verbindung = datenbank.get().getConnection(); Statement anweisung = verbindung.createStatement()) {
                anweisung.execute(sql);
            }
        }).isInstanceOf(SQLException.class).hasMessageContaining("dokument_version: nur Einfügen erlaubt");
    }

    @Angenommen("das Dokument «{word}» hat das Original mit der Prüfsumme «{word}»")
    public void originalVorhanden(String name, String zeichen) {
        originalAnlegen(name, zeichen);
        dannAngenommen();
    }

    @Wenn("das Dokument «{word}» ein Original mit der Prüfsumme «{word}» erhält")
    public void originalAnlegen(String name, String zeichen) {
        Ort ort = dokumente.get(name);
        ausfuehren("insert into " + schema(ort) + ".dokument_datei (dokument_id, nr, rolle, sha256) "
                + "values (?, 1, 'ORIGINAL', ?)", ort.id(), zeichen.repeat(64));
    }

    /** Verwerfen setzt den Status von Dokument und Dateien (design.md «Verwerfen»). */
    @Wenn("das Dokument «{word}» verworfen ist")
    public void verwerfen(String name) {
        Ort ort = dokumente.get(name);
        ausfuehren("update " + schema(ort) + ".dokument_datei set status = 'VERWORFEN' where dokument_id = ?", ort.id());
        dannAngenommen();
        ausfuehren("update " + schema(ort) + ".dokument set status = 'VERWORFEN' where id = ?", ort.id());
        dannAngenommen();
    }

    @Angenommen("das Dokument «{word}» hat die eigene Belegnummer «{word}» der Art «{word}»")
    public void belegnummerVorhanden(String name, String nummer, String art) {
        belegnummer(name, nummer, art);
        dannAngenommen();
    }

    @Wenn("das Dokument «{word}» die eigene Belegnummer «{word}» der Art «{word}» erhält")
    public void belegnummer(String name, String nummer, String art) {
        Ort ort = dokumente.get(name);
        ausfuehren("insert into " + schema(ort) + ".belegnummer_eigen (art, belegnummer, dokument_id) "
                + "values (?, ?, ?)", art, nummer, ort.id());
    }

    @Dann("nimmt die Datenbank es an")
    public void dannAngenommen() {
        assertThat((Throwable) datenbankFehler).as("Datenbankfehler").isNull();
    }

    @Dann("weist die Datenbank es mit «{word}» ab")
    public void abgewiesen(String bedingung) {
        assertThat((Throwable) datenbankFehler).isNotNull();
        assertThat(datenbankFehler.getMessage()).contains(bedingung);
    }

    private void zusatzSetzen(String benutzer, String name, int version, String zusatz, String grund) {
        Map<String, Object> koerper = new HashMap<>();
        koerper.put("zusatz", zusatz);
        koerper.put("grund", grund);
        antwort = given().auth().oauth2(Testanmeldung.token(benutzer)).contentType(ContentType.JSON)
                .header("If-Match", "\"" + version + "\"").body(koerper)
                .when().patch("/api/v1/probe/dokumente/" + id(name) + "/zusatz");
    }

    private int version(String name) throws SQLException {
        Ort ort = dokumente.get(name);
        try (Connection verbindung = datenbank.get().getConnection(); PreparedStatement abfrage = verbindung
                .prepareStatement("select version from " + schema(ort) + ".dokument where id = ?")) {
            abfrage.setObject(1, ort.id());
            try (ResultSet zeile = abfrage.executeQuery()) {
                assertThat(zeile.next()).isTrue();
                return zeile.getInt(1);
            }
        }
    }

    private List<Map<String, String>> fassungenLesen(String name) throws SQLException {
        Ort ort = dokumente.get(name);
        List<Map<String, String>> fassungen = new ArrayList<>();
        try (Connection verbindung = datenbank.get().getConnection(); PreparedStatement abfrage = verbindung
                .prepareStatement("select dokument_id, version, geaendert_von, grund, zeit, "
                        + "daten -> 'dokument' ->> 'zusatz', daten -> 'dokument' ->> 'id' from " + schema(ort)
                        + ".dokument_version where dokument_id = ? order by id")) {
            abfrage.setObject(1, ort.id());
            try (ResultSet zeile = abfrage.executeQuery()) {
                while (zeile.next()) {
                    Map<String, String> fassung = new HashMap<>();
                    fassung.put("dokument_id", zeile.getString(1));
                    fassung.put("version", zeile.getString(2));
                    fassung.put("geaendert_von", zeile.getString(3));
                    fassung.put("grund", zeile.getString(4));
                    fassung.put("zeit", zeile.getString(5));
                    fassung.put("zusatz", zeile.getString(6));
                    assertThat(zeile.getString(7)).isEqualTo(ort.id().toString());
                    fassungen.add(fassung);
                }
            }
        }
        return fassungen;
    }

    private void ausfuehren(String sql, Object... parameter) {
        datenbankFehler = null;
        try (Connection verbindung = datenbank.get().getConnection();
             PreparedStatement anweisung = verbindung.prepareStatement(sql)) {
            for (int i = 0; i < parameter.length; i++) {
                anweisung.setObject(i + 1, parameter[i]);
            }
            anweisung.executeUpdate();
        } catch (SQLException e) {
            datenbankFehler = e;
        }
    }

    private UUID id(String name) {
        if (dokumente.containsKey(name)) {
            return dokumente.get(name).id();
        }
        return Testdokumente.NAMEN.get(name);
    }

    private static String schema(Ort ort) {
        return new MandantCode(ort.mandant()).schema();
    }
}
