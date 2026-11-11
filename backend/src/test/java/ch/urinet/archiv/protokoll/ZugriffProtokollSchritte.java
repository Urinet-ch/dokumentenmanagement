package ch.urinet.archiv.protokoll;

import ch.urinet.archiv.gemeinsam.Testanmeldung;
import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.probe.ProbeSichtbarkeit;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agroal.api.AgroalDataSource;
import io.cucumber.datatable.DataTable;
import io.cucumber.java.After;
import io.cucumber.java.de.Angenommen;
import io.cucumber.java.de.Dann;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Schritte zu Req 10.2, 10.3, 10.7 und 10.8 (Grundlage aus Aufgabe 1.6) über die Probe-Endpunkte unter src/test, dazu
 * die Regeländerung einer Dokumentart (Req 9.10).
 */
public class ZugriffProtokollSchritte {

    private static final String FEHLER_TRIGGER = "probe_protokoll_fehler";

    @Inject
    Instance<AgroalDataSource> datenbank;

    private final Map<String, Long> stand = new HashMap<>();
    private final List<String> fehlerSchemas = new ArrayList<>();
    private final List<String[]> angelegteArten = new ArrayList<>();

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
        List<Map<String, String>> neu = neueEintraege(mandant);
        assertThat(neu).hasSize(1);
        Map<String, String> eintrag = neu.getFirst();
        assertThat(eintrag.get("benutzer")).isEqualTo(erwartet.get("benutzer") == null ? null
                : sub(erwartet.get("benutzer")));
        assertThat(eintrag.get("rolle")).isEqualTo(erwartet.get("rolle"));
        assertThat(eintrag.get("aktion")).isEqualTo(erwartet.get("aktion"));
        assertThat(eintrag.get("dokument")).isEqualTo(erwartet.get("dokument") == null ? null
                : ProbeSichtbarkeit.NAMEN.get(erwartet.get("dokument")).toString());
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
        List<Map<String, String>> neu = neueEintraege(mandant);
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

    private static List<String> feldnamen(JsonNode knoten) {
        List<String> namen = new ArrayList<>();
        knoten.fieldNames().forEachRemaining(namen::add);
        return namen;
    }

    @Dann("das Zugriffsprotokoll von «{word}» hat keinen neuen Eintrag")
    public void keinNeuerEintrag(String mandant) throws SQLException {
        assertThat(neueEintraege(mandant)).isEmpty();
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

    private static String sub(String benutzer) throws IOException {
        String token = Testanmeldung.token(benutzer);
        JsonNode claims = new ObjectMapper().readTree(Base64.getUrlDecoder().decode(token.split("\\.")[1]));
        return claims.path("sub").asText();
    }

    private static String schema(String mandant) {
        return new MandantCode(mandant).schema();
    }
}
