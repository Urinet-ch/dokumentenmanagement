package ch.urinet.archiv.protokoll;

import ch.urinet.archiv.dokument.Testdokumente;
import ch.urinet.archiv.gemeinsam.Testanmeldung;
import ch.urinet.archiv.konfiguration.application.KonfigurationAenderungsProtokoll;
import ch.urinet.archiv.konfiguration.domain.Konfiguration;
import ch.urinet.archiv.konfiguration.domain.KonfigurationQuelle;
import ch.urinet.archiv.mandant.application.MandantKontext;
import ch.urinet.archiv.mandant.domain.MandantCode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agroal.api.AgroalDataSource;
import io.cucumber.datatable.DataTable;
import io.cucumber.java.After;
import io.cucumber.java.de.Angenommen;
import io.cucumber.java.de.Dann;
import io.cucumber.java.de.Wenn;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Schritte zu Req 10.2, 10.3, 10.7 und 10.8 (Grundlage aus Aufgabe 1.6) über die Probe-Endpunkte unter src/test, dazu
 * die Regeländerung einer Dokumentart (Req 9.10).
 */
public class ZugriffProtokollSchritte {

    private static final String FEHLER_TRIGGER = "probe_protokoll_fehler";
    private static final String KENNUNGEN = "einstellungen.kennungen.";
    private static final String UID_PROBE = "CHE-000.000.077";
    private static final String IBAN_PROBE = "CH0000000000000000077";

    @Inject
    Instance<AgroalDataSource> datenbank;

    @Inject
    KonfigurationAenderungsProtokoll konfigurationsProtokoll;

    @Inject
    MandantKontext kontext;

    private final Map<String, Long> stand = new HashMap<>();
    private final List<String> fehlerSchemas = new ArrayList<>();
    private final List<String[]> angelegteArten = new ArrayList<>();
    private final Map<String, String> kennungenVorher = new HashMap<>();

    @Angenommen("der Stand des Zugriffsprotokolls von «{word}» ist gemerkt")
    public void merken(String mandant) throws SQLException {
        try (Connection verbindung = datenbank.get().getConnection(); Statement befehl = verbindung.createStatement();
             ResultSet zeile = befehl.executeQuery("select coalesce(max(id), 0) from " + schema(mandant)
                     + ".zugriff_protokoll")) {
            zeile.next();
            stand.put(mandant, zeile.getLong(1));
        }
    }

    @Dann("das Zugriffsprotokoll von «{word}» hat genau einen neuen Eintrag:")
    public void einNeuerEintrag(String mandant, DataTable tabelle) throws Exception {
        Map<String, String> erwartet = tabelle.asMaps().getFirst();
        List<Map<String, String>> neu = eintraegeFuerAktion(mandant, erwartet.get("aktion"));
        assertThat(neu).hasSize(1);
        Map<String, String> eintrag = neu.getFirst();
        assertThat(eintrag.get("benutzer")).isEqualTo(erwartet.get("benutzer") == null ? null
                : Testanmeldung.sub(erwartet.get("benutzer")));
        assertThat(eintrag.get("rolle")).isEqualTo(erwartet.get("rolle"));
        assertThat(eintrag.get("aktion")).isEqualTo(erwartet.get("aktion"));
        assertThat(eintrag.get("dokument")).isEqualTo(erwartet.get("dokument") == null ? null
                : Testdokumente.NAMEN.get(erwartet.get("dokument")).toString());
        assertThat(eintrag.get("ip")).isEqualTo(erwartet.get("ip"));
        assertThat(eintrag.get("grund")).isEqualTo(erwartet.get("grund"));
        assertThat(eintrag.get("fehlercode")).isEqualTo(erwartet.get("fehlercode"));
        if (eintrag.get("details_schluessel") != null) {
            assertThat(eintrag.get("details_schluessel").split(","))
                    .as("nur grund, sha256, fehlercode und bei REGEL_AENDERN dokumentart, alt und neu")
                    .isSubsetOf("grund", "sha256", "fehlercode", "dokumentart", "alt", "neu");
        }
    }

