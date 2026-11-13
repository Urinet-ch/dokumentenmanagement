package ch.urinet.archiv.protokoll;

import ch.urinet.archiv.dokument.Testdokumente;
import ch.urinet.archiv.gemeinsam.Testanmeldung;
import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.sicherheit.ProblemDetails;
import ch.urinet.archiv.sicherheit.domain.ApiToken;
import io.agroal.api.AgroalDataSource;
import io.cucumber.datatable.DataTable;
import io.cucumber.java.de.Angenommen;
import io.cucumber.java.de.Dann;
import io.cucumber.java.de.Wenn;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Schritte zu Req 2.19, 10.5 und 10.10 (Aufgabe 5.1): Einsicht ins Zugriffsprotokoll und Benutzerverzeichnis über
 * {@code /api/v1/protokoll}. Das Verzeichnis ist nur Einfügen; die Szenarien vergleichen darum nur Zeilen nach dem
 * gemerkten Stand. Frühere Namen und Rollen entstehen als synthetische Zeilen, die Testbenutzer im Realm bleiben gleich.
 */
public class ProtokollEinsichtSchritte {

    private static final String PFAD = "/api/v1/protokoll";

    @Inject
    Instance<AgroalDataSource> datenbank;

    private final Map<String, Long> stand = new HashMap<>();
    private Instant start;
    private Response antwort;

    @Angenommen("der Stand des Benutzerverzeichnisses von «{word}» ist gemerkt")
    public void merken(String mandant) throws SQLException {
        try (Connection verbindung = datenbank.get().getConnection(); Statement befehl = verbindung.createStatement();
             ResultSet zeile = befehl.executeQuery("select coalesce(max(id), 0), clock_timestamp() from "
                     + schema(mandant) + ".benutzer_verzeichnis")) {
            zeile.next();
            stand.put(mandant, zeile.getLong(1));
            start = zeile.getObject(2, OffsetDateTime.class).toInstant();
        }
    }

    @Angenommen("im Benutzerverzeichnis von «{word}» steht «{word}» zuletzt als «{}» mit Rolle «{word}»")
    public void frueher(String mandant, String benutzer, String name, String rolle) throws SQLException {
        try (Connection verbindung = datenbank.get().getConnection(); PreparedStatement einfuegen =
                verbindung.prepareStatement("insert into " + schema(mandant) + ".benutzer_verzeichnis (benutzer, name, "
                        + "rolle, gueltig_von, anlass) values (?, ?, ?, clock_timestamp(), 'AKTION') returning id")) {
            einfuegen.setString(1, Testanmeldung.sub(benutzer));
            einfuegen.setString(2, name);
            einfuegen.setString(3, rolle);
            try (ResultSet zeile = einfuegen.executeQuery()) {
                zeile.next();
                stand.put(mandant, zeile.getLong(1));
            }
        }
    }

    @Angenommen("im Zugriffsprotokoll von «{word}» steht danach ein Eintrag {word} von «{word}» als «{word}» zum "
            + "Probe-Dokument «{word}»")
    public void frueherEintrag(String mandant, String aktion, String benutzer, String rolle, String dokument)
            throws SQLException {
        try (Connection verbindung = datenbank.get().getConnection(); PreparedStatement einfuegen =
                verbindung.prepareStatement("insert into " + schema(mandant) + ".zugriff_protokoll (benutzer, rolle, "
                        + "aktion, dokument_id) values (?, ?, ?, ?)")) {
            einfuegen.setString(1, Testanmeldung.sub(benutzer));
            einfuegen.setString(2, rolle);
            einfuegen.setString(3, aktion);
            einfuegen.setObject(4, Testdokumente.NAMEN.get(dokument));
            einfuegen.executeUpdate();
        }
    }

    @Wenn("«{word}» das Zugriffsprotokoll abfragt")
    public void abfragen(String benutzer) {
        antwort = als(benutzer).when().get(PFAD);
    }

    @Wenn("«{word}» das Zugriffsprotokoll seit dem gemerkten Stand abfragt")
    public void seitStand(String benutzer) {
        antwort = als(benutzer).queryParam("von", start.toString()).when().get(PFAD);
    }

