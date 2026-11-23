package ch.urinet.archiv.mandant;

import ch.urinet.archiv.dokument.Testdokumente;
import ch.urinet.archiv.mandant.application.MandantDatenbank;
import ch.urinet.archiv.mandant.application.MandantProvisionierung;
import ch.urinet.archiv.mandant.domain.MandantCode;
import io.agroal.api.AgroalDataSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import liquibase.Scope;
import liquibase.command.CommandScope;
import liquibase.command.core.UpdateCommandStep;
import liquibase.command.core.helpers.DbUrlConnectionArgumentsCommandStep;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import liquibase.ui.LoggerUIService;
import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
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
    void migrationBrichtBeiVerweisAbUndRollbackErhaeltDokumente() throws Exception {
        anlegenWennFehlt(MANDANT);
        String s = MANDANT.schema();
        ausfuehren("insert into " + s + ".dokumentart (code, name, vertraulichkeit, gesetzliche_pflicht) "
                + "values ('PROJEKTDOKUMENT', 'Projektdokument', 'NORMAL', false) on conflict do nothing");
        ausfuehren("insert into " + s + ".dokumentart (code, name, vertraulichkeit, gesetzliche_pflicht) "
                + "values ('WISSENSQUELLE', 'Wissensquelle', 'NORMAL', false)");
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
        ausfuehren("drop table " + s + ".wissensquelle_markierung");
        ausfuehren("delete from " + s + ".databasechangelog where id = '070-wissensquelle-markierung'");
        String alteVersion = wert("select max(version)::text from " + s + ".konfiguration_version");
        ausfuehren("delete from " + s + ".dokumentart where code = 'PROJEKTDOKUMENT'");
        assertThatThrownBy(() -> mandantDatenbank.schemaMigrieren(s))
                .hasStackTraceContaining("Dokumentart PROJEKTDOKUMENT fehlt");
        ausfuehren("insert into " + s + ".dokumentart (code, name, vertraulichkeit, gesetzliche_pflicht) "
                + "values ('PROJEKTDOKUMENT', 'Projektdokument', 'NORMAL', false)");
        UUID projektdokument = Testdokumente.anlegen(datenbank.get(),
                new Testdokumente.Angaben(MANDANT.wert(), "PROJEKTDOKUMENT", "NORMAL", true, "probe", null,
                        "ARCHIVIERT"));
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
        assertThat(wert("select count(*) from " + s + ".dokument where id = '" + projektdokument
                + "' and art = 'PROJEKTDOKUMENT' and version = 1")).isEqualTo("1");
        assertThat(wert("select count(*) from " + s + ".dokument where art = 'PROJEKTDOKUMENT' "
                + "and konfiguration_version = (select max(version) from " + s + ".konfiguration_version)"))
                .isEqualTo("3");
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
        String neueVersion = wert("select max(version)::text from " + s + ".konfiguration_version");
        ausfuehren("delete from " + s + ".databasechangelog where id = '070-wissensquelle-markierung'");
        mandantDatenbank.schemaMigrieren(s);
        assertThat(wert("select count(*) from " + s + ".wissensquelle_markierung")).isEqualTo("2");
        assertThat(wert("select count(*) from " + s + ".dokument_version where grund = 'Migration Wissensquelle'"))
                .isEqualTo("2");
        assertThat(wert("select max(version)::text from " + s + ".konfiguration_version")).isEqualTo(neueVersion);

        rollbackWissensquelle(s);
        assertThat(wert("select count(*) from " + s + ".dokumentart where code = 'WISSENSQUELLE' "
                + "and name = 'Wissensquelle' and beschreibung = "
                + "'Fachunterlage, deren Inhalt als Quelle für die Wissensbasis dient.' "
                + "and gruppen = array['frei']::text[] "
                + "and pflichtfelder = array['titel', 'datum']::text[] and vertraulichkeit = 'NORMAL' "
                + "and zusatz_schema = '{\"type\":\"object\",\"properties\":{\"herausgeber\":"
                + "{\"type\":\"string\",\"minLength\":1}},\"additionalProperties\":false}'::jsonb "
                + "and aufbewahrung_jahre is null and aufbewahrung_ab is null "
                + "and gesetzliche_pflicht = false and aktiv and not selbst_ausgestellt "
                + "and belegnummer_muster = '{}'::text[] and not ki_vorschlag "
                + "and ki_felder = '{}'::text[] and reihenfolge = 0"))
                .isEqualTo("1");
        assertThat(wert("select count(*) from " + s + ".dokument where art = 'WISSENSQUELLE'"))
                .isEqualTo("2");
        assertThat(wert("select count(*) from " + s + ".konfiguration_version k, "
                + "jsonb_array_elements(k.inhalt->'arten') a where k.version = (select max(version) from " + s
                + ".konfiguration_version) and a->>'code' = 'WISSENSQUELLE'")).isEqualTo("1");
        assertThat(wert("select count(*) from " + s + ".dokument where art = 'WISSENSQUELLE' "
                + "and konfiguration_version = (select max(version) from " + s + ".konfiguration_version)"))
                .isEqualTo("2");
        assertThat(wert("select count(*) from " + s + ".dokument d join " + s
                + ".wissensquelle_markierung m on m.dokument_id = d.id "
                + "where m.beendet_am is null and d.art = 'WISSENSQUELLE'")).isEqualTo("2");
        assertThat(wert("select count(*) from " + s + ".dokument where id = '" + projektdokument
                + "' and art = 'PROJEKTDOKUMENT' and version = 1")).isEqualTo("1");
        assertThat(wert("select count(*) from " + s
                + ".dokument_version where grund = 'Rollback Migration Wissensquelle'")).isEqualTo("2");

        mandantDatenbank.schemaMigrieren(s);
        rollbackWissensquelle(s);
        assertThat(wert("select count(*) from " + s + ".dokument where art = 'WISSENSQUELLE'")).isEqualTo("2");
        assertThat(wert("select count(*) from " + s + ".wissensquelle_markierung "
                + "where beendet_am is null")).isEqualTo("2");
        assertThat(wert("select count(*) from " + s
                + ".dokument_version where grund = 'Rollback Migration Wissensquelle'")).isEqualTo("4");

        mandantDatenbank.schemaMigrieren(s);
        assertThat(wert("select count(*) from " + s + ".dokument where art = 'WISSENSQUELLE'")).isEqualTo("0");
        assertThat(wert("select count(*) from " + s + ".wissensquelle_markierung "
                + "where beendet_am is null")).isEqualTo("2");
        Testdokumente.loeschen(datenbank.get(), MANDANT.wert(), List.of(erstes, zweites, projektdokument));
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

    private static void rollbackWissensquelle(String schema) throws Exception {
        try (Connection verbindung = DriverManager.getConnection(
                ConfigProvider.getConfig().getValue("quarkus.datasource.jdbc.url", String.class),
                ConfigProvider.getConfig().getValue("quarkus.datasource.username", String.class),
                ConfigProvider.getConfig().getValue("quarkus.datasource.password", String.class))) {
            verbindung.setAutoCommit(false);
            var liquibaseDb = DatabaseFactory.getInstance()
                    .findCorrectDatabaseImplementation(new JdbcConnection(verbindung));
            liquibaseDb.setDefaultSchemaName(schema);
            liquibaseDb.setLiquibaseSchemaName(schema);
            Scope.child(Map.of(
                    Scope.Attr.resourceAccessor.name(),
                    new ClassLoaderResourceAccessor(WissensquelleBestandTest.class.getClassLoader()),
                    Scope.Attr.ui.name(), new LoggerUIService()), () ->
                    new CommandScope("rollbackCount")
                            .addArgumentValue("count", 1)
                            .addArgumentValue(DbUrlConnectionArgumentsCommandStep.DATABASE_ARG, liquibaseDb)
                            .addArgumentValue(UpdateCommandStep.CHANGELOG_FILE_ARG,
                                    "db/mandant/changesets/070-wissensquelle-markierung.xml")
                            .execute());
            verbindung.commit();
        }
    }
}
