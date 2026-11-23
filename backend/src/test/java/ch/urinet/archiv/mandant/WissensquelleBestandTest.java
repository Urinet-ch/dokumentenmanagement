package ch.urinet.archiv.mandant;

import ch.urinet.archiv.dokument.Testdokumente;
import ch.urinet.archiv.mandant.application.MandantDatenbank;
import ch.urinet.archiv.mandant.application.MandantProvisionierung;
import ch.urinet.archiv.mandant.domain.MandantCode;
import io.agroal.api.AgroalDataSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import liquibase.Liquibase;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@QuarkusTest
class WissensquelleBestandTest {
    private static final MandantCode MANDANT = new MandantCode("wq-bestand");
    private static final MandantCode VHF = new MandantCode("vhf-gsk");

    @Inject MandantProvisionierung provisionierung;
    @Inject MandantDatenbank mandantDatenbank;
    @Inject Instance<AgroalDataSource> datenbank;

    @Test
    void migrationBrichtBeiVerweisAbUndIstMitRollbackDatenverlustfrei() throws Exception {
        anlegenWennFehlt(MANDANT);
        String s = MANDANT.schema();
        ausfuehren("insert into " + s + ".dokumentart (code, name, vertraulichkeit, gesetzliche_pflicht) "
                + "values ('PROJEKTDOKUMENT', 'Projektdokument', 'NORMAL', false) on conflict do nothing");
        ausfuehren("insert into " + s + ".dokumentart (code, name, vertraulichkeit, gesetzliche_pflicht) "
                + "values ('WISSENSQUELLE', 'Wissensquelle', 'NORMAL', false)");
        ausfuehren("insert into " + s + ".dokumentart (code, name, vertraulichkeit, gesetzliche_pflicht) "
                + "values ('QUITTUNG', 'Quittung', 'NORMAL', false) on conflict do nothing");
        ausfuehren("insert into " + s + ".konfiguration_version (version, wer, grund, quelle, inhalt, sha256) "
                + "select k.version + 1, 'probe', 'Altbestand', 'KOMMANDO', i.inhalt, "
                + "encode(sha256(convert_to(i.inhalt::text, 'UTF8')), 'hex') from " + s + ".konfiguration_version k, "
                + "lateral (select jsonb_set(jsonb_set(k.inhalt, '{version}', to_jsonb(k.version + 1)), "
                + "'{arten}', k.inhalt->'arten' || '[{\"code\":\"WISSENSQUELLE\"}]'::jsonb) as inhalt) i "
                + "order by k.version desc limit 1");
        UUID erstes = Testdokumente.anlegen(datenbank.get(),
                new Testdokumente.Angaben(MANDANT.wert(), "WISSENSQUELLE", "NORMAL", true, "probe", null, "ARCHIVIERT"));
        UUID zweites = Testdokumente.anlegen(datenbank.get(),
                new Testdokumente.Angaben(MANDANT.wert(), "WISSENSQUELLE", "INTERN", true, "probe", null, "ARCHIVIERT"));
        UUID quittung = Testdokumente.anlegen(datenbank.get(),
                new Testdokumente.Angaben(MANDANT.wert(), "QUITTUNG", "NORMAL", true, "probe", null, "ARCHIVIERT"));
        UUID projekt;
        ausfuehren("drop table " + s + ".wissensquelle_markierung");
        ausfuehren("delete from " + s + ".databasechangelog where id = '070-wissensquelle-markierung'");
        String alteVersion = wert("select max(version)::text from " + s + ".konfiguration_version");
        ausfuehren("delete from " + s + ".dokumentart where code = 'PROJEKTDOKUMENT'");
        assertThatThrownBy(() -> mandantDatenbank.schemaMigrieren(s))
                .hasStackTraceContaining("Dokumentart PROJEKTDOKUMENT fehlt");
        ausfuehren("insert into " + s + ".dokumentart (code, name, vertraulichkeit, gesetzliche_pflicht) "
                + "values ('PROJEKTDOKUMENT', 'Projektdokument', 'NORMAL', false)");
        projekt = Testdokumente.anlegen(datenbank.get(),
                new Testdokumente.Angaben(MANDANT.wert(), "PROJEKTDOKUMENT", "NORMAL", true, "probe", null, "ARCHIVIERT"));
        ausfuehren("insert into " + s + ".klassifikation "
                + "(code, name, mehrfach, ki_vorschlag, suchfilter, oeffentlich_filter, aktiv) "
                + "values ('migrationstest', 'Migrationstest', false, false, false, false, true)");
        record Verweis(String anlegen, String pruefen, String entfernen) { }
        List<Verweis> verweise = List.of(
                new Verweis("update " + s + ".dokumentart set fremdbeleg_art = 'WISSENSQUELLE' where code = 'PROBE_BELEG'",
                        "select count(*) from " + s + ".dokumentart where fremdbeleg_art = 'WISSENSQUELLE'",
                        "update " + s + ".dokumentart set fremdbeleg_art = null where code = 'PROBE_BELEG'"),
                new Verweis("update " + s + ".dokumentart set ohne_zahlungsdaten_art = 'WISSENSQUELLE' where code = 'PROBE_BELEG'",
                        "select count(*) from " + s + ".dokumentart where ohne_zahlungsdaten_art = 'WISSENSQUELLE'",
                        "update " + s + ".dokumentart set ohne_zahlungsdaten_art = null where code = 'PROBE_BELEG'"),
                new Verweis("insert into " + s + ".belegnummer_eigen (art, belegnummer, dokument_id) values "
                        + "('WISSENSQUELLE', 'probe', '" + erstes + "')",
                        "select count(*) from " + s + ".belegnummer_eigen where art = 'WISSENSQUELLE'",
                        "delete from " + s + ".belegnummer_eigen where art = 'WISSENSQUELLE'"),
                new Verweis("insert into " + s + ".dokumentart_klassifikation (art, klassifikation, pflicht) "
                        + "values ('WISSENSQUELLE', 'migrationstest', false)",
                        "select count(*) from " + s + ".dokumentart_klassifikation where art = 'WISSENSQUELLE'",
                        "delete from " + s + ".dokumentart_klassifikation where art = 'WISSENSQUELLE'"),
                new Verweis("insert into " + s + ".regel (code, art, typ, parameter, schwere, meldung, aktiv) "
                        + "values ('migrationstest', 'WISSENSQUELLE', 'FOLGE', "
                        + "'{\"feld\":\"geschaeftsjahr\",\"zeitraum\":\"GESAMT\"}', 'WARNUNG', 'Probe', true)",
                        "select count(*) from " + s + ".regel where art = 'WISSENSQUELLE'",
                        "delete from " + s + ".regel where code = 'migrationstest'"));
        for (Verweis verweis : verweise) {
            ausfuehren(verweis.anlegen());
            assertThatThrownBy(() -> mandantDatenbank.schemaMigrieren(s))
                    .hasStackTraceContaining("Ein Verweis auf die Dokumentart WISSENSQUELLE besteht weiterhin");
            assertThat(wert(verweis.pruefen())).isEqualTo("1");
            assertThat(wert("select count(*) from " + s + ".dokument where art = 'WISSENSQUELLE'")).isEqualTo("2");
            assertThat(wert("select count(*) from " + s + ".dokument_version where grund = 'Migration Wissensquelle'"))
                    .isEqualTo("0");
            assertThat(wert("select max(version)::text from " + s + ".konfiguration_version")).isEqualTo(alteVersion);
            assertThat(wert("select to_regclass('" + s + ".wissensquelle_markierung') is null")).isEqualTo("t");
            ausfuehren(verweis.entfernen());
        }
        ausfuehren("delete from " + s + ".klassifikation where code = 'migrationstest'");
        mandantDatenbank.schemaMigrieren(s);
        assertThat(wert("select count(*) from " + s
                + ".dokument where art = 'PROJEKTDOKUMENT' and version = 1")).isEqualTo("2");
        assertThat(wert("select count(*) from " + s + ".dokument where art = 'PROJEKTDOKUMENT' "
                + "and konfiguration_version = (select max(version) from " + s + ".konfiguration_version)"))
                .isEqualTo("2");
        assertThat(wert("select count(*) from " + s + ".wissensquelle_markierung "
                + "where markiert_von = 'System' and beendet_am is null")).isEqualTo("2");
        assertThat(wert("select count(*) from " + s + ".dokument_version "
                + "where grund = 'Migration Wissensquelle' and daten->'dokument'->>'art' = 'WISSENSQUELLE'"))
                .isEqualTo("2");
        assertThat(wert("select count(*) from " + s + ".dokumentart where code = 'WISSENSQUELLE'")).isEqualTo("0");
        assertThat(wert("select quelle from " + s + ".konfiguration_version order by version desc limit 1"))
                .isEqualTo("MIGRATION");
        assertThat(wert("select count(*) from " + s + ".konfiguration_version k, "
                + "jsonb_array_elements(k.inhalt->'arten') a where k.version = (select max(version) from " + s
                + ".konfiguration_version) and a->>'code' = 'WISSENSQUELLE'")).isEqualTo("0");
        assertThat(wert("select art from " + s + ".dokument where id = '" + quittung + "'")).isEqualTo("QUITTUNG");
        assertThat(wert("select art from " + s + ".dokument where id = '" + projekt + "'")).isEqualTo("PROJEKTDOKUMENT");
        assertThat(wert("select count(*) from " + s + ".wissensquelle_markierung "
                + "where dokument_id in ('" + quittung + "', '" + projekt + "')")).isEqualTo("0");

        rollbackWissensquelleMarkierung(s);
        assertThat(wert("select count(*) from " + s + ".dokument where art = 'WISSENSQUELLE' "
                + "and id in ('" + erstes + "', '" + zweites + "')")).isEqualTo("2");
        assertThat(wert("select count(*) from " + s + ".wissensquelle_markierung "
                + "where dokument_id in ('" + erstes + "', '" + zweites + "') and beendet_am is null"))
                .isEqualTo("2");
        assertThat(wert("select count(*) from " + s + ".dokument where id = '" + quittung
                + "' and art = 'QUITTUNG'")).isEqualTo("1");
        assertThat(wert("select count(*) from " + s + ".dokument where id = '" + projekt
                + "' and art = 'PROJEKTDOKUMENT'")).isEqualTo("1");
        assertThat(wert("select name from " + s + ".dokumentart where code = 'WISSENSQUELLE'"))
                .isEqualTo("Wissensquelle");
        assertThat(wert("select count(*) from " + s + ".dokumentart where code = 'WISSENSQUELLE' "
                + "and beschreibung = 'Fachunterlage, deren Inhalt als Quelle für die Wissensbasis dient.' "
                + "and gruppen = array['frei']::text[] and pflichtfelder = array['titel', 'datum']::text[] "
                + "and zusatz_schema = '{\"type\":\"object\",\"properties\":{\"herausgeber\":"
                + "{\"type\":\"string\",\"minLength\":1}},\"additionalProperties\":false}'::jsonb "
                + "and vertraulichkeit = 'NORMAL' and aufbewahrung_jahre is null and aufbewahrung_ab is null "
                + "and not gesetzliche_pflicht and aktiv and not selbst_ausgestellt "
                + "and belegnummer_muster = '{}'::text[] and not ki_vorschlag and ki_felder = '{}'::text[] "
                + "and reihenfolge = 0")).isEqualTo("1");
        assertThat(wert("select count(*) from " + s + ".konfiguration_version k, "
                + "jsonb_array_elements(k.inhalt->'arten') a where k.version = (select max(version) from " + s
                + ".konfiguration_version) and a->>'code' = 'WISSENSQUELLE' and a->>'name' = 'Wissensquelle'"))
                .isEqualTo("1");
        assertThat(wert("select count(*) from " + s + ".dokument_version where dokument_id in ('" + erstes
                + "', '" + zweites + "') and grund = 'Rollback Wissensquelle' "
                + "and daten->'dokument'->>'art' = 'PROJEKTDOKUMENT'")).isEqualTo("2");
        String rollbackVersion = wert("select max(version)::text from " + s + ".konfiguration_version");
        String rollbackDokumentVersion = wert("select version::text from " + s + ".dokument where id = '" + erstes + "'");
        rollbackWissensquelleMarkierung(s);
        assertThat(wert("select max(version)::text from " + s + ".konfiguration_version")).isEqualTo(rollbackVersion);
        assertThat(wert("select version::text from " + s + ".dokument where id = '" + erstes + "'"))
                .isEqualTo(rollbackDokumentVersion);
        assertThat(wert("select count(*) from " + s + ".dokument_version where grund = 'Rollback Wissensquelle'"))
                .isEqualTo("2");

        Testdokumente.loeschen(datenbank.get(), MANDANT.wert(), List.of(erstes, zweites, quittung, projekt));
        ausfuehren("delete from " + s + ".databasechangelog where id = '070-wissensquelle-markierung'");
        mandantDatenbank.schemaMigrieren(s);
        assertThat(wert("select count(*) from " + s + ".wissensquelle_markierung")).isEqualTo("0");
        assertThat(wert("select count(*) from " + s + ".dokument_version where grund = 'Migration Wissensquelle'"))
                .isEqualTo("0");
        assertThat(wert("select count(*) from " + s + ".dokumentart where code = 'WISSENSQUELLE'")).isEqualTo("0");
    }