    @Wenn("«{word}» das Zugriffsprotokoll seit dem gemerkten Stand für «{word}» abfragt")
    public void seitStandFuer(String benutzer, String kennung) {
        antwort = als(benutzer).queryParam("von", start.toString()).queryParam("benutzer", Testanmeldung.sub(kennung))
                .when().get(PFAD);
    }

    @Wenn("«{word}» das Zugriffsprotokoll seit dem gemerkten Stand für «{word}» und das Probe-Dokument «{word}» "
            + "abfragt")
    public void seitStandFuerDokument(String benutzer, String kennung, String dokument) {
        antwort = als(benutzer).queryParam("von", start.toString()).queryParam("benutzer", Testanmeldung.sub(kennung))
                .queryParam("dokument", Testdokumente.NAMEN.get(dokument).toString()).when().get(PFAD);
    }

    @Wenn("«{word}» das Zugriffsprotokoll seit dem gemerkten Stand mit Aktion «{word}» abfragt")
    public void seitStandAktion(String benutzer, String aktion) {
        antwort = als(benutzer).queryParam("von", start.toString()).queryParam("aktion", aktion).when().get(PFAD);
    }

    @Wenn("«{word}» das Zugriffsprotokoll im Zeitraum vor dem gemerkten Stand abfragt")
    public void vorStand(String benutzer) {
        antwort = als(benutzer).queryParam("von", start.minusSeconds(3600).toString())
                .queryParam("bis", start.toString()).when().get(PFAD);
    }

    @Wenn("«{word}» das Zugriffsprotokoll mit dem Parameter «{word}» = «{}» abfragt")
    public void mitParameter(String benutzer, String parameter, String wert) {
        antwort = als(benutzer).queryParam(parameter, wert).when().get(PFAD);
    }

    @Wenn("«{word}» das Benutzerverzeichnis abfragt")
    public void verzeichnis(String benutzer) {
        antwort = als(benutzer).when().get(PFAD + "/benutzer");
    }

    @Wenn("«{word}» den Austritt von «{word}» erfasst")
    public void austritt(String benutzer, String person) {
        austrittKennung(benutzer, Testanmeldung.sub(person));
    }

    @Wenn("«{word}» den Austritt der Kennung «{}» erfasst")
    public void austrittKennung(String benutzer, String kennung) {
        antwort = als(benutzer).urlEncodingEnabled(false).when()
                .post(PFAD + "/benutzer/" + URLEncoder.encode(kennung, StandardCharsets.UTF_8).replace("+", "%20")
                        + "/austritt");
    }

    @Wenn("«{word}» den Austritt des Tokens «{}» im Mandanten «{word}» erfasst")
    public void austrittToken(String benutzer, String name, String mandant) throws SQLException {
        austrittKennung(benutzer, tokenKennung(mandant, "Probe " + name));
    }

    @Dann("antwortet das Archiv auf die Einsicht mit Status {int}")
    public void status(int status) {
        assertThat(antwort.statusCode()).as(antwort.asString()).isEqualTo(status);
    }

    @Dann("antwortet das Archiv auf die Einsicht mit Status {int} und Fehlercode «{word}»")
    public void fehlercode(int status, String fehlercode) {
        assertThat(ProblemDetails.pruefen(antwort, status)).isEqualTo(fehlercode);
    }

    /** Neueste zuerst; leere Zelle heisst {@code null}; Benutzer als Name des Testbenutzers. */
    @Dann("enthält die Einsicht genau diese Einträge:")
    public void eintraege(DataTable tabelle) {
        assertThat(antwort.statusCode()).as(antwort.asString()).isEqualTo(200);
        List<Map<String, Object>> eintraege = antwort.jsonPath().getList("eintraege");
        List<Map<String, String>> erwartet = tabelle.asMaps();
        assertThat(antwort.jsonPath().getLong("gesamt")).isEqualTo(erwartet.size());
        assertThat(eintraege).hasSize(erwartet.size());
        for (int i = 0; i < erwartet.size(); i++) {
            Map<String, String> soll = erwartet.get(i);
            Map<String, Object> ist = eintraege.get(i);
            assertThat(ist.get("benutzer")).as("benutzer %d", i).isEqualTo(Testanmeldung.sub(soll.get("benutzer")));
            assertThat(ist.get("name")).as("name %d", i).isEqualTo(soll.get("name"));
            assertThat(ist.get("rolle")).as("rolle %d", i).isEqualTo(soll.get("rolle"));
            assertThat(ist.get("aktion")).as("aktion %d", i).isEqualTo(soll.get("aktion"));
            assertThat(ist.get("dokumentId")).as("dokument %d", i).isEqualTo(soll.get("dokument") == null ? null
                    : Testdokumente.NAMEN.get(soll.get("dokument")).toString());
            assertThat(ist.get("ip")).as("ip %d", i).isEqualTo(soll.get("ip"));
        }
    }