    /** Nur Werte der Regel, keine Werte eines Dokuments (Req 9.10, 10.2); leere Zelle heisst {@code null}. */
    @Dann("der neue Eintrag im Zugriffsprotokoll von «{word}» hält für die Dokumentart «{word}» diese Regeln fest:")
    public void regelFestgehalten(String mandant, String code, DataTable tabelle) throws Exception {
        List<Map<String, String>> neu = eintraegeFuerAktion(mandant, "REGEL_AENDERN");
        assertThat(neu).hasSize(1);
        JsonNode details = new ObjectMapper().readTree(neu.getFirst().get("details"));
        assertThat(feldnamen(details)).containsExactlyInAnyOrder("dokumentart", "alt", "neu");
        assertThat(details.path("dokumentart").asText()).isEqualTo(code);
        for (Map<String, String> zeile : tabelle.asMaps()) {
            JsonNode regel = details.path(zeile.get("regel"));
            assertThat(feldnamen(regel)).as(zeile.get("regel"))
                    .containsExactlyInAnyOrder("aufbewahrungJahre", "aufbewahrungAb", "gesetzlichePflicht");
            for (String feld : List.of("aufbewahrungJahre", "aufbewahrungAb", "gesetzlichePflicht")) {
                JsonNode wert = regel.get(feld);
                assertThat(wert.isNull() ? null : wert.asText()).as(zeile.get("regel") + "." + feld)
                        .isEqualTo(zeile.get(feld));
            }
        }
    }

    @Dann("das Zugriffsprotokoll von «{word}» hat genau einen neuen Konfigurationseintrag mit den geänderten Elementen")
    public void konfigurationMitAenderungen(String mandant) throws Exception {
        List<Map<String, String>> neu = eintraegeFuerAktion(mandant, "KONFIGURATION_AENDERN");
        assertThat(neu).hasSize(1);
        Map<String, String> eintrag = neu.getFirst();
        assertThat(eintrag.get("benutzer")).isEqualTo(Testanmeldung.sub("inhaber-urinet"));
        assertThat(eintrag.get("rolle")).isEqualTo("INHABER");
        assertThat(eintrag.get("grund")).isEqualTo("Aufbewahrungsregel anpassen");
        JsonNode details = new ObjectMapper().readTree(eintrag.get("details"));
        assertThat(feldnamen(details)).containsExactlyInAnyOrder("grund", "version", "quelle", "aenderungen");
        assertThat(details.path("grund").asText()).isEqualTo("Aufbewahrungsregel anpassen");
        assertThat(details.path("version").asInt()).isPositive();
        assertThat(details.path("quelle").asText()).isEqualTo("APP");
        JsonNode jahre = null;
        for (JsonNode aenderung : details.path("aenderungen")) {
            if (aenderung.path("pfad").asText().startsWith(KENNUNGEN)) {
                // Kennungen eines früheren Szenarios (Req 1.3): nur Pfad und «geändert»
                assertThat(feldnamen(aenderung)).containsExactlyInAnyOrder("pfad", "aenderung");
                continue;
            }
            assertThat(feldnamen(aenderung)).containsExactlyInAnyOrder("pfad", "alt", "neu");
            if ("arten.PROBE_DA_REGEL.aufbewahrung.jahre".equals(aenderung.path("pfad").asText())) {
                jahre = aenderung;
            }
        }
        assertThat(jahre).as("geänderte Aufbewahrungsjahre im Protokoll").isNotNull();
        assertThat(jahre.path("alt").asText()).isEqualTo("10");
        assertThat(jahre.path("neu").asText()).isEqualTo("6");
    }

