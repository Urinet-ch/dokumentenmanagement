package ch.urinet.archiv.konfiguration.adapter.out.persistence;

import ch.urinet.archiv.konfiguration.domain.Konfiguration;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.junit.QuarkusTest;
import liquibase.Scope;
import liquibase.command.CommandScope;
import liquibase.command.core.UpdateCommandStep;
import liquibase.command.core.helpers.DbUrlConnectionArgumentsCommandStep;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import liquibase.ui.LoggerUIService;
import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
class KonfigurationMigrationTest {

    private static final List<String> SCHEMAS = List.of(
            "m_probe_konf037_a", "m_probe_konf037_b", "m_probe_volltext055_a", "m_probe_volltext055_b",
            "m_probe_wissen121_a", "m_probe_wissen121_b");
    private static final PanacheKonfigurationVersionsRepository LESER =
            new PanacheKonfigurationVersionsRepository(new ObjectMapper());

    @AfterEach
    void aufraeumen() throws SQLException {
        try (Connection verbindung = verbinden(); Statement befehl = verbindung.createStatement()) {
            for (String schema : SCHEMAS) {
                befehl.execute("drop schema if exists " + schema + " cascade");
                befehl.execute("delete from public.mandant where schema_name = '" + schema + "'");
            }
        }
    }

    @Test
    void changeset037UebernimmtBeideMandantenAusDemEchtenVorgaengerschema() throws Exception {
        Map<String, String> abbilder = new LinkedHashMap<>();
        for (String schema : SCHEMAS.subList(0, 2)) {
            String code = schema.substring(2).replace('_', '-');
            try (Connection verbindung = verbinden(); Statement befehl = verbindung.createStatement()) {
                befehl.execute("create schema " + schema);
                befehl.execute("set search_path to " + schema);
                // 016 enthält zwei Changesets; vor 037 sind es deshalb insgesamt 34.
                migrieren(verbindung, schema, 34);
                verbindung.commit();
                assertThat(wert(befehl, "select id from databasechangelog order by orderexecuted desc limit 1"))
                        .isEqualTo("036-webseite-liste-index");
                assertThat(wert(befehl, "select to_regclass('" + schema + ".konfiguration_version')")).isNull();
                befehl.execute("insert into public.mandant (code, name, schema_name, bucket, geschaeftsjahr_ende, "
                        + "kennungen, aktiv) values ('" + code + "', 'Migration Probe', '" + schema
                        + "', '" + code + "', '06-30', '{\"uid\":\"PROBE-" + code
                        + "\",\"ibans\":[]}', true)");
                befehl.execute("insert into dokumentart (code, name, gruppen, pflichtfelder, zusatz_schema, "
                        + "vertraulichkeit, aufbewahrung_jahre, aufbewahrung_ab, gesetzliche_pflicht, aktiv)"
                        + " values ('ALT', '" + schema + "', '{frei}', '{titel}', '{\"type\":\"object\"}', "
                        + "'INTERN', 10, 'GESCHAEFTSJAHR', true, true)");
                UUID id = UUID.randomUUID();
                befehl.execute("insert into dokument (id, art, titel, quelle, erstellt_von, status, "
                        + "aufbewahren_bis, sperre_bis) values ('" + id
                        + "', 'ALT', 'Bestand Probe', 'UPLOAD', 'probe', 'ARCHIVIERT', '2036-06-30', '2036-06-30')");
                befehl.execute("insert into dokument_version (dokument_id, version, daten, geaendert_von) values ('"
                        + id + "', 1, '{\"titel\":\"Bestand Probe\"}', 'probe')");
                String dokumentVorher = wert(befehl, "select to_jsonb(d)::text from dokument d");
                String versionVorher = wert(befehl, "select to_jsonb(v)::text from dokument_version v");
                verbindung.commit();

                migrieren(verbindung, schema, 1);
                verbindung.commit();

                assertThat(wert(befehl, "select id from databasechangelog order by orderexecuted desc limit 1"))
                        .isEqualTo("037-konfiguration-version");
                assertThat(wert(befehl, "select (to_jsonb(d) - 'konfiguration_version')::text from dokument d"))
                        .isEqualTo(dokumentVorher);
                assertThat(wert(befehl, "select (to_jsonb(v) - 'konfiguration_version')::text from dokument_version v"))
                        .isEqualTo(versionVorher);
                assertThat(wert(befehl, "select konfiguration_version from dokument")).isEqualTo("1");
                assertThat(wert(befehl, "select konfiguration_version from dokument_version")).isEqualTo("1");
                assertThat(wert(befehl, "select quelle from konfiguration_version")).isEqualTo("MIGRATION");
                assertThat(wert(befehl, "select (sha256 = encode(sha256(convert_to(inhalt::text, 'UTF8')), 'hex'))"
                        + "::text from konfiguration_version")).isEqualTo("true");
                abbilder.put(schema, wert(befehl, "select inhalt::text from konfiguration_version"));
            }
        }
        for (String schema : SCHEMAS.subList(0, 2)) {
            try (Connection verbindung = verbinden(); Statement befehl = verbindung.createStatement()) {
                String inhalt = wert(befehl, "select inhalt::text from " + schema + ".konfiguration_version");
                assertThat(inhalt).isEqualTo(abbilder.get(schema));
                Konfiguration gelesen = LESER.lesen(inhalt);
                assertThat(gelesen.einstellungen().geschaeftsjahrEnde()).isEqualTo("06-30");
                assertThat(gelesen.einstellungen().kennungen().uid())
                        .isEqualTo("PROBE-" + schema.substring(2).replace('_', '-'));
                assertThat(gelesen.arten()).singleElement().satisfies(art -> {
                    assertThat(art.code()).isEqualTo("ALT");
                    assertThat(art.name()).isEqualTo(schema);
                    assertThat(art.aufbewahrung()).isEqualTo(new Konfiguration.Aufbewahrung(10, "GESCHAEFTSJAHR"));
                    assertThat(art.pflichtfelder()).containsExactly("titel");
                });
            }
        }
    }