    @Dann("enthält die Einsicht keinen Eintrag ab dem gemerkten Stand")
    public void keineNeuen() {
        assertThat(antwort.statusCode()).as(antwort.asString()).isEqualTo(200);
        List<String> zeiten = antwort.jsonPath().getList("eintraege.zeit");
        assertThat(zeiten).allSatisfy(zeit -> assertThat(Instant.parse(zeit)).isBefore(start));
    }

    /**
     * Zeilen nach dem gemerkten Stand, in der Reihenfolge des Eintrags. Spalte {@code benutzer}: Testbenutzer oder
     * {@code Token <Name>}; {@code beendet}: ja/nein; {@code erfasst_von}: Testbenutzer oder leer.
     */
    @Dann("hat das Benutzerverzeichnis von «{word}» genau diese neuen Zeilen:")
    public void neueZeilen(String mandant, DataTable tabelle) throws SQLException {
        List<Map<String, String>> ist = zeilenNach(mandant);
        List<Map<String, String>> erwartet = tabelle.asMaps();
        assertThat(ist).hasSize(erwartet.size());
        for (int i = 0; i < erwartet.size(); i++) {
            Map<String, String> soll = erwartet.get(i);
            Map<String, String> zeile = ist.get(i);
            assertThat(zeile.get("benutzer")).as("benutzer %d", i).isEqualTo(kennung(mandant, soll.get("benutzer")));
            assertThat(zeile.get("name")).as("name %d", i).isEqualTo(soll.get("name"));
            assertThat(zeile.get("rolle")).as("rolle %d", i).isEqualTo(soll.get("rolle"));
            assertThat(zeile.get("anlass")).as("anlass %d", i).isEqualTo(soll.get("anlass"));
            assertThat(zeile.get("beendet")).as("beendet %d", i).isEqualTo(soll.get("beendet"));
            assertThat(zeile.get("erfasst_von")).as("erfasst_von %d", i).isEqualTo(
                    soll.get("erfasst_von") == null ? null : Testanmeldung.sub(soll.get("erfasst_von")));
            assertThat(zeile.get("gueltig")).as("gueltig_bis >= gueltig_von %d", i).isEqualTo("ja");
        }
    }

    @Dann("hat das Benutzerverzeichnis von «{word}» keine neue Zeile")
    public void keineNeueZeile(String mandant) throws SQLException {
        assertThat(zeilenNach(mandant)).isEmpty();
    }

    @Dann("beginnt die Austritt-Zeile von «{word}» in «{word}» mit der Zeile davor")
    public void austrittBeginn(String person, String mandant) throws SQLException {
        try (Connection verbindung = datenbank.get().getConnection(); PreparedStatement abfrage =
                verbindung.prepareStatement("select gueltig_von from " + schema(mandant) + ".benutzer_verzeichnis "
                        + "where benutzer = ? order by id desc limit 2")) {
            abfrage.setString(1, Testanmeldung.sub(person));
            try (ResultSet zeile = abfrage.executeQuery()) {
                zeile.next();
                OffsetDateTime austritt = zeile.getObject(1, OffsetDateTime.class);
                zeile.next();
                assertThat(austritt.toInstant()).isEqualTo(zeile.getObject(1, OffsetDateTime.class).toInstant());
            }
        }
    }

