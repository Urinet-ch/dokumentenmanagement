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

    private static final List<String> SCHEMAS = List.of("m_probe_konf037_a", "m_probe_konf037_b");
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
        for (String schema : SCHEMAS) {
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
        for (String schema : SCHEMAS) {
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

    private static void migrieren(Connection verbindung, String schema, int anzahl) throws Exception {
        var datenbank = DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(verbindung));
        datenbank.setDefaultSchemaName(schema);
        datenbank.setLiquibaseSchemaName(schema);
        Scope.child(Map.of(
                Scope.Attr.resourceAccessor.name(), new ClassLoaderResourceAccessor(
                        KonfigurationMigrationTest.class.getClassLoader()),
                Scope.Attr.ui.name(), new LoggerUIService()), () ->
                new CommandScope("updateCount")
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
