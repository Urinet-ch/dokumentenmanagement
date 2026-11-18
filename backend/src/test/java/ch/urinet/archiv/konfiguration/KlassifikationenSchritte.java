package ch.urinet.archiv.konfiguration;

import ch.urinet.archiv.dokument.Testdokumente;
import ch.urinet.archiv.gemeinsam.Testanmeldung;
import ch.urinet.archiv.konfiguration.domain.ArtKlassifikation;
import ch.urinet.archiv.konfiguration.domain.Klassifikation;
import ch.urinet.archiv.konfiguration.domain.KlassifikationWert;
import ch.urinet.archiv.konfiguration.domain.Konfiguration;
import ch.urinet.archiv.mandant.domain.MandantCode;
import io.agroal.api.AgroalDataSource;
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
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

public class KlassifikationenSchritte {

    private record Zuordnung(String mandant, String art, String klassifikation) {
    }

    private record Dokument(String mandant, UUID id) {
    }

    private record Eigene(String mandant, String code) {
    }

    @Inject
    Instance<AgroalDataSource> datenbank;

    private final List<Eigene> eigeneKlassifikationen = new ArrayList<>();
    private final List<Zuordnung> eigeneZuordnungen = new ArrayList<>();
    private final Map<String, Dokument> dokumente = new LinkedHashMap<>();
    private Response antwort;
    private Map<String, List<String>> vorbelegung;

    @Wenn("«{word}» legt die Klassifikation mit dem aktuellen Stand an:")
    public void anlegen(String benutzer, String body) {
        String code = io.restassured.path.json.JsonPath.from(body).getString("code");
        eigeneKlassifikationen.add(new Eigene(mandant(benutzer), code));
        antwort = mitVersion(benutzer).contentType(ContentType.JSON).body(body)
                .when().post("/api/v1/klassifikationen");
    }

    @Wenn("«{word}» ordnet die Klassifikation «{word}» der Dokumentart «{word}» mit dem Standardwert «{word}» zu")
    public void zuordnen(String benutzer, String klassifikation, String art, String standardWert) {
        eigeneZuordnungen.add(new Zuordnung(mandant(benutzer), art, klassifikation));
        antwort = mitVersion(benutzer).contentType(ContentType.JSON)
                .body(Map.of("klassifikation", klassifikation, "pflicht", false, "standardWert", standardWert))
                .when().post("/api/v1/dokumentarten/" + art + "/klassifikationen");
    }

    @Wenn("«{word}» ordnet die Klassifikation «{word}» der Dokumentart «{word}» als Pflicht ohne Standardwert zu")
    public void alsPflichtZuordnen(String benutzer, String klassifikation, String art) {
        eigeneZuordnungen.add(new Zuordnung(mandant(benutzer), art, klassifikation));
        antwort = mitVersion(benutzer).contentType(ContentType.JSON)
                .body(Map.of("klassifikation", klassifikation, "pflicht", true))
                .when().post("/api/v1/dokumentarten/" + art + "/klassifikationen");
    }

    @Wenn("«{word}» setzt den Standardwert der Klassifikation «{word}» der Dokumentart «{word}» auf «{word}»")
    public void standardwertSetzen(String benutzer, String klassifikation, String art, String standardWert) {
        antwort = mitVersion(benutzer).contentType(ContentType.JSON).body(Map.of("standardWert", standardWert))
                .when().patch("/api/v1/dokumentarten/" + art + "/klassifikationen/" + klassifikation);
    }

    @Wenn("«{word}» benennt die Klassifikation «{word}» in «{word}» um")
    public void klassifikationUmbenennen(String benutzer, String code, String name) {
        antwort = mitVersion(benutzer).contentType(ContentType.JSON).body(Map.of("name", name))
                .when().patch("/api/v1/klassifikationen/" + code);
    }

    @Wenn("«{word}» benennt den Wert «{word}» der Klassifikation «{word}» in «{word}» um")
    public void wertUmbenennen(String benutzer, String wert, String code, String name) {
        antwort = mitVersion(benutzer).contentType(ContentType.JSON).body(Map.of("name", name))
                .when().patch("/api/v1/klassifikationen/" + code + "/werte/" + wert);
    }

    @Wenn("«{word}» liest die Klassifikationen")
    public void klassifikationenLesen(String benutzer) {
        antwort = gegeben(benutzer).when().get("/api/v1/klassifikationen");
    }