    @Dann("liefert das Benutzerverzeichnis für «{word}» als letzte Zeile «{}» mit Rolle «{word}» und Anlass «{word}»")
    public void letzteZeile(String person, String name, String rolle, String anlass) {
        assertThat(antwort.statusCode()).as(antwort.asString()).isEqualTo(200);
        String sub = Testanmeldung.sub(person);
        List<Map<String, Object>> zeilen = antwort.jsonPath().getList("");
        List<Map<String, Object>> eigene = zeilen.stream().filter(z -> sub.equals(z.get("benutzer"))).toList();
        assertThat(eigene).isNotEmpty();
        Map<String, Object> letzte = eigene.getLast();
        assertThat(letzte).containsEntry("name", name).containsEntry("rolle", rolle).containsEntry("anlass", anlass);
        assertThat(letzte.get("gueltigVon")).isNotNull();
    }

    @Dann("scheitert im Schema von «{word}» jeder Versuch, das Benutzerverzeichnis zu ändern, zu löschen oder zu leeren")
    public void nurEinfuegen(String mandant) throws SQLException {
        String tabelle = schema(mandant) + ".benutzer_verzeichnis";
        for (String sql : List.of("update " + tabelle + " set name = 'geaendert' where id > " + stand.get(mandant),
                "update " + tabelle + " set gueltig_bis = clock_timestamp() where id > " + stand.get(mandant),
                "delete from " + tabelle + " where id > " + stand.get(mandant),
                "truncate " + tabelle)) {
            try (Connection verbindung = datenbank.get().getConnection(); Statement befehl = verbindung.createStatement()) {
                assertThatThrownBy(() -> befehl.execute(sql)).as(sql).isInstanceOf(SQLException.class)
                        .hasMessageContaining("nur Einfügen erlaubt");
            }
        }
    }

    private List<Map<String, String>> zeilenNach(String mandant) throws SQLException {
        List<Map<String, String>> zeilen = new ArrayList<>();
        try (Connection verbindung = datenbank.get().getConnection(); PreparedStatement abfrage =
                verbindung.prepareStatement("select benutzer, name, rolle, anlass, "
                        + "case when gueltig_bis is null then 'nein' else 'ja' end, erfasst_von, "
                        + "case when gueltig_bis is null or gueltig_bis >= gueltig_von then 'ja' else 'nein' end "
                        + "from " + schema(mandant) + ".benutzer_verzeichnis where id > ? order by id")) {
            abfrage.setLong(1, Objects.requireNonNull(stand.get(mandant), "Stand nicht gemerkt"));
            try (ResultSet zeile = abfrage.executeQuery()) {
                while (zeile.next()) {
                    Map<String, String> eintrag = new HashMap<>();
                    eintrag.put("benutzer", zeile.getString(1));
                    eintrag.put("name", zeile.getString(2));
                    eintrag.put("rolle", zeile.getString(3));
                    eintrag.put("anlass", zeile.getString(4));
                    eintrag.put("beendet", zeile.getString(5));
                    eintrag.put("erfasst_von", zeile.getString(6));
                    eintrag.put("gueltig", zeile.getString(7));
                    zeilen.add(eintrag);
                }
            }
        }
        return zeilen;
    }

    private String kennung(String mandant, String zelle) throws SQLException {
        return zelle.startsWith("Token ") ? tokenKennung(mandant, "Probe " + zelle.substring("Token ".length()))
                : Testanmeldung.sub(zelle);
    }

    /** Kennung {@code token:<id>} des Tokens mit diesem Namen; die Token-Schritte legen «Probe <Name>» an. */
    private String tokenKennung(String mandant, String name) throws SQLException {
        try (Connection verbindung = datenbank.get().getConnection(); PreparedStatement abfrage =
                verbindung.prepareStatement("select id from public.api_client where mandant = ? and name = ?")) {
            abfrage.setString(1, mandant);
            abfrage.setString(2, name);
            try (ResultSet zeile = abfrage.executeQuery()) {
                assertThat(zeile.next()).as("Token %s", name).isTrue();
                return ApiToken.principal(zeile.getString(1));
            }
        }
    }

    private static RequestSpecification als(String benutzer) {
        return given().auth().oauth2(Testanmeldung.token(benutzer));
    }

    private static String schema(String mandant) {
        return new MandantCode(mandant).schema();
    }
}