    @Wenn("eine synthetische UID- und IBAN-Änderung für «{word}» protokolliert wird")
    public void kennungenProtokollieren(String mandant) {
        CompletableFuture.runAsync(() -> kontext.mit(new MandantCode(mandant),
                () -> QuarkusTransaction.requiringNew().run(() -> konfigurationsProtokoll.schreiben(
                        7, KonfigurationQuelle.APP, "test:kennungen", "Synthetische Kennungsprobe", List.of(
                                new Konfiguration.Aenderung("einstellungen.kennungen.uid",
                                        "CHE-000.000.001", "CHE-000.000.002"),
                                new Konfiguration.Aenderung("einstellungen.kennungen.ibans",
                                        "[CH0000000000000000000]", "[CH0000000000000000001]"),
                                new Konfiguration.Aenderung("einstellungen.geschaeftsjahrEnde",
                                        "12-31", "06-30")))))).join();
    }

    @Dann("enthält der neue Konfigurationseintrag von «{word}» nur Kennungsfeldnamen und «geändert»")
    public void kennungenVerdeckt(String mandant) throws Exception {
        var eintraege = eintraegeFuerAktion(mandant, "KONFIGURATION_AENDERN");
        assertThat(eintraege).hasSize(1);
        String text = eintraege.getFirst().get("details");
        assertThat(text).doesNotContain("CHE-000.000.001", "CHE-000.000.002",
                "CH0000000000000000000", "CH0000000000000000001");
        JsonNode aenderungen = new ObjectMapper().readTree(text).path("aenderungen");
        assertThat(aenderungen.size()).isEqualTo(3);
        for (int i = 0; i < 2; i++) {
            JsonNode kennung = aenderungen.get(i);
            assertThat(feldnamen(kennung)).containsExactlyInAnyOrder("pfad", "aenderung");
            assertThat(kennung.path("pfad").asText())
                    .isEqualTo("einstellungen.kennungen." + (i == 0 ? "uid" : "ibans"));
            assertThat(kennung.path("aenderung").asText()).isEqualTo("geändert");
        }
        JsonNode anderesFeld = aenderungen.get(2);
        assertThat(feldnamen(anderesFeld)).containsExactlyInAnyOrder("pfad", "alt", "neu");
        assertThat(anderesFeld.path("pfad").asText()).isEqualTo("einstellungen.geschaeftsjahrEnde");
        assertThat(anderesFeld.path("alt").asText()).isEqualTo("12-31");
        assertThat(anderesFeld.path("neu").asText()).isEqualTo("06-30");
    }

    /**
     * Sichert erst die aktuelle Konfigurationsversion (GET mit ETag), dann setzt {@code public.mandant.kennungen}
     * synthetisch: Die nächste Änderung über die Verwaltung schreibt den Unterschied der Kennungen ins Protokoll.
     */
    @Angenommen("der Mandant «{word}» hat synthetische Kennungen, die seit der letzten Konfigurationsversion geändert sind")
    public void kennungenSynthetisch(String mandant) throws SQLException {
        given().auth().oauth2(Testanmeldung.token("inhaber-" + mandant)).when().get("/api/v1/dokumentarten")
                .then().statusCode(200);
        try (Connection verbindung = datenbank.get().getConnection(); PreparedStatement lesen = verbindung
                .prepareStatement("select kennungen::text from public.mandant where code = ?")) {
            lesen.setString(1, mandant);
            try (ResultSet zeile = lesen.executeQuery()) {
                assertThat(zeile.next()).isTrue();
                kennungenVorher.putIfAbsent(mandant, zeile.getString(1));
            }
        }
        kennungenSetzen(mandant, "{\"uid\":\"" + UID_PROBE + "\",\"ibans\":[\"" + IBAN_PROBE + "\"]}");
    }

    @Wenn("die Kennungen von «{word}» wieder wie vorher sind")
    public void kennungenZuruecksetzen(String mandant) throws SQLException {
        kennungenSetzen(mandant, kennungenVorher.remove(mandant));
    }

