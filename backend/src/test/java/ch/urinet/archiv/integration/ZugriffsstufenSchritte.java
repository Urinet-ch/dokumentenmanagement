package ch.urinet.archiv.integration;

import ch.urinet.archiv.dokument.Testdokumente;
import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.sicherheit.ProblemDetails;
import ch.urinet.archiv.sicherheit.application.WebseitenSchluesselVerwaltung;
import ch.urinet.archiv.sicherheit.domain.ApiToken;
import io.agroal.api.AgroalDataSource;
import io.cucumber.datatable.DataTable;
import io.cucumber.java.After;
import io.cucumber.java.de.Angenommen;
import io.cucumber.java.de.Dann;
import io.cucumber.java.de.Wenn;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Schritte zu den Zugriffsstufen der Webseiten-Schnittstelle (Spec 03, Aufgabe 11.1) gegen die Probe
 * {@code /public/v1/probe/stufe} und {@code /public/v1/probe/dokumente}. Schlüssel und Testdokumente (Titel
 * {@code stufentest-<name>}, nur synthetische Daten) entstehen zur Laufzeit und werden danach gelöscht.
 */
public class ZugriffsstufenSchritte {

    private static final String PROBE = "/public/v1/probe";

    @Inject
    WebseitenSchluesselVerwaltung verwaltung;

    @Inject
    Instance<AgroalDataSource> datenbank;

    private final Map<String, String> schluessel = new HashMap<>();
    private final List<String> tokenIds = new ArrayList<>();
    private final Map<String, Dokument> dokumente = new LinkedHashMap<>();
    private Response antwort;
    private Response vergleich;

    private record Dokument(String mandant, UUID id) {
    }

    @After
    public void aufraeumen() throws SQLException {
        for (String id : tokenIds) {
            try (Connection verbindung = datenbank.get().getConnection();
                 PreparedStatement befehl = verbindung.prepareStatement("delete from public.api_client where id = ?")) {
                befehl.setString(1, id);
                befehl.executeUpdate();
            }
        }
        Map<String, List<UUID>> jeMandant = new HashMap<>();
        dokumente.values().forEach(d -> jeMandant.computeIfAbsent(d.mandant(), m -> new ArrayList<>()).add(d.id()));
        for (Map.Entry<String, List<UUID>> e : jeMandant.entrySet()) {
            Testdokumente.loeschen(datenbank.get(), e.getKey(), e.getValue());
        }
        dokumente.clear();
        tokenIds.clear();
        schluessel.clear();
    }

    @Angenommen("der Lese-Schlüssel «{word}» der Webseite «{word}» im Mandanten «{word}» hat die höchste Stufe «{word}»")
    public void leseSchluessel(String name, String webseite, String mandant, String stufe) {
        String text = verwaltung.erstellen(mandant, webseite, "lesen", stufe, false).token().text();
        tokenIds.add(ApiToken.lesen(text).orElseThrow().id());
        schluessel.put(name, text);
    }

    /** Spalten: name, status, vertraulichkeit, zugriff, veroeffentlicht; «-» lässt das Feld weg. */
    @Angenommen("im Mandanten «{word}» liegen diese Stufentest-Dokumente:")
    public void dokumenteAnlegen(String mandant, DataTable tabelle) throws SQLException {
        for (Map<String, String> zeile : tabelle.asMaps()) {
            UUID id = Testdokumente.anlegen(datenbank.get(), new Testdokumente.Angaben(mandant, null,
                    zeile.get("vertraulichkeit"), true, "probe", null, zeile.get("status")));
            dokumente.put(zeile.get("name"), new Dokument(mandant, id));
            List<String> felder = new ArrayList<>();
            if (!"-".equals(zeile.get("zugriff"))) {
                felder.add("\"zugriff\": \"" + zeile.get("zugriff") + "\"");
            }
            String veroeffentlicht = zeile.get("veroeffentlicht");
            if (!"-".equals(veroeffentlicht)) {
                // «"true"» (mit Anführungszeichen) steht für den JSON-String, «true» für den Wahrheitswert
                felder.add("\"veroeffentlicht\": " + veroeffentlicht);
            }
            try (Connection verbindung = datenbank.get().getConnection();
                 PreparedStatement befehl = verbindung.prepareStatement("update " + new MandantCode(mandant).schema()
                         + ".dokument set titel = ?, zusatz = ?::jsonb where id = ?")) {
                befehl.setString(1, "stufentest-" + zeile.get("name"));
                befehl.setString(2, "{" + String.join(", ", felder) + "}");
                befehl.setObject(3, id);
                befehl.executeUpdate();
            }
        }
    }

    @Wenn("der Schlüssel «{word}» «{}» ohne Stufen-Header aufruft")
    public void ohneHeader(String name, String pfad) {
        antwort = anfrage(name).when().get(pfad);
    }

    @Wenn("der Schlüssel «{word}» «{}» mit dem Header «{}» und dem Wert «{}» aufruft")
    public void mitHeader(String name, String pfad, String header, String wert) {
        antwort = anfrage(name).header(header, wert).when().get(pfad);
    }

