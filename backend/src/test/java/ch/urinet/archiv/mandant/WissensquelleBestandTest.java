package ch.urinet.archiv.mandant;

import ch.urinet.archiv.konfiguration.domain.Konfiguration;
import ch.urinet.archiv.mandant.application.MandantDatenbank;
import ch.urinet.archiv.mandant.application.MandantProvisionierung;
import ch.urinet.archiv.mandant.domain.MandantCode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.agroal.api.AgroalDataSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Changeset {@code 044-wissensquelle-bestand} (Spec 04, Aufgabe 13.2, Review PR #297): Ein Mandant, der vor 13.2
 * angelegt wurde, erhält die Art {@code WISSENSQUELLE} genau so, wie die Provisionierung sie heute aus dem Seed anlegt
 * (allgemein bzw. in {@code vhf-gsk} mit den reservierten Feldern), und eine neue Version der Konfiguration.
 * Den Bestand stellt der Test her, indem er die Art und den Eintrag des Changesets wieder entfernt.
 */
@QuarkusTest
class WissensquelleBestandTest {

    /** Test-Seed {@code seed/wq-bestand} ohne Wissensquelle; die Provisionierung ergänzt sie. */
    private static final MandantCode MANDANT = new MandantCode("wq-bestand");
    private static final MandantCode VHF = new MandantCode("vhf-gsk");

    @Inject
    MandantProvisionierung provisionierung;

    @Inject
    MandantDatenbank mandantDatenbank;

    @Inject
    Instance<AgroalDataSource> datenbank;

    @Inject
    ObjectMapper json;

    @Test
    void bestehenderMandantErhaeltDieWissensquelleWieAusDemSeedUndEineNeueKonfiguration() throws SQLException {
        anlegenWennFehlt(MANDANT);
        String s = MANDANT.schema();
        String ausSeed = wissensquelle(s);
        // 044 ist unveränderlich und schreibt kein kiTexte; beim Lesen ergänzt KonfigurationVerwaltung sie aus ki_texte
        String vorherKonfiguration = wert("select ((inhalt - 'version') - 'regeln' - 'kiTexte')::text from " + s
                + ".konfiguration_version order by version desc limit 1");
        // Stand vor 13.2: Konfiguration ohne die Art
        ausfuehren("insert into " + s + ".konfiguration_version (version, wer, grund, quelle, inhalt, sha256) "
                + "select k.version + 1, 'system:test', null, 'KOMMANDO', i.inhalt, "
                + "encode(sha256(convert_to(i.inhalt::text, 'UTF8')), 'hex') from " + s + ".konfiguration_version k, "
                + "lateral (select jsonb_set(k.inhalt, '{version}', to_jsonb(k.version + 1)) || jsonb_build_object("
                + "'arten', (select coalesce(jsonb_agg(a order by a ->> 'code' collate \"C\"), '[]'::jsonb) "
                + "from jsonb_array_elements(k.inhalt -> 'arten') a where a ->> 'code' <> 'WISSENSQUELLE')) "
                + "- 'regeln' as inhalt) i "
                + "order by k.version desc limit 1");
        bestandOhneWissensquelle(s);

        mandantDatenbank.schemaMigrieren(s);

        assertThat(wissensquelle(s)).isEqualTo(ausSeed);
        assertThat(wert("select quelle || '/' || wer from " + s
                + ".konfiguration_version order by version desc limit 1")).isEqualTo("MIGRATION/system:migration");
        assertThat(normalisieren(wert("select (inhalt - 'version')::text from " + s
                + ".konfiguration_version order by version desc limit 1")))
                .isEqualTo(normalisieren(vorherKonfiguration));
    }

    @Test
    void vhfGskErhaeltDieWissensquelleMitDenReserviertenFeldern() throws SQLException {
        anlegenWennFehlt(VHF);
        String s = VHF.schema();
        String ausSeed = wissensquelle(s);
        assertThat(wert("select zusatz_schema -> 'required' from " + s
                + ".dokumentart where code = 'WISSENSQUELLE'")).contains("zugriff", "veroeffentlicht");
        bestandOhneWissensquelle(s);

        mandantDatenbank.schemaMigrieren(s);

        assertThat(wissensquelle(s)).isEqualTo(ausSeed);
    }

    @Test
    void neuerMandantUebergehtDasChangesetUndBehaeltDieArtAusDemSeed() throws SQLException {
        anlegenWennFehlt(MANDANT);
        assertThat(wert("select exectype from " + MANDANT.schema()
                + ".databasechangelog where id = '044-wissensquelle-bestand'")).isIn("MARK_RAN", "EXECUTED");
        assertThat(wert("select count(*) from " + MANDANT.schema()
                + ".dokumentart where code = 'WISSENSQUELLE' and not ki_vorschlag")).isEqualTo("1");
    }

    private void bestandOhneWissensquelle(String s) throws SQLException {
        assertThat(ausfuehren("delete from " + s + ".dokumentart where code = 'WISSENSQUELLE'")).isEqualTo(1);
        assertThat(ausfuehren("delete from " + s + ".databasechangelog where id = '044-wissensquelle-bestand'"))
                .isEqualTo(1);
    }

    private void anlegenWennFehlt(MandantCode mandant) throws SQLException {
        if (wert("select count(*) from public.mandant where code = '" + mandant.wert() + "'").equals("0")) {
            provisionierung.anlegen(mandant.wert(), null, null);
        }
    }

    private String wissensquelle(String s) throws SQLException {
        return wert("select to_jsonb(a)::text from " + s + ".dokumentart a where code = 'WISSENSQUELLE'");
    }

    private JsonNode normalisieren(String inhalt) throws SQLException {
        try {
            ObjectNode konfiguration = (ObjectNode) json.readTree(inhalt);
            Konfiguration.Einstellungen einstellungen = json.treeToValue(konfiguration.path("einstellungen"),
                    Konfiguration.Einstellungen.class);
            konfiguration.set("einstellungen", json.valueToTree(einstellungen));
            if (konfiguration.path("klassifikationen").isMissingNode()) {
                konfiguration.putArray("klassifikationen");
            }
            for (JsonNode art : konfiguration.path("arten")) {
                if (art instanceof ObjectNode objekt) {
                    if (objekt.path("klassifikationen").isMissingNode()) {
                        objekt.putArray("klassifikationen");
                    }
                    JsonNode schema = objekt.get("zusatzSchema");
                    if (schema != null && schema.isTextual()) {
                        objekt.set("zusatzSchema", json.readTree(schema.textValue()));
                    }
                    JsonNode aufbewahrung = objekt.path("aufbewahrung");
                    if (aufbewahrung instanceof ObjectNode objektAufbewahrung
                            && objektAufbewahrung.path("feld").isNull()) {
                        objektAufbewahrung.remove("feld");
                    }
                }
            }
            return konfiguration;
        } catch (IOException e) {
            throw new SQLException("Konfiguration ist kein gültiges JSON", e);
        }
    }

    private int ausfuehren(String sql) throws SQLException {
        try (Connection verbindung = datenbank.get().getConnection(); Statement befehl = verbindung.createStatement()) {
            return befehl.executeUpdate(sql);
        }
    }

    private String wert(String sql) throws SQLException {
        try (Connection verbindung = datenbank.get().getConnection();
             PreparedStatement abfrage = verbindung.prepareStatement(sql);
             ResultSet zeile = abfrage.executeQuery()) {
            assertThat(zeile.next()).isTrue();
            return zeile.getString(1);
        }
    }
}
