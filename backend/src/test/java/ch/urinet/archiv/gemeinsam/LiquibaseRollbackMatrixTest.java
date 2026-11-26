package ch.urinet.archiv.gemeinsam;

import io.quarkus.test.junit.QuarkusTest;
import liquibase.Liquibase;
import liquibase.Scope;
import liquibase.changelog.ChangeSet;
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
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.net.URI;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Manuelle Bestandsaufnahme in eigenen Wegwerfdatenbanken des lokalen Test-Dev-Service.
 * Die Tabelle meldet auch fehlende Rollbacks; sie behauptet keine allgemeine Datenverlustfreiheit.
 */
@QuarkusTest
@EnabledIfSystemProperty(named = "archiv.liquibase.matrix", matches = "true")
class LiquibaseRollbackMatrixTest {

    private static final String SCHEMA = "m_matrix";
    private static final String PRAEFIX = "qa_lb_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
    private static final ClassLoaderResourceAccessor RESSOURCEN =
            new ClassLoaderResourceAccessor(LiquibaseRollbackMatrixTest.class.getClassLoader());

    @Test
    void jedesChangesetAufLeeremUndGefuelltemSchemaPruefen() throws Exception {
        var config = ConfigProvider.getConfig();
        String jdbc = config.getValue("quarkus.datasource.jdbc.url", String.class);
        URI adresse = URI.create(jdbc.substring("jdbc:".length()));
        assertThat(adresse.getScheme()).isEqualTo("postgresql");
        assertThat(adresse.getHost()).isIn("localhost", "127.0.0.1");
        assertThat(adresse.getPort()).isPositive();
        String benutzer = config.getValue("quarkus.datasource.username", String.class);
        String passwort = config.getValue("quarkus.datasource.password", String.class);
        var verbindungen = new Verbindungen(adresse.getHost(), adresse.getPort(), benutzer, passwort);
        List<String> bericht = new ArrayList<>();
        try (Connection admin = DriverManager.getConnection(jdbc, benutzer, passwort)) {
            for (String scope : List.of("public", "mandant")) {
                pruefen(admin, verbindungen, scope, bericht);
            }
        } finally {
            bericht.forEach(System.out::println);
        }
    }

    private static void pruefen(Connection admin, Verbindungen verbindungen, String scope, List<String> bericht)
            throws Exception {
        String basis = PRAEFIX + "_basis";
        String kopie = PRAEFIX + "_kopie";
        String schema = scope.equals("public") ? "public" : SCHEMA;
        String changelog = "db/" + scope + "/changelog.xml";
        erstellen(admin, basis, null);
        try {
            if (scope.equals("mandant")) {
                aendern(verbindungen, basis, "public", "db/public/changelog.xml", "update", 0);
                try (Connection verbindung = verbindungen.verbinden(basis); Statement sql = verbindung.createStatement()) {
                    sql.execute("create schema " + SCHEMA);
                }
            }
            List<ChangeSet> alle;
            try (Connection verbindung = verbindungen.verbinden(basis)) {
                Database datenbank = datenbank(verbindung, schema);
                alle = new Liquibase(changelog, RESSOURCEN, datenbank).getDatabaseChangeLog().getChangeSets();
            }
            assertThat(alle.size()).isGreaterThanOrEqualTo(20);
            Set<String> bearbeitet = new HashSet<>();
            bericht.add("| Scope | Changeset | Rollback | Zustand | Reapply Schema | Daten |");
            bericht.add("|---|---|---|---|---|---|");
            for (int versuch = 0; versuch < alle.size(); versuch++) {
                List<String> vorherIds = ausgefuehrteIds(verbindungen, basis, schema);
                List<String> vorherSchema = schema(verbindungen, basis, schema);
                aendern(verbindungen, basis, schema, changelog, "updateCount", 1);
                List<String> nachherIds = ausgefuehrteIds(verbindungen, basis, schema);
                if (nachherIds.size() == vorherIds.size()) {
                    break;
                }
                assertThat(nachherIds).hasSize(vorherIds.size() + 1);
                String id = nachherIds.getLast();
                int index = indexVon(alle, id);
                assertThat(bearbeitet.add(id)).isTrue();
                List<String> nachherSchema = schema(verbindungen, basis, schema);
                for (boolean gefuellt : List.of(false, true)) {
                    erstellen(admin, kopie, basis);
                    try {
                        if (gefuellt) {
                            fuellen(verbindungen, kopie, scope, id);
                        }
                        zeile(verbindungen, kopie, scope, schema, changelog, alle.get(index), vorherSchema,
                                nachherSchema, gefuellt, bericht);
                    } finally {
                        entfernen(admin, kopie);
                    }
                }
            }
            for (int i = 0; i < alle.size(); i++) {
                if (!bearbeitet.contains(alle.get(i).getId())) {
                    bericht.add("| " + scope + " | " + alle.get(i).getId()
                            + " | nicht ausgeführt | beide | nicht geprüft | nicht geprüft |");
                }
            }
        } finally {
            entfernen(admin, basis);
        }
    }