    @Wenn("der Schlüssel «{word}» «{}» mit zwei Headern «{}» und den Werten «{}» und «{}» aufruft")
    public void mitZweiHeadern(String name, String pfad, String header, String eins, String zwei) {
        antwort = anfrage(name).header(header, eins).header(header, zwei).when().get(pfad);
    }

    @Wenn("der Schlüssel «{word}» das Dokument «{word}» mit der Stufe «{word}» abruft")
    public void dokumentAbrufen(String name, String dokument, String stufe) {
        antwort = anfrage(name).header("X-Archiv-Zugriff", stufe).when()
                .get(PROBE + "/dokumente/" + dokumente.get(dokument).id());
    }

    @Wenn("der Schlüssel «{word}» ein unbekanntes Dokument mit der Stufe «{word}» abruft")
    public void unbekanntesDokument(String name, String stufe) {
        vergleich = anfrage(name).header("X-Archiv-Zugriff", stufe).when()
                .get(PROBE + "/dokumente/" + UUID.randomUUID());
    }

    @Wenn("der Schlüssel «{word}» «{}» mit dem Herkunftskopf «{}» und der Methode {word} aufruft")
    public void mitHerkunft(String name, String pfad, String herkunft, String methode) {
        antwort = anfrage(name).header("Origin", herkunft)
                .header("Access-Control-Request-Method", "GET").when().request(methode, pfad);
    }

    @Dann("liefert das Archiv Status {int}")
    public void status(int status) {
        assertThat(antwort.statusCode()).as(antwort.asString()).isEqualTo(status);
    }

    @Dann("liefert das Archiv Status {int} und Fehlercode «{word}»")
    public void statusUndCode(int status, String code) {
        assertThat(ProblemDetails.pruefen(antwort, status)).isEqualTo(code);
    }

    @Dann("steht in der Antwort weder «{}» noch «{}»")
    public void nennt(String eins, String zwei) {
        assertThat(antwort.asString()).doesNotContain(eins).doesNotContain(zwei);
    }

    @Dann("nennt die Probe die gemeldete Stufe «{word}», die wirksame Stufe «{word}» und die Stufen «{}»")
    public void stufe(String gemeldet, String wirksam, String stufen) {
        assertThat(antwort.statusCode()).as(antwort.asString()).isEqualTo(200);
        assertThat(antwort.<String>path("gemeldet")).isEqualTo(gemeldet);
        assertThat(antwort.<String>path("wirksam")).isEqualTo(wirksam);
        assertThat(antwort.<List<String>>path("stufen")).containsExactlyElementsOf(List.of(stufen.split(",")));
    }

    @Dann("nennt die Probe den Client-Hash «{word}»")
    public void clientHash(String hash) {
        assertThat(antwort.statusCode()).as(antwort.asString()).isEqualTo(200);
        assertThat(antwort.<String>path("clientHash")).isEqualTo(hash);
    }

    @Dann("liefert der feste Filter genau die Dokumente «{}»")
    public void genau(String namen) {
        assertThat(antwort.statusCode()).as(antwort.asString()).isEqualTo(200);
        List<String> erwartet = namen.isEmpty() ? List.of()
                : java.util.Arrays.stream(namen.split(",")).map(n -> "stufentest-" + n).toList();
        assertThat(antwort.<List<String>>path("$")).containsExactlyElementsOf(erwartet);
    }

    @Dann("ist die Antwort für das Dokument gleich der für ein unbekanntes Dokument")
    public void gleichWieUnbekannt() {
        assertThat(ProblemDetails.pruefen(antwort, 404)).isEqualTo("NICHT_GEFUNDEN");
        assertThat(ProblemDetails.pruefen(vergleich, 404)).isEqualTo("NICHT_GEFUNDEN");
        assertThat(antwort.asString().replaceAll("\"instance\":\"[^\"]*\"", "").replaceAll("\"[a-zA-Z]*[Ii]d\":\"[^\"]*\"", ""))
                .isEqualTo(vergleich.asString().replaceAll("\"instance\":\"[^\"]*\"", "")
                        .replaceAll("\"[a-zA-Z]*[Ii]d\":\"[^\"]*\"", ""));
    }

    @Dann("enthält die Antwort das Dokument «{word}» mit dem Titel «{}»")
    public void dokumentTitel(String name, String titel) {
        assertThat(antwort.statusCode()).as(antwort.asString()).isEqualTo(200);
        assertThat(antwort.<String>path("titel")).isEqualTo(titel);
    }

    @Dann("trägt die Antwort keinen Header «{}»")
    public void keinHeader(String header) {
        assertThat(antwort.headers().hasHeaderWithName(header)).as(antwort.headers().toString()).isFalse();
    }

    private RequestSpecification anfrage(String name) {
        return given().header("Authorization", "Bearer " + schluessel.get(name)).urlEncodingEnabled(false);
    }
}