    @Wenn("«{word}» liest den Eingangskorb")
    public void eingangskorbLesen(String benutzer) {
        antwort = gegeben(benutzer).queryParam("groesse", 100).when().get("/api/v1/eingangskorb");
    }

    @Wenn("«{word}» entfernt den Standardwert «{word}» der Klassifikation «{word}» von der Dokumentart «{word}»")
    public void standardwertEntfernen(String benutzer, String standardWert, String klassifikation, String art) {
        antwort = mitVersion(benutzer).contentType(ContentType.JSON).body("{\"standardWert\":null}")
                .when().patch("/api/v1/dokumentarten/" + art + "/klassifikationen/" + klassifikation);
    }

    @Wenn("«{word}» versucht, den Code der Klassifikation «{word}» auf «{word}» zu ändern")
    public void codeAendern(String benutzer, String code, String neuerCode) {
        antwort = mitVersion(benutzer).contentType(ContentType.JSON).body(Map.of("code", neuerCode))
                .when().patch("/api/v1/klassifikationen/" + code);
    }

    @Wenn("«{word}» versucht, die Klassifikation «{word}» zu löschen")
    public void klassifikationLoeschen(String benutzer, String code) {
        antwort = mitVersion(benutzer).when().delete("/api/v1/klassifikationen/" + code);
    }

    @Wenn("«{word}» versucht, den Wert «{word}» der Klassifikation «{word}» zu löschen")
    public void wertLoeschen(String benutzer, String wert, String code) {
        antwort = mitVersion(benutzer).when().delete("/api/v1/klassifikationen/" + code + "/werte/" + wert);
    }

    @Wenn("«{word}» deaktiviert den Wert «{word}» der Klassifikation «{word}»")
    public void wertDeaktivieren(String benutzer, String wert, String code) {
        antwort = mitVersion(benutzer).contentType(ContentType.JSON).body(Map.of("aktiv", false))
                .when().patch("/api/v1/klassifikationen/" + code + "/werte/" + wert);
    }

    @Wenn("«{word}» entfernt die Zuordnung «{word}» von der Dokumentart «{word}»")
    public void zuordnungEntfernen(String benutzer, String code, String art) {
        antwort = mitVersion(benutzer).when().delete("/api/v1/dokumentarten/" + art + "/klassifikationen/" + code);
        if (antwort.statusCode() == 204) {
            eigeneZuordnungen.remove(new Zuordnung(mandant(benutzer), art, code));
        }
    }

    @Angenommen("«{word}» legt ein Dokument «{word}» der Art «{word}» mit der Klassifikation «{word}» und dem Wert «{word}» im Status «{word}» an")
    public void dokumentAnlegen(String benutzer, String name, String art, String code, String wert, String status)
            throws SQLException {
        String mandant = benutzer.substring(benutzer.indexOf('-') + 1);
        UUID id = Testdokumente.anlegen(datenbank.get(), new Testdokumente.Angaben(mandant, art, "NORMAL",
                "ARCHIVIERT".equals(status), Testanmeldung.sub(benutzer), null, status));
        dokumente.put(name, new Dokument(mandant, id));
        if (!"ohne".equals(code)) {
            try (Connection verbindung = datenbank.get().getConnection();
                 PreparedStatement einfuegen = verbindung.prepareStatement("insert into "
                         + new MandantCode(mandant).schema()
                         + ".dokument_klassifikation (dokument_id, klassifikation, wert) values (?, ?, ?)")) {
                einfuegen.setObject(1, id);
                einfuegen.setString(2, code);
                einfuegen.setString(3, wert);
                einfuegen.executeUpdate();
            }
        }
    }

    @Angenommen("das Dokument «{word}» hat den freien Tag «{word}»")
    public void freienTagSetzen(String name, String tag) throws SQLException {
        Dokument dokument = dokument(name);
        try (Connection verbindung = datenbank.get().getConnection();
             PreparedStatement aendern = verbindung.prepareStatement("update "
                     + new MandantCode(dokument.mandant()).schema() + ".dokument set tags = array[?] where id = ?")) {
            aendern.setString(1, tag);
            aendern.setObject(2, dokument.id());
            assertThat(aendern.executeUpdate()).isEqualTo(1);
        }
    }