    @Test
    void changeset055StelltBeimRollbackBestandUndBeideSuchindizesWiederHer() throws Exception {
        Map<String, String> bestand = new LinkedHashMap<>();
        Map<String, String> vektoren = new LinkedHashMap<>();
        for (String schema : SCHEMAS.subList(2, 4)) {
            try (Connection verbindung = verbinden(); Statement befehl = verbindung.createStatement()) {
                befehl.execute("create schema " + schema);
                befehl.execute("set search_path to " + schema);
                migrieren(verbindung, schema, 51);
                assertThat(wert(befehl, "select id from databasechangelog order by orderexecuted desc limit 1"))
                        .isEqualTo("054-ki-texte-aus-seed");
                befehl.execute("""
                        insert into dokumentart (code, name, gruppen, pflichtfelder, zusatz_schema,
                            vertraulichkeit, aufbewahrung_jahre, aufbewahrung_ab, gesetzliche_pflicht, aktiv)
                        values ('ALT', 'Probe', '{frei}', '{titel}', '{"type":"object"}',
                            'NORMAL', 10, 'GESCHAEFTSJAHR', true, true)
                        """);
                befehl.execute("insert into dokument (id, art, titel, quelle, erstellt_von, status, volltext, "
                        + "dateiname, tags) values ('" + UUID.randomUUID()
                        + "', 'ALT', 'Bestand Probe', 'UPLOAD', 'probe', 'ARCHIVIERT', "
                        + "'Police Versicherung', 'Zebrafink.pdf', '{bestand}')");
                befehl.execute("""
                        insert into klassifikation (code, name, mehrfach, ki_vorschlag, suchfilter, oeffentlich_filter)
                        values ('fach', 'Fach', false, false, true, false);
                        insert into klassifikation_wert (klassifikation, code, name)
                        values ('fach', 'bau', 'Elektrotechnik');
                        insert into dokument_klassifikation (dokument_id, klassifikation, wert)
                        select id, 'fach', 'bau' from dokument
                        """);
                bestand.put(schema, wert(befehl, "select (to_jsonb(d) - 'volltext_tsv')::text from dokument d"));
                vektoren.put(schema, wert(befehl, "select volltext_tsv::text from dokument"));
                verbindung.commit();
                migrieren(verbindung, schema, 1);
                verbindung.commit();
                assertThat(wert(befehl, "select (volltext_tsv @@ plainto_tsquery('german', 'Elektrotechnik'))::text "
                        + "from dokument")).isEqualTo("true");
            }
        }
        for (String schema : SCHEMAS.subList(2, 4)) {
            String anderes = SCHEMAS.get(schema.endsWith("_a") ? 3 : 2);
            try (Connection verbindung = verbinden(); Statement befehl = verbindung.createStatement()) {
                befehl.execute("set search_path to " + schema);
                String andererStand = wert(befehl, "select to_jsonb(d)::text from " + anderes + ".dokument d");
                aendern(verbindung, schema, "rollbackCount", 1);
                verbindung.commit();
                assertThat(wert(befehl, "select (to_jsonb(d) - 'volltext_tsv')::text from dokument d"))
                        .isEqualTo(bestand.get(schema));
                assertThat(wert(befehl, "select volltext_tsv::text from dokument")).isEqualTo(vektoren.get(schema));
                assertThat(wert(befehl, "select attgenerated from pg_attribute "
                        + "where attrelid = 'dokument'::regclass and attname = 'volltext_tsv'")).isEqualTo("s");
                assertThat(wert(befehl, "select count(*)::text from pg_index where indrelid = 'dokument'::regclass "
                        + "and indisvalid and indexrelid in ('dokument_volltext_ix'::regclass, "
                        + "'dokument_volltext_ohne_dateiname_ix'::regclass)")).isEqualTo("2");
                assertThat(wert(befehl, "select to_jsonb(d)::text from " + anderes + ".dokument d"))
                        .isEqualTo(andererStand);
                befehl.execute("update dokument set titel = 'Neuer Titel'");
                assertThat(wert(befehl, "select (volltext_tsv @@ plainto_tsquery('german', 'Neuer'))::text "
                        + "from dokument")).isEqualTo("true");
                verbindung.commit();
                migrieren(verbindung, schema, 1);
                verbindung.commit();
                assertThat(wert(befehl, "select (volltext_tsv @@ plainto_tsquery('german', 'Elektrotechnik'))::text "
                        + "from dokument")).isEqualTo("true");
            }
        }
    }