    private static void zeile(Verbindungen verbindungen, String db, String scope, String schema, String changelog,
                              ChangeSet changeset, List<String> vorherSchema, List<String> nachherSchema,
                              boolean gefuellt, List<String> bericht) throws Exception {
        String zustand = gefuellt ? "gefüllt" : "leer";
        boolean rollbackMoeglich;
        try (Connection verbindung = verbindungen.verbinden(db)) {
            rollbackMoeglich = changeset.supportsRollback(datenbank(verbindung, schema));
        }
        if (!rollbackMoeglich) {
            bericht.add("| " + scope + " | " + changeset.getId()
                    + " | nicht unterstützt | " + zustand + " | nicht geprüft | nicht geprüft |");
            return;
        }
        List<String> datenVorher = daten(verbindungen, db, schema);
        List<String> idsVorher = ausgefuehrteIds(verbindungen, db, schema);
        assertThat(idsVorher.getLast()).isEqualTo(changeset.getId());
        try {
            aendern(verbindungen, db, schema, changelog, "rollbackCount", 1);
        } catch (Exception fehler) {
            bericht.add("| " + scope + " | " + changeset.getId() + " | fehlgeschlagen: "
                    + fehler.getClass().getSimpleName() + " | " + zustand + " | nicht geprüft | nicht geprüft |");
            return;
        }
        assertThat(ausgefuehrteIds(verbindungen, db, schema))
                .isEqualTo(idsVorher.subList(0, idsVorher.size() - 1));
        boolean rueckbau = schema(verbindungen, db, schema).equals(vorherSchema);
        try {
            aendern(verbindungen, db, schema, changelog, "updateCount", 1);
        } catch (Exception fehler) {
            bericht.add("| " + scope + " | " + changeset.getId() + " | "
                    + (rueckbau ? "vorheriger Stand" : "abweichend") + " | " + zustand
                    + " | fehlgeschlagen: " + fehler.getClass().getSimpleName() + " | nicht geprüft |");
            return;
        }
        assertThat(ausgefuehrteIds(verbindungen, db, schema)).isEqualTo(idsVorher);
        boolean schemaGleich = schema(verbindungen, db, schema).equals(nachherSchema);
        List<String> datenNachher = daten(verbindungen, db, schema);
        Set<String> abweichendeTabellen = abweichendeTabellen(datenVorher, datenNachher);
        bericht.add("| " + scope + " | " + changeset.getId() + " | "
                + (rueckbau ? "vorheriger Stand" : "abweichend") + " | " + zustand + " | "
                + (schemaGleich ? "gleich" : "abweichend") + " | "
                + (abweichendeTabellen.isEmpty() ? "gleich" : "abweichend: " + String.join(", ", abweichendeTabellen))
                + " |");
    }

    private static Set<String> abweichendeTabellen(List<String> vorher, List<String> nachher) {
        Map<String, Integer> mengen = new HashMap<>();
        vorher.forEach(zeile -> mengen.merge(zeile, 1, Integer::sum));
        nachher.forEach(zeile -> mengen.merge(zeile, -1, Integer::sum));
        Set<String> tabellen = new TreeSet<>();
        mengen.forEach((zeile, differenz) -> {
            if (differenz != 0) {
                tabellen.add(zeile.substring(0, zeile.indexOf(':')));
            }
        });
        return tabellen;
    }

