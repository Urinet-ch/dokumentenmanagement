package ch.urinet.archiv.mandant.adapter.out.persistence;

import ch.urinet.archiv.mandant.application.MandantDatenbank;
import ch.urinet.archiv.mandant.domain.DokumentartVorlage;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import liquibase.Scope;
import liquibase.command.CommandScope;
import liquibase.command.core.UpdateCommandStep;
import liquibase.command.core.helpers.DbUrlConnectionArgumentsCommandStep;
import liquibase.database.Database;
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
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Ein neuer Mandant bekommt nach dem Seed eine Version mit Quelle {@code SEED} (Spec 05, Req 1.1); ein zweiter Lauf
 * ohne Unterschied legt keine weitere an. Das Abbild trägt {@code zusatzSchema} auch aus jsonb als Text.
 */
@QuarkusTest
class KonfigurationSeedVersionTest {

    private static final String SCHEMA = "m_probe_konf_seed";
    private static final String MIGRATION_SCHEMA = "m_probe_konf_migration";

    @Inject
    MandantDatenbank datenbank;

    @AfterEach
    void aufraeumen() throws SQLException {
        try (Connection verbindung = verbinden(); Statement befehl = verbindung.createStatement()) {
            befehl.execute("drop schema if exists " + SCHEMA + " cascade");
        }
    }

    @Test
    void seedErzeugtGenauEineVersionMitAbbildDerArten() throws SQLException {
        datenbank.schemaAnlegen(SCHEMA);
        datenbank.dokumentartenEinspielen(SCHEMA, List.of(
                new DokumentartVorlage("SEED_B", "B", null, List.of(), List.of(),
                        "{\"type\":\"object\",\"properties\":{\"x\":{\"type\":\"string\"}}}", "NORMAL", 10,
                        "GESCHAEFTSJAHR", false, true),
                new DokumentartVorlage("SEED_A", "A", null, List.of(), List.of(), null, "NORMAL", null, null, false,
                        true)));

        datenbank.konfigurationSeedAnlegen(SCHEMA);
        datenbank.konfigurationSeedAnlegen(SCHEMA);

        try (Connection verbindung = verbinden(); Statement befehl = verbindung.createStatement()) {
            assertThat(wert(befehl, "select count(*) from " + SCHEMA + ".konfiguration_version")).isEqualTo("2");
            assertThat(wert(befehl, "select quelle from " + SCHEMA + ".konfiguration_version where version = 1"))
                    .isEqualTo("MIGRATION");
            assertThat(wert(befehl, "select quelle from " + SCHEMA + ".konfiguration_version where version = 2"))
                    .isEqualTo("SEED");
            assertThat(wert(befehl, "select inhalt #>> '{arten,0,code}' from " + SCHEMA
                    + ".konfiguration_version where version = 2")).isEqualTo("SEED_A");
            assertThat(wert(befehl, "select jsonb_typeof(inhalt #> '{arten,1,zusatzSchema}') from " + SCHEMA
                    + ".konfiguration_version where version = 2")).isEqualTo("string");
            assertThat(wert(befehl, "select jsonb_typeof(inhalt #> '{arten,0,gruppen}') from " + SCHEMA
                    + ".konfiguration_version where version = 2")).isEqualTo("array");
        }
    }

    @Test
    void changeset039UebernimmtVorhandeneArtenUndFuegtMigrationsabbildAn() throws Exception {
        try (Connection verbindung = verbinden(); Statement befehl = verbindung.createStatement()) {
            befehl.execute("drop schema if exists " + MIGRATION_SCHEMA + " cascade");
            befehl.execute("create schema " + MIGRATION_SCHEMA);
            befehl.execute("set search_path to " + MIGRATION_SCHEMA);
            befehl.execute("""
                    create table dokumentart (
                        code text primary key, name text not null, beschreibung text, gruppen text[],
                        pflichtfelder text[], zusatz_schema jsonb, vertraulichkeit text not null,
                        aufbewahrung_jahre integer, aufbewahrung_ab text,
                        gesetzliche_pflicht boolean not null, aktiv boolean not null)
                    """);
            befehl.execute("""
                    create table konfiguration_version (
                        version integer primary key, wer text not null, grund text, quelle text not null,
                        inhalt jsonb not null, sha256 text not null)
                    """);
            befehl.execute("""
                    insert into dokumentart values ('PROBE_ALT', 'Probe alt', null, '{}', '{}',
                        '{"type":"object"}', 'NORMAL', 10, 'GESCHAEFTSJAHR', false, true)
                    """);
            befehl.execute("""
                    insert into konfiguration_version values (7, 'system:migration', null, 'MIGRATION',
                        '{"version":7,"marker":"vorher"}', 'alter-hash')
                    """);
            liquibase039(verbindung);
            verbindung.commit();

            assertThat(wert(befehl, "select count(*) from konfiguration_version")).isEqualTo("2");
            assertThat(wert(befehl, "select inhalt ->> 'marker' from konfiguration_version where version = 7"))
                    .isEqualTo("vorher");
            assertThat(wert(befehl, "select quelle from konfiguration_version where version = 8"))
                    .isEqualTo("MIGRATION");
            assertThat(wert(befehl, """
                    select inhalt #>> '{arten,0,code}' from konfiguration_version where version = 8
                    """)).isEqualTo("PROBE_ALT");
            assertThat(wert(befehl, """
                    select inhalt #>> '{arten,0,selbstAusgestellt}' from konfiguration_version where version = 8
                    """)).isEqualTo("false");
            assertThat(wert(befehl, """
                    select jsonb_typeof(inhalt #> '{arten,0,belegnummerMuster}')
                    from konfiguration_version where version = 8
                    """)).isEqualTo("array");
            assertThat(wert(befehl, "select name from dokumentart where code = 'PROBE_ALT'")).isEqualTo("Probe alt");
        } finally {
            try (Connection verbindung = verbinden(); Statement befehl = verbindung.createStatement()) {
                befehl.execute("drop schema if exists " + MIGRATION_SCHEMA + " cascade");
            }
        }
    }

    private static void liquibase039(Connection verbindung) throws Exception {
        Database datenbank = DatabaseFactory.getInstance()
                .findCorrectDatabaseImplementation(new JdbcConnection(verbindung));
        datenbank.setDefaultSchemaName(MIGRATION_SCHEMA);
        datenbank.setLiquibaseSchemaName(MIGRATION_SCHEMA);
        ClassLoaderResourceAccessor ressource = new ClassLoaderResourceAccessor(
                KonfigurationSeedVersionTest.class.getClassLoader());
        Scope.child(Map.of(Scope.Attr.resourceAccessor.name(), ressource,
                Scope.Attr.ui.name(), new LoggerUIService()), () ->
                new CommandScope(UpdateCommandStep.COMMAND_NAME)
                        .addArgumentValue(DbUrlConnectionArgumentsCommandStep.DATABASE_ARG, datenbank)
                        .addArgumentValue(UpdateCommandStep.CHANGELOG_FILE_ARG,
                                "db/mandant/changesets/039-dokumentart-konfiguration.xml")
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
            ergebnis.next();
            return ergebnis.getString(1);
        }
    }
}