    @Test
    void wissenswerteWerdenMandantengetrenntMigriertUndAltwerteGesichert() throws Exception {
        for (String schema : SCHEMAS.subList(4, 6)) {
            try (Connection verbindung = verbinden(); Statement befehl = verbindung.createStatement()) {
                befehl.execute("create schema " + schema);
                befehl.execute("set search_path to " + schema);
                alleMigrieren(verbindung, schema);
                aendern(verbindung, schema, "rollbackCount", 1);
                String werte = schema.endsWith("_a")
                        ? "123456, 234567, '0 0 23 ? * MON', 345678"
                        : "1, 2000000, '0 0/15 * * * ?', 2";
                befehl.execute("insert into wissens_einstellung "
                        + "(budget_tokens, lint_budget_tokens, lint_zeitplan, monatsbudget_tokens) values (" + werte + ")");
                verbindung.commit();
                migrieren(verbindung, schema, 1);
                verbindung.commit();

                String inhalt = wert(befehl, "select inhalt::text from konfiguration_version order by version desc limit 1");
                var wissen = LESER.lesen(inhalt).einstellungen().wissen();
                assertThat(wert(befehl, "select quelle from konfiguration_version order by version desc limit 1"))
                        .isEqualTo("MIGRATION");
                assertThat(wert(befehl, "select (sha256 = encode(sha256(convert_to(inhalt::text, 'UTF8')), 'hex'))"
                        + "::text from konfiguration_version order by version desc limit 1")).isEqualTo("true");
                assertThat(wert(befehl, "select count(*)::text from zugriff_protokoll "
                        + "where aktion = 'KONFIGURATION_AENDERN' and benutzer = 'system:migration'")).isEqualTo("1");
                if (schema.endsWith("_a")) {
                    assertThat(wissen.budgetTokens()).isEqualTo(123456);
                    assertThat(wissen.lintBudgetTokens()).isEqualTo(234567);
                    assertThat(wissen.monatsbudgetTokens()).isEqualTo(345678);
                    assertThat(wissen.lintZeitplan()).isEqualTo(
                            new Konfiguration.LintZeitplan(java.time.DayOfWeek.MONDAY, 23));
                    assertThat(wert(befehl, "select count(*)::text from wissens_einstellung_migration")).isEqualTo("0");
                } else {
                    assertThat(wissen.budgetTokens()).isEqualTo(600000);
                    assertThat(wissen.lintBudgetTokens()).isEqualTo(100000);
                    assertThat(wissen.monatsbudgetTokens()).isEqualTo(1000000);
                    assertThat(wissen.lintZeitplan()).isEqualTo(
                            new Konfiguration.LintZeitplan(java.time.DayOfWeek.SUNDAY, 4));
                    assertThat(wert(befehl, "select lint_zeitplan from wissens_einstellung_migration"))
                            .isEqualTo("0 0/15 * * * ?");
                    assertThat(wert(befehl, "select details::text from zugriff_protokoll "
                            + "where benutzer = 'system:migration'")).contains("wissen.lintZeitplan",
                            "wissen.budgetTokens", "wissen.lintBudgetTokens", "wissen.monatsbudgetTokens");
                }
                String alt = wert(befehl, "select to_jsonb(e)::text from wissens_einstellung e");
                aendern(verbindung, schema, "rollbackCount", 1);
                verbindung.commit();
                assertThat(wert(befehl, "select to_jsonb(e)::text from wissens_einstellung e")).isEqualTo(alt);
            }
        }
    }