    private static int indexVon(List<ChangeSet> alle, String id) {
        for (int i = 0; i < alle.size(); i++) {
            if (alle.get(i).getId().equals(id)) {
                return i;
            }
        }
        throw new IllegalStateException("Unbekanntes Changeset: " + id);
    }

    private static void aendern(Verbindungen verbindungen, String db, String schema, String changelog,
                               String kommando, int anzahl) throws Exception {
        try (Connection verbindung = verbindungen.verbinden(db)) {
            try (Statement sql = verbindung.createStatement()) {
                sql.execute("set search_path to " + schema + ", public");
            }
            Database datenbank = datenbank(verbindung, schema);
            Scope.child(Map.of(Scope.Attr.resourceAccessor.name(), RESSOURCEN,
                    Scope.Attr.ui.name(), new LoggerUIService()), () -> {
                CommandScope befehl = new CommandScope(kommando)
                        .addArgumentValue(DbUrlConnectionArgumentsCommandStep.DATABASE_ARG, datenbank)
                        .addArgumentValue(UpdateCommandStep.CHANGELOG_FILE_ARG, changelog);
                if (anzahl > 0) {
                    befehl.addArgumentValue("count", anzahl);
                }
                befehl.execute();
            });
        }
    }

    private static Database datenbank(Connection verbindung, String schema) throws Exception {
        Database datenbank = DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(verbindung));
        datenbank.setDefaultSchemaName(schema);
        datenbank.setLiquibaseSchemaName(schema);
        return datenbank;
    }

    private static void erstellen(Connection admin, String name, String vorlage) throws SQLException {
        try (Statement sql = admin.createStatement()) {
            sql.execute("create database " + name + (vorlage == null ? "" : " template " + vorlage));
        }
    }

    private static void entfernen(Connection admin, String name) throws SQLException {
        assertThat(name).startsWith(PRAEFIX);
        try (Statement sql = admin.createStatement()) {
            sql.execute("drop database " + name);
        }
    }

    private static List<String> ausgefuehrteIds(Verbindungen verbindungen, String db, String schema) throws SQLException {
        try (Connection verbindung = verbindungen.verbinden(db); Statement sql = verbindung.createStatement()) {
            try (ResultSet existiert = sql.executeQuery("select to_regclass('" + schema + ".databasechangelog')")) {
                existiert.next();
                if (existiert.getString(1) == null) {
                    return List.of();
                }
            }
            try (ResultSet zeilen = sql.executeQuery(
                    "select id from " + schema + ".databasechangelog order by orderexecuted")) {
                List<String> ids = new ArrayList<>();
                while (zeilen.next()) {
                    ids.add(zeilen.getString(1));
                }
                return ids;
            }
        }
    }

    private static void fuellen(Verbindungen verbindungen, String db, String scope, String id) throws SQLException {
        try (Connection verbindung = verbindungen.verbinden(db); Statement sql = verbindung.createStatement()) {
            if (scope.equals("public")) {
                sql.execute("insert into public.mandant (code, name, schema_name, bucket) "
                        + "values ('matrix', 'Test', 'm_matrix', 'matrix')");
                if (id.equals("010-webseiten-schluessel")) {
                    sql.execute("insert into public.api_client "
                            + "(id, mandant, name, scope, geheimnis_hash, webseite, hoechste_stufe) "
                            + "values ('matrix', 'matrix', 'Test', 'webseite-lesen', 'synthetisch', 'test', 'oeffentlich')");
                }
                if (existiert(sql, "public.mandant_budget")) {
                    sql.execute("insert into public.mandant_budget (mandant, monat, tokens_max) "
                            + "values ('matrix', '2020-01-01', 100)");
                    if (spalteExistiert(sql, "public", "mandant_budget", "frage_laufend")) {
                        sql.execute("update public.mandant_budget set frage_laufend = true");
                    }
                    if (spalteExistiert(sql, "public", "mandant_budget", "laufend_seit")) {
                        sql.execute("update public.mandant_budget set laufend_seit = '2020-01-01T00:00:00Z'");
                    }
                }
                if (existiert(sql, "public.token_widerruf")) {
                    sql.execute("insert into public.token_widerruf (benutzer, ab) "
                            + "values ('synthetisch', '2020-01-01T00:00:00Z')");
                }
            } else {
                sql.execute("insert into " + SCHEMA + ".dokumentart (code, name, vertraulichkeit, gesetzliche_pflicht) "
                        + "values ('MATRIX', 'Test', 'NORMAL', false)");
                if (existiert(sql, SCHEMA + ".chat_gespraech")) {
                    sql.execute("insert into " + SCHEMA + ".chat_gespraech (id, benutzer, titel, erstellt, geaendert) "
                            + "values ('00000000-0000-0000-0000-000000000001', 'synthetisch', 'Test', "
                            + "'2020-01-01T00:00:00Z', '2020-01-01T00:00:00Z')");
                    sql.execute("insert into " + SCHEMA + ".chat_nachricht (id, gespraech_id, rolle, inhalt, zeit) "
                            + "values (9001, '00000000-0000-0000-0000-000000000001', 'BENUTZER', 'Test', "
                            + "'2020-01-01T00:00:00Z')");
                    if (spalteExistiert(sql, SCHEMA, "chat_nachricht", "modellkennung")) {
                        sql.execute("update " + SCHEMA + ".chat_nachricht set modellkennung = 'synthetisch', "
                                + "systemprompt_version = 'probe', zeit_bis_erster_text_ms = 42");
                    }
                }
                if (existiert(sql, SCHEMA + ".chat_bewertung_versuch")) {
                    sql.execute("insert into " + SCHEMA + ".chat_bewertung_versuch (benutzer, zeit) "
                            + "values ('synthetisch', '2020-01-01T00:00:00Z')");
                }
                if (existiert(sql, SCHEMA + ".benutzer_einladung")) {
                    sql.execute("insert into " + SCHEMA + ".benutzer_einladung (benutzer, versendet_am, gueltig_bis) "
                            + "values ('synthetisch', '2020-01-01T00:00:00Z', '2020-01-02T00:00:00Z')");
                }
            }
        }
    }

    private static boolean existiert(Statement sql, String name) throws SQLException {
        try (ResultSet ergebnis = sql.executeQuery("select to_regclass('" + name + "') is not null")) {
            ergebnis.next();
            return ergebnis.getBoolean(1);
        }
    }

    private static boolean spalteExistiert(Statement sql, String schema, String tabelle, String spalte)
            throws SQLException {
        try (ResultSet ergebnis = sql.executeQuery("select exists (select 1 from information_schema.columns "
                + "where table_schema = '" + schema + "' and table_name = '" + tabelle
                + "' and column_name = '" + spalte + "')")) {
            ergebnis.next();
            return ergebnis.getBoolean(1);
        }
    }

    private static List<String> schema(Verbindungen verbindungen, String db, String schema) throws SQLException {
        String abfrage = """
                select art || ':' || name || ':' || definition from (
                    select 'relation' art, c.relname name,
                        c.relkind::text || ':' || coalesce(c.relacl::text, '') || ':' || c.relrowsecurity::text
                        || ':' || c.relforcerowsecurity::text || ':' || pg_get_userbyid(c.relowner)
                        || ':' || coalesce(c.reloptions::text, '') definition
                    from pg_class c join pg_namespace n on n.oid = c.relnamespace
                    where n.nspname = '%s' and c.relkind in ('r', 'p', 'S', 'v', 'm')
                        and c.relname not in ('databasechangelog', 'databasechangeloglock')
                    union all
                    select 'column', c.relname || '.' || a.attname,
                        a.attnum::text || ':' || format_type(a.atttypid, a.atttypmod) || ':' || a.attnotnull::text || ':'
                        || coalesce(pg_get_expr(d.adbin, d.adrelid), '') || ':' || a.attgenerated::text
                        || ':' || a.attidentity::text || ':' || a.attcollation::regcollation::text
                    from pg_attribute a join pg_class c on c.oid = a.attrelid
                    join pg_namespace n on n.oid = c.relnamespace
                    left join pg_attrdef d on d.adrelid = a.attrelid and d.adnum = a.attnum
                    where n.nspname = '%s' and a.attnum > 0 and not a.attisdropped
                        and c.relname not in ('databasechangelog', 'databasechangeloglock')
                    union all
                    select 'constraint', c.relname || '.' || k.conname, pg_get_constraintdef(k.oid)
                    from pg_constraint k join pg_class c on c.oid = k.conrelid
                    join pg_namespace n on n.oid = c.relnamespace
                    where n.nspname = '%s' and c.relname not in ('databasechangelog', 'databasechangeloglock')
                    union all
                    select 'index', i.indexname, i.indexdef from pg_indexes i
                    where i.schemaname = '%s' and i.tablename not in ('databasechangelog', 'databasechangeloglock')
                    union all
                    select 'view', c.relname, pg_get_viewdef(c.oid)
                    from pg_class c join pg_namespace n on n.oid = c.relnamespace
                    where n.nspname = '%s' and c.relkind in ('v', 'm')
                    union all
                    select 'sequence', c.relname, s.seqstart::text || ':' || s.seqincrement::text || ':'
                        || s.seqmax::text || ':' || s.seqmin::text || ':' || s.seqcache::text || ':' || s.seqcycle::text
                    from pg_sequence s join pg_class c on c.oid = s.seqrelid
                    join pg_namespace n on n.oid = c.relnamespace
                    where n.nspname = '%s'
                    union all
                    select 'function', p.proname || pg_get_function_identity_arguments(p.oid),
                        pg_get_functiondef(p.oid) || ':' || coalesce(p.proacl::text, '')
                    from pg_proc p join pg_namespace n on n.oid = p.pronamespace
                    where n.nspname = '%s' and p.prokind = 'f' and not exists
                        (select 1 from pg_depend d where d.objid = p.oid and d.deptype = 'e')
                    union all
                    select 'trigger', c.relname || '.' || t.tgname, pg_get_triggerdef(t.oid)
                    from pg_trigger t join pg_class c on c.oid = t.tgrelid
                    join pg_namespace n on n.oid = c.relnamespace
                    where n.nspname = '%s' and not t.tgisinternal
                ) objekte order by 1
                """.formatted(schema, schema, schema, schema, schema, schema, schema, schema);
        return liste(verbindungen, db, abfrage);
    }

    private static List<String> daten(Verbindungen verbindungen, String db, String schema) throws SQLException {
        List<String> daten = new ArrayList<>();
        try (Connection verbindung = verbindungen.verbinden(db); Statement sql = verbindung.createStatement()) {
            List<String> tabellen = new ArrayList<>();
            try (ResultSet zeilen = sql.executeQuery("select tablename from pg_tables where schemaname = '" + schema
                    + "' and tablename not in ('databasechangelog', 'databasechangeloglock') order by tablename")) {
                while (zeilen.next()) {
                    tabellen.add(zeilen.getString(1));
                }
            }
            for (String tabelle : tabellen) {
                try (ResultSet zeilen = sql.executeQuery("select to_jsonb(t)::text from " + schema + ".\""
                        + tabelle.replace("\"", "\"\"") + "\" t order by 1")) {
                    while (zeilen.next()) {
                        daten.add(tabelle + ":" + zeilen.getString(1));
                    }
                }
            }
        }
        return daten;
    }

    private static List<String> liste(Verbindungen verbindungen, String db, String abfrage) throws SQLException {
        try (Connection verbindung = verbindungen.verbinden(db); Statement sql = verbindung.createStatement();
             ResultSet zeilen = sql.executeQuery(abfrage)) {
            List<String> werte = new ArrayList<>();
            while (zeilen.next()) {
                werte.add(zeilen.getString(1));
            }
            return werte;
        }
    }

    private record Verbindungen(String host, int port, String benutzer, String passwort) {
        Connection verbinden(String datenbank) throws SQLException {
            return DriverManager.getConnection("jdbc:postgresql://" + host + ":" + port + "/" + datenbank,
                    benutzer, passwort);
        }
    }
}