    @Test
    void neuerMandantUndVhfHabenKeineDokumentartWissensquelle() throws SQLException {
        for (MandantCode mandant : List.of(MANDANT, VHF)) {
            anlegenWennFehlt(mandant);
            assertThat(wert("select exectype from " + mandant.schema()
                    + ".databasechangelog where id = '044-wissensquelle-bestand'")).isIn("MARK_RAN", "EXECUTED");
            assertThat(wert("select count(*) from " + mandant.schema()
                    + ".dokumentart where code = 'WISSENSQUELLE'")).isEqualTo("0");
        }
    }

    private void anlegenWennFehlt(MandantCode mandant) throws SQLException {
        if (wert("select count(*) from public.mandant where code = '" + mandant.wert() + "'").equals("0")) {
            provisionierung.anlegen(mandant.wert(), null, null);
        }
    }

    private void ausfuehren(String sql) throws SQLException {
        try (Connection verbindung = datenbank.get().getConnection(); Statement befehl = verbindung.createStatement()) {
            befehl.executeUpdate(sql);
        }
    }

    private String wert(String sql) throws SQLException {
        try (Connection verbindung = datenbank.get().getConnection(); Statement befehl = verbindung.createStatement();
             ResultSet zeile = befehl.executeQuery(sql)) {
            assertThat(zeile.next()).isTrue();
            return zeile.getString(1);
        }
    }

    private void rollbackWissensquelleMarkierung(String schema) throws Exception {
        try (Connection verbindung = datenbank.get().getConnection()) {
            try (Statement befehl = verbindung.createStatement()) {
                befehl.execute("set search_path to " + schema);
            }
            Database datenbankLiquibase = DatabaseFactory.getInstance()
                    .findCorrectDatabaseImplementation(new JdbcConnection(verbindung));
            datenbankLiquibase.setDefaultSchemaName(schema);
            datenbankLiquibase.setLiquibaseSchemaName(schema);
            Liquibase liquibase = new Liquibase("db/mandant/changelog.xml",
                    new ClassLoaderResourceAccessor(getClass().getClassLoader()), datenbankLiquibase);
            liquibase.getDatabaseChangeLog().getChangeSets().stream()
                    .filter(changeSet -> changeSet.getId().equals("070-wissensquelle-markierung"))
                    .findFirst()
                    .orElseThrow()
                    .rollback(datenbankLiquibase);
            try (Statement befehl = verbindung.createStatement()) {
                befehl.execute("reset search_path");
            }
        }
    }
}