    @Wenn("«{word}» liest das Dokument «{word}»")
    public void dokumentLesen(String benutzer, String name) {
        antwort = gegeben(benutzer).when().get("/api/v1/dokumente/" + dokument(name).id());
    }

    @Wenn("«{word}» ändert die Klassifikationen des Dokuments «{word}» auf:")
    public void dokumentKlassifikationenAendern(String benutzer, String name, String body) {
        Response stand = gegeben(benutzer).when().get("/api/v1/dokumente/" + dokument(name).id());
        assertThat(stand.statusCode()).as(stand.asString()).isEqualTo(200);
        antwort = gegeben(benutzer).header("If-Match", stand.getHeader("ETag")).contentType(ContentType.JSON)
                .body("{\"klassifikationen\":" + body + ",\"grund\":\"Klassifikationen aktualisieren\"}")
                .when().patch("/api/v1/dokumente/" + dokument(name).id());
    }

    @Wenn("«{word}» wechselt die Art des Dokuments «{word}» auf «{word}»")
    public void artWechseln(String benutzer, String name, String art) {
        Response stand = gegeben(benutzer).when().get("/api/v1/dokumente/" + dokument(name).id());
        assertThat(stand.statusCode()).as(stand.asString()).isEqualTo(200);
        antwort = gegeben(benutzer).header("If-Match", stand.getHeader("ETag")).contentType(ContentType.JSON)
                .body(Map.of("art", art, "projekt", "Probe", "grund", "Art wechseln"))
                .when().patch("/api/v1/dokumente/" + dokument(name).id());
        if (antwort.statusCode() == 200) {
            antwort = gegeben(benutzer).when().get("/api/v1/dokumente/" + dokument(name).id());
        }
    }

    @Wenn("«{word}» bestätigt das Dokument «{word}» mit diesen Angaben:")
    public void dokumentBestaetigen(String benutzer, String name, String body) {
        Response stand = gegeben(benutzer).when().get("/api/v1/dokumente/" + dokument(name).id());
        assertThat(stand.statusCode()).as(stand.asString()).isEqualTo(200);
        antwort = gegeben(benutzer).header("If-Match", stand.getHeader("ETag")).contentType(ContentType.JSON)
                .body(body).when().post("/api/v1/dokumente/" + dokument(name).id() + "/bestaetigen");
    }

    @Wenn("eine Test-Konfiguration beim Artwechsel Klassifikationen vorbelegt")
    public void artwechselVorbelegen() {
        Klassifikation fachgebiet = new Klassifikation("fachgebiet", "Fachgebiet", null, List.of(
                new KlassifikationWert("bau", "Bau", null, 0, true),
                new KlassifikationWert("elektro", "Elektro", null, 1, true)),
                true, false, true, false, true);
        Klassifikation alt = new Klassifikation("alt", "Alt", null,
                List.of(new KlassifikationWert("wert", "Wert", null, 0, true)),
                false, false, false, false, true);
        Klassifikation neu = new Klassifikation("neu", "Neu", null,
                List.of(new KlassifikationWert("standard", "Standard", null, 0, true)),
                false, false, false, false, true);
        Konfiguration.Art alteArt = art("ALT", List.of(new ArtKlassifikation("fachgebiet", false, null),
                new ArtKlassifikation("alt", false, null)));
        Konfiguration.Art neueArt = art("NEU", List.of(new ArtKlassifikation("fachgebiet", false, "bau"),
                new ArtKlassifikation("neu", false, "standard")));
        Konfiguration.Einstellungen einstellungen = new Konfiguration.Einstellungen("12-31",
                new Konfiguration.Kennungen(null, List.of()));
        Konfiguration konfiguration = new Konfiguration(1, einstellungen, List.of(alteArt, neueArt),
                List.of(fachgebiet, alt, neu));

        vorbelegung = konfiguration.beimArtwechselVorbelegen("NEU",
                Map.of("fachgebiet", List.of("elektro"), "alt", List.of("wert")));
    }

    @Dann("antwortet die Klassifikationsverwaltung mit Status {int}")
    public void status(int status) {
        assertThat(antwort.statusCode()).as(antwort.asString()).isEqualTo(status);
    }

    @Dann("antwortet die Klassifikationsverwaltung mit Status {int} und Fehlercode «{word}»")
    public void fehlercode(int status, String code) {
        assertThat(antwort.statusCode()).as(antwort.asString()).isEqualTo(status);
        assertThat(antwort.jsonPath().getString("fehlercode")).isEqualTo(code);
    }

