package ch.urinet.archiv.konfiguration.adapter.out.persistence;

import ch.urinet.archiv.konfiguration.application.KonfigurationsdateiCodec;
import ch.urinet.archiv.konfiguration.domain.Konfiguration;
import ch.urinet.archiv.mandant.application.KonfigurationSeed;
import ch.urinet.archiv.mandant.domain.MandantCode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spec 05, Req 9.2 (Aufgabe 17.7, Review PR #325, M4): Ein Schema {@code m_urinet} im Stand vor Spec 05 (Changesets bis
 * 036, Arten des alten Seeds ohne die Spalten ab 040) ergibt nach allen Changesets denselben Export wie
 * {@code seed/urinet/konfiguration.json} samt {@code _alle}. Läuft in einer eigenen, kurzlebigen Datenbank, damit das
 * Schema {@code m_urinet} die echten Testdaten nicht berührt.
 *
 * <p>Ausgenommen sind {@code version}/{@code basisVersion} und {@code kiTexte.kiFreigegeben}: Changeset 048 übernimmt
 * für den bestehenden Mandanten {@code urinet} die Freigabe des Gate-0-Prompts ({@code true}); ein neuer Mandant
 * beginnt nach Req 6.6/8.4 ohne Freigabe, die Datei setzt sie nie.
 */
@QuarkusTest
class KonfigurationMigrationSeedTest {

    private static final String ALTER_SEED = "migration/urinet-dokumentarten-vor-spec-05.json";
    private static final Pattern INCLUDE = Pattern.compile("file=\"changesets/([^\"]+)\"");

    @Inject
    KonfigurationsdateiCodec codec;

    @Inject
    KonfigurationSeed seed;

    @Inject
    ObjectMapper json;

    @Test
    void exportNachMigrationEntsprichtDemSeed() throws Exception {
        String name = "probe_konf_seed_" + Long.toHexString(System.nanoTime());
        try (Connection verwaltung = verbinden(null); Statement befehl = verwaltung.createStatement()) {
            befehl.execute("create database " + name);
        }
        try (Connection verbindung = verbinden(name); Statement befehl = verbindung.createStatement()) {
            befehl.execute("create extension if not exists pg_trgm");
            befehl.execute("create extension if not exists vector");
            befehl.execute("""
                    create table public.mandant (code text, schema_name text, eigene_arten text[],
                        geschaeftsjahr_ende text, kennungen jsonb)
                    """);
            befehl.execute("""
                    insert into public.mandant values ('urinet', 'm_urinet', null, '12-31',
                        '{"uid":null,"ibans":[]}')
                    """);
            befehl.execute("create schema m_urinet");
            befehl.execute("set search_path to m_urinet");
            List<String> changesets = changesets();
            int vorSpec05 = changesets.indexOf("037-konfiguration-version.xml");
            assertThat(vorSpec05).isPositive();
            for (String datei : changesets.subList(0, vorSpec05)) {
                liquibase(verbindung, datei);
            }
            alteArtenEinspielen(verbindung);
            verbindung.commit();
            for (String datei : changesets.subList(vorSpec05, changesets.size())) {
                liquibase(verbindung, datei);
            }
            verbindung.commit();

            Konfiguration migriert = new PanacheKonfigurationVersionsRepository(json).lesen(
                    wert(befehl, "select inhalt::text from konfiguration_version order by version desc limit 1"));
            Konfiguration.KiTexte live = new Konfiguration.KiTexte(wert(befehl, "select rolle from ki_texte"),
                    wert(befehl, "select feldregeln from ki_texte"),
                    Boolean.parseBoolean(wert(befehl, "select ki_freigegeben::text from ki_texte")));
            assertThat(live.kiFreigegeben()).as("048 übernimmt die Freigabe von urinet").isTrue();

            JsonNode export = vergleichbar(codec.schreiben(new Konfiguration(migriert.version(),
                    migriert.einstellungen(), migriert.arten(), migriert.klassifikationen(), migriert.regeln(), live)));
            JsonNode erwartet = vergleichbar(codec.schreiben(seed.lesen(new MandantCode("urinet"))));
            assertThat(export).isEqualTo(erwartet);
        } finally {
            try (Connection verwaltung = verbinden(null); Statement befehl = verwaltung.createStatement()) {
                befehl.execute("drop database if exists " + name + " with (force)");
            }
        }
    }

    private JsonNode vergleichbar(String datei) throws IOException {
        ObjectNode baum = (ObjectNode) json.readTree(datei);
        baum.remove(List.of("version", "basisVersion"));
        ((ObjectNode) baum.path("kiTexte")).remove("kiFreigegeben");
        return baum;
    }