    @Dann("der neue Konfigurationseintrag von «{word}» nennt die Kennungen nur mit Feldnamen und «geändert»")
    public void kennungenNurMitFeldnamen(String mandant) throws Exception {
        var eintraege = eintraegeFuerAktion(mandant, "KONFIGURATION_AENDERN");
        assertThat(eintraege).hasSize(1);
        String text = eintraege.getFirst().get("details");
        assertThat(text).doesNotContain("000.000.077", "0000000000000000077");
        List<String> kennungen = new ArrayList<>();
        boolean art = false;
        for (JsonNode aenderung : new ObjectMapper().readTree(text).path("aenderungen")) {
            String pfad = aenderung.path("pfad").asText();
            if (pfad.startsWith(KENNUNGEN)) {
                kennungen.add(pfad);
                assertThat(feldnamen(aenderung)).as(pfad).containsExactlyInAnyOrder("pfad", "aenderung");
                assertThat(aenderung.path("aenderung").asText()).isEqualTo("geändert");
            } else {
                art |= pfad.startsWith("arten.PROBE_DA_KENNUNG");
                assertThat(feldnamen(aenderung)).as(pfad).containsExactlyInAnyOrder("pfad", "alt", "neu");
            }
        }
        assertThat(kennungen).containsExactlyInAnyOrder(KENNUNGEN + "uid", KENNUNGEN + "ibans");
        assertThat(art).as("Änderung der Art mit alt und neu").isTrue();
    }

    private void kennungenSetzen(String mandant, String json) throws SQLException {
        try (Connection verbindung = datenbank.get().getConnection(); PreparedStatement setzen = verbindung
                .prepareStatement("update public.mandant set kennungen = cast(? as jsonb) where code = ?")) {
            setzen.setString(1, json);
            setzen.setString(2, mandant);
            assertThat(setzen.executeUpdate()).isEqualTo(1);
        }
    }

    private static List<String> feldnamen(JsonNode knoten) {
        List<String> namen = new ArrayList<>();
        knoten.fieldNames().forEachRemaining(namen::add);
        return namen;
    }

    @Dann("das Zugriffsprotokoll von «{word}» hat keinen neuen Eintrag")
    public void keinNeuerEintrag(String mandant) throws SQLException {
        assertThat(neueEintraege(mandant)).isEmpty();
    }

    @Dann("das Zugriffsprotokoll von «{word}» hat keinen neuen Eintrag für die Aktion «{word}»")
    public void keineNeueAktion(String mandant, String aktion) throws SQLException {
        assertThat(eintraegeFuerAktion(mandant, aktion)).isEmpty();
    }

    @Dann("scheitert im Schema von «{word}» jeder Versuch, das Zugriffsprotokoll zu ändern, zu löschen oder zu leeren")
    public void nurEinfuegen(String mandant) throws SQLException {
        String tabelle = schema(mandant) + ".zugriff_protokoll";
        for (String sql : List.of("update " + tabelle + " set benutzer = 'geaendert' where id > " + stand.get(mandant),
                "delete from " + tabelle + " where id > " + stand.get(mandant),
                "truncate " + tabelle)) {
            try (Connection verbindung = datenbank.get().getConnection(); Statement befehl = verbindung.createStatement()) {
                assertThatThrownBy(() -> befehl.execute(sql)).as(sql).isInstanceOf(SQLException.class)
                        .hasMessageContaining("nur Einfügen erlaubt");
            }
        }
    }

    @Angenommen("im Schema von «{word}» scheitert jeder neue Eintrag ins Zugriffsprotokoll beim Festschreiben")
    public void protokollScheitert(String mandant) throws SQLException {
        String schema = schema(mandant);
        fehlerSchemas.add(schema);
        try (Connection verbindung = datenbank.get().getConnection(); Statement befehl = verbindung.createStatement()) {
            befehl.execute("create or replace function " + schema + "." + FEHLER_TRIGGER + "() returns trigger "
                    + "language plpgsql as $$ begin raise exception 'Protokoll-Probe: Festschreiben scheitert'; end $$");
            befehl.execute("create constraint trigger " + FEHLER_TRIGGER + " after insert on " + schema
                    + ".zugriff_protokoll deferrable initially deferred for each row execute function " + schema + "."
                    + FEHLER_TRIGGER + "()");
        }
    }