    @Dann("enthält die Antwort den Wert «{word}» der Klassifikation")
    public void wertInAntwort(String wert) {
        assertThat(antwort.jsonPath().<String>getList("werte.code")).contains(wert);
    }

    @Dann("enthält die Antwort den Standardwert «{word}»")
    public void standardwertInAntwort(String wert) {
        assertThat(antwort.jsonPath().getString("standardWert")).isEqualTo(wert);
    }

    @Dann("enthält das Dokument «{word}» die Klassifikation «{word}» mit dem Wert «{word}»")
    public void dokumentHatKlassifikation(String name, String code, String wert) {
        assertThat(antwort.statusCode()).as(antwort.asString()).isEqualTo(200);
        assertThat(antwort.jsonPath().<String>getList("klassifikationen." + code)).contains(wert);
    }

    @Dann("enthält das Dokument «{word}» keine Klassifikation «{word}»")
    public void dokumentOhneKlassifikation(String name, String code) {
        assertThat(antwort.statusCode()).as(antwort.asString()).isEqualTo(200);
        assertThat(antwort.jsonPath().<String>getList("klassifikationen." + code)).isNullOrEmpty();
    }

    @Dann("ist die Klassifikation «{word}» des Dokuments «{word}» ungültig")
    public void dokumentKlassifikationUngueltig(String code, String name) {
        assertThat(antwort.statusCode()).as(antwort.asString()).isEqualTo(200);
        assertThat(antwort.jsonPath().<String>getList("klassifikationenUngueltig")).contains(code);
    }

    @Dann("ist keine Klassifikation des Dokuments «{word}» ungültig")
    public void dokumentKlassifikationenGueltig(String name) {
        assertThat(antwort.statusCode()).as(antwort.asString()).isEqualTo(200);
        assertThat(antwort.jsonPath().<String>getList("klassifikationenUngueltig")).isEmpty();
    }

    @Dann("ist im Eingangskorb die Klassifikation «{word}» des Dokuments «{word}» ungültig")
    public void eingangskorbKlassifikationUngueltig(String code, String name) {
        assertThat(eingangskorbMarkierung(name)).contains(code);
    }

    @Dann("ist im Eingangskorb keine Klassifikation des Dokuments «{word}» ungültig")
    public void eingangskorbKlassifikationenGueltig(String name) {
        assertThat(eingangskorbMarkierung(name)).isEmpty();
    }

    @Dann("fehlt die Klassifikation «{word}» in der Liste")
    public void klassifikationFehlt(String code) {
        assertThat(antwort.statusCode()).as(antwort.asString()).isEqualTo(200);
        assertThat(antwort.jsonPath().<String>getList("code")).doesNotContain(code);
    }

    @Dann("steht im letzten Konfigurationseintrag von «{word}» die Änderung «{word}» von «{word}» auf «{word}»")
    public void konfigurationseintrag(String mandant, String pfad, String alt, String neu) throws SQLException {
        try (Connection verbindung = datenbank.get().getConnection();
             PreparedStatement abfrage = verbindung.prepareStatement("select details::text from "
                     + new MandantCode(mandant).schema() + ".zugriff_protokoll "
                     + "where aktion = 'KONFIGURATION_AENDERN' order by id desc limit 1")) {
            try (var ergebnis = abfrage.executeQuery()) {
                assertThat(ergebnis.next()).as("Konfigurationseintrag").isTrue();
                var aenderungen = io.restassured.path.json.JsonPath.from(ergebnis.getString(1))
                        .<Map<String, String>>getList("aenderungen");
                assertThat(aenderungen).contains(Map.of("pfad", pfad, "alt", alt, "neu", neu));
            }
        }
    }

    private List<String> eingangskorbMarkierung(String name) {
        assertThat(antwort.statusCode()).as(antwort.asString()).isEqualTo(200);
        List<String> markierung = antwort.jsonPath().getList("eintraege.findAll { it.id == '"
                + dokument(name).id() + "' }.klassifikationenUngueltig.flatten()");
        List<String> ids = antwort.jsonPath().getList("eintraege.id");
        assertThat(ids).as("Dokument im Eingangskorb").contains(dokument(name).id().toString());
        return markierung;
    }