    private static void alleMigrieren(Connection verbindung, String schema) throws Exception {
        var datenbank = DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(verbindung));
        datenbank.setDefaultSchemaName(schema);
        datenbank.setLiquibaseSchemaName(schema);
        Scope.child(Map.of(
                Scope.Attr.resourceAccessor.name(), new ClassLoaderResourceAccessor(
                        KonfigurationMigrationTest.class.getClassLoader()),
                Scope.Attr.ui.name(), new LoggerUIService()), () ->
                new CommandScope("update")
                        .addArgumentValue(DbUrlConnectionArgumentsCommandStep.DATABASE_ARG, datenbank)
                        .addArgumentValue(UpdateCommandStep.CHANGELOG_FILE_ARG, "db/mandant/changelog.xml")
                        .execute());
    }

    private static void migrieren(Connection verbindung, String schema, int anzahl) throws Exception {
        aendern(verbindung, schema, "updateCount", anzahl);
    }

    private static void aendern(Connection verbindung, String schema, String kommando, int anzahl) throws Exception {
        var datenbank = DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(verbindung));
        datenbank.setDefaultSchemaName(schema);
        datenbank.setLiquibaseSchemaName(schema);
        Scope.child(Map.of(
                Scope.Attr.resourceAccessor.name(), new ClassLoaderResourceAccessor(
                        KonfigurationMigrationTest.class.getClassLoader()),
                Scope.Attr.ui.name(), new LoggerUIService()), () ->
                new CommandScope(kommando)
                        .addArgumentValue("count", anzahl)
                        .addArgumentValue(DbUrlConnectionArgumentsCommandStep.DATABASE_ARG, datenbank)
                        .addArgumentValue(UpdateCommandStep.CHANGELOG_FILE_ARG, "db/mandant/changelog.xml")
                        .execute());
    }

    private static Connection verbinden() throws SQLException {
        var config = ConfigProvider.getConfig();
        return DriverManager.getConnection(config.getValue("quarkus.datasource.jdbc.url", String.class),
                config.getValue("quarkus.datasource.username", String.class),
                config.getValue("quarkus.datasource.password", String.class));
    }

    private static String wert(Statement befehl, String sql) throws SQLException {
        try (ResultSet ergebnis = befehl.executeQuery(sql)) {
            assertThat(ergebnis.next()).isTrue();
            return ergebnis.getString(1);
        }
    }
}