    /** Arten des alten Seeds nur mit den Spalten, die vor Changeset 040 bestanden. */
    private void alteArtenEinspielen(Connection verbindung) throws IOException, SQLException {
        JsonNode alt;
        try (InputStream ein = getClass().getClassLoader().getResourceAsStream(ALTER_SEED)) {
            assertThat(ein).as(ALTER_SEED).isNotNull();
            alt = json.readTree(ein);
        }
        try (PreparedStatement einfuegen = verbindung.prepareStatement("""
                insert into dokumentart (code, name, beschreibung, gruppen, pflichtfelder, zusatz_schema,
                    vertraulichkeit, aufbewahrung_jahre, aufbewahrung_ab, gesetzliche_pflicht, aktiv)
                values (?, ?, ?, ?, ?, cast(? as jsonb), ?, ?, ?, ?, ?)
                """)) {
            for (JsonNode art : alt.path("dokumentarten")) {
                einfuegen.setString(1, art.path("code").asText());
                einfuegen.setString(2, art.path("name").asText());
                einfuegen.setString(3, art.path("beschreibung").isNull() ? null : art.path("beschreibung").asText(null));
                einfuegen.setArray(4, verbindung.createArrayOf("text", texte(art.path("gruppen"))));
                einfuegen.setArray(5, verbindung.createArrayOf("text", texte(art.path("pflichtfelder"))));
                einfuegen.setString(6, art.path("zusatz_schema").isMissingNode() || art.path("zusatz_schema").isNull()
                        ? null : json.writeValueAsString(art.path("zusatz_schema")));
                einfuegen.setString(7, art.path("vertraulichkeit").asText());
                if (art.path("aufbewahrung_jahre").isNumber()) {
                    einfuegen.setInt(8, art.path("aufbewahrung_jahre").asInt());
                } else {
                    einfuegen.setNull(8, java.sql.Types.INTEGER);
                }
                einfuegen.setString(9, art.path("aufbewahrung_ab").asText(null));
                einfuegen.setBoolean(10, art.path("gesetzliche_pflicht").asBoolean());
                einfuegen.setBoolean(11, art.path("aktiv").asBoolean(true));
                einfuegen.addBatch();
            }
            einfuegen.executeBatch();
        }
    }

    private static String[] texte(JsonNode liste) {
        List<String> werte = new ArrayList<>();
        liste.forEach(wert -> werte.add(wert.asText()));
        return werte.toArray(String[]::new);
    }

    private static List<String> changesets() throws IOException {
        String changelog;
        try (InputStream ein = KonfigurationMigrationSeedTest.class.getClassLoader()
                .getResourceAsStream("db/mandant/changelog.xml")) {
            changelog = new String(ein.readAllBytes(), StandardCharsets.UTF_8);
        }
        List<String> dateien = new ArrayList<>();
        Matcher treffer = INCLUDE.matcher(changelog);
        while (treffer.find()) {
            dateien.add(treffer.group(1));
        }
        return dateien;
    }

    private static void liquibase(Connection verbindung, String datei) throws Exception {
        Database datenbank = DatabaseFactory.getInstance()
                .findCorrectDatabaseImplementation(new JdbcConnection(verbindung));
        datenbank.setDefaultSchemaName("m_urinet");
        datenbank.setLiquibaseSchemaName("m_urinet");
        ClassLoaderResourceAccessor ressource = new ClassLoaderResourceAccessor(
                KonfigurationMigrationSeedTest.class.getClassLoader());
        Scope.child(Map.of(Scope.Attr.resourceAccessor.name(), ressource,
                Scope.Attr.ui.name(), new LoggerUIService()), () ->
                new CommandScope(UpdateCommandStep.COMMAND_NAME)
                        .addArgumentValue(DbUrlConnectionArgumentsCommandStep.DATABASE_ARG, datenbank)
                        .addArgumentValue(UpdateCommandStep.CHANGELOG_FILE_ARG, "db/mandant/changesets/" + datei)
                        .execute());
    }

    private static Connection verbinden(String datenbankName) throws SQLException {
        var config = ConfigProvider.getConfig();
        String url = config.getValue("quarkus.datasource.jdbc.url", String.class);
        if (datenbankName != null) {
            url = url.replaceFirst("/[^/?]+(\\?|$)", "/" + datenbankName + "$1");
        }
        return DriverManager.getConnection(url,
                config.getValue("quarkus.datasource.username", String.class),
                config.getValue("quarkus.datasource.password", String.class));
    }

    private static String wert(Statement befehl, String sql) throws SQLException {
        try (ResultSet ergebnis = befehl.executeQuery(sql)) {
            assertThat(ergebnis.next()).as(sql).isTrue();
            return ergebnis.getString(1);
        }
    }
}