    @Dann("bleibt die letzte Fassung des Dokuments «{word}» bei der Klassifikation «{word}» auf dem Wert «{word}»")
    public void klassifikationInLetzterFassung(String name, String code, String wert) throws SQLException {
        Dokument dokument = dokument(name);
        String schema = new MandantCode(dokument.mandant()).schema();
        try (Connection verbindung = datenbank.get().getConnection();
             PreparedStatement abfrage = verbindung.prepareStatement("select daten -> 'klassifikationen' -> ? "
                     + "->> 0 from " + schema + ".dokument_version where dokument_id = ? order by id desc limit 1")) {
            abfrage.setString(1, code);
            abfrage.setObject(2, dokument.id());
            try (var ergebnis = abfrage.executeQuery()) {
                assertThat(ergebnis.next()).isTrue();
                assertThat(ergebnis.getString(1)).isEqualTo(wert);
            }
        }
    }

    @Dann("hat das Dokument «{word}» weiterhin den freien Tag «{word}»")
    public void freierTagBleibt(String name, String tag) {
        assertThat(antwort.statusCode()).as(antwort.asString()).isEqualTo(200);
        assertThat(antwort.jsonPath().<String>getList("tags")).contains(tag);
    }

    @Dann("beim Artwechsel bleiben nur zugeordnete Werte erhalten und leere Klassifikationen werden vorbelegt")
    public void artwechselErgebnis() {
        assertThat(vorbelegung).containsExactlyInAnyOrderEntriesOf(Map.of(
                "fachgebiet", List.of("elektro"), "neu", List.of("standard")));
    }

    @After(order = 0)
    public void aufraeumen() throws SQLException {
        Map<String, List<UUID>> idsJeMandant = new LinkedHashMap<>();
        dokumente.values().forEach(dokument -> idsJeMandant.computeIfAbsent(dokument.mandant(), ignored ->
                new ArrayList<>()).add(dokument.id()));
        idsJeMandant.forEach((mandant, ids) -> {
            try {
                Testdokumente.loeschen(datenbank.get(), mandant, ids);
            } catch (SQLException e) {
                throw new IllegalStateException("Testdokumente konnten nicht entfernt werden", e);
            }
        });
        for (Zuordnung zuordnung : eigeneZuordnungen.reversed()) {
            Response loeschen = mitVersion("inhaber-" + zuordnung.mandant())
                    .delete("/api/v1/dokumentarten/" + zuordnung.art() + "/klassifikationen/"
                            + zuordnung.klassifikation());
            assertThat(loeschen.statusCode()).as(loeschen.asString()).isIn(204, 404);
        }
        for (Eigene eigene : eigeneKlassifikationen.reversed()) {
            Response loeschen = mitVersion("inhaber-" + eigene.mandant())
                    .delete("/api/v1/klassifikationen/" + eigene.code());
            assertThat(loeschen.statusCode()).as(loeschen.asString()).isIn(204, 404);
        }
        dokumente.clear();
        eigeneKlassifikationen.clear();
        eigeneZuordnungen.clear();
    }

    private io.restassured.specification.RequestSpecification mitVersion(String benutzer) {
        Response stand = gegeben(benutzer).get("/api/v1/dokumentarten");
        assertThat(stand.statusCode()).as(stand.asString()).isEqualTo(200);
        return gegeben(benutzer).header("If-Match", stand.getHeader("ETag"));
    }

    private static io.restassured.specification.RequestSpecification gegeben(String benutzer) {
        return given().auth().oauth2(Testanmeldung.token(benutzer));
    }

    private static Konfiguration.Art art(String code, List<ArtKlassifikation> klassifikationen) {
        return new Konfiguration.Art(code, code, null, List.of("frei"), List.of("titel"),
                Konfiguration.OHNE_ZUSATZ, "NORMAL", new Konfiguration.Aufbewahrung(null, null), false, true,
                false, List.of(), null, null, true, List.of(), null, 0, klassifikationen);
    }

    private Dokument dokument(String name) {
        Dokument dokument = dokumente.get(name);
        if (dokument == null) {
            throw new AssertionError("Dokument " + name + " fehlt");
        }
        return dokument;
    }

    private static String mandant(String benutzer) {
        return benutzer.substring(benutzer.indexOf('-') + 1);
    }
}