    @Dann("im Schema von «{word}» gibt es die Dokumentart «{word}»")
    public void artVorhanden(String mandant, String code) throws SQLException {
        angelegteArten.add(new String[]{schema(mandant), code});
        assertThat(artGibtEs(mandant, code)).isTrue();
    }

    @Dann("im Schema von «{word}» gibt es die Dokumentart «{word}» nicht")
    public void artFehlt(String mandant, String code) throws SQLException {
        angelegteArten.add(new String[]{schema(mandant), code});
        assertThat(artGibtEs(mandant, code)).isFalse();
    }

    @After
    public void aufraeumen() throws SQLException {
        for (String mandant : List.copyOf(kennungenVorher.keySet())) {
            kennungenZuruecksetzen(mandant);
        }
        try (Connection verbindung = datenbank.get().getConnection(); Statement befehl = verbindung.createStatement()) {
            for (String schema : fehlerSchemas) {
                befehl.execute("drop trigger if exists " + FEHLER_TRIGGER + " on " + schema + ".zugriff_protokoll");
                befehl.execute("drop function if exists " + schema + "." + FEHLER_TRIGGER + "()");
            }
            for (String[] art : angelegteArten) {
                try (PreparedStatement loeschen = verbindung.prepareStatement(
                        "delete from " + art[0] + ".dokumentart where code = ?")) {
                    loeschen.setString(1, art[1]);
                    loeschen.executeUpdate();
                }
            }
        }
        fehlerSchemas.clear();
        angelegteArten.clear();
    }

    private boolean artGibtEs(String mandant, String code) throws SQLException {
        try (Connection verbindung = datenbank.get().getConnection(); PreparedStatement abfrage =
                verbindung.prepareStatement("select 1 from " + schema(mandant) + ".dokumentart where code = ?")) {
            abfrage.setString(1, code);
            try (ResultSet zeile = abfrage.executeQuery()) {
                return zeile.next();
            }
        }
    }

    private List<Map<String, String>> neueEintraege(String mandant) throws SQLException {
        List<Map<String, String>> eintraege = new ArrayList<>();
        try (Connection verbindung = datenbank.get().getConnection(); PreparedStatement abfrage = verbindung.prepareStatement(
                "select benutzer, rolle, aktion, dokument_id::text, host(ip), details->>'grund', details->>'fehlercode', "
                        + "(select string_agg(k, ',' order by k) from jsonb_object_keys(details) k), details::text "
                        + "from " + schema(mandant) + ".zugriff_protokoll where id > ? order by id")) {
            abfrage.setLong(1, stand.get(mandant));
            try (ResultSet zeile = abfrage.executeQuery()) {
                while (zeile.next()) {
                    Map<String, String> eintrag = new HashMap<>();
                    eintrag.put("benutzer", zeile.getString(1));
                    eintrag.put("rolle", zeile.getString(2));
                    eintrag.put("aktion", zeile.getString(3));
                    eintrag.put("dokument", zeile.getString(4));
                    eintrag.put("ip", zeile.getString(5));
                    eintrag.put("grund", zeile.getString(6));
                    eintrag.put("fehlercode", zeile.getString(7));
                    eintrag.put("details_schluessel", zeile.getString(8));
                    eintrag.put("details", zeile.getString(9));
                    eintraege.add(eintrag);
                }
            }
        }
        return eintraege;
    }

    private List<Map<String, String>> eintraegeFuerAktion(String mandant, String aktion) throws SQLException {
        return neueEintraege(mandant).stream().filter(eintrag -> aktion.equals(eintrag.get("aktion"))).toList();
    }

    private static String schema(String mandant) {
        return new MandantCode(mandant).schema();
    }
}
