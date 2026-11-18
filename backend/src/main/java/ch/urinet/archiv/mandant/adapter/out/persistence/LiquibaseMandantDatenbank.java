package ch.urinet.archiv.mandant.adapter.out.persistence;

import ch.urinet.archiv.mandant.application.MandantDatenbank;
import ch.urinet.archiv.mandant.domain.DokumentartVorlage;
import jakarta.enterprise.context.ApplicationScoped;
import liquibase.Scope;
import liquibase.command.CommandScope;
import liquibase.command.core.UpdateCommandStep;
import liquibase.command.core.helpers.DbUrlConnectionArgumentsCommandStep;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import liquibase.ui.LoggerUIService;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Schema je Mandant über Liquibase {@code db/mandant} (eigene Changelog-Tabelle im Schema, ADR-002) und Rechte der
 * App-Rolle: lesen und schreiben, aber keine Tabellen anlegen oder ändern (Req 1.7).
 */
@ApplicationScoped
public class LiquibaseMandantDatenbank implements MandantDatenbank {

    static final String CHANGELOG = "db/mandant/changelog.xml";
    static final String REGELN_SEED = "db/mandant/seed/regel-urinet.sql";
    // Namen gehen als Bezeichner in SQL ein; nur dieses Muster ist erlaubt
    private static final Pattern SCHEMA = Pattern.compile("^m_[a-z0-9_]+$");
    private static final Pattern ROLLE = Pattern.compile("^[a-z_][a-z0-9_]*$");
    /**
     * Tabellen, in die die App-Rolle nur einfügt und aus denen sie nur liest (Req 10.3, 10.10). Zusätzlich verhindert je
     * ein Trigger UPDATE, DELETE und TRUNCATE, auch für den Eigentümer.
     */
    static final List<String> NUR_EINFUEGEN = List.of("zugriff_protokoll", "dokument_version", "benutzer_verzeichnis",
            "manifest", "manifest_zeitstempel", "protokoll_blatt", "konfiguration_version", "wissensseite_version",
            "wissensseite_version_dokument", "wissens_log");

    private final EigentuemerVerbindung eigentuemer;
    private final String appRolle;

    public LiquibaseMandantDatenbank(EigentuemerVerbindung eigentuemer,
                                     @ConfigProperty(name = "archiv.db.app-rolle") String appRolle) {
        if (!ROLLE.matcher(appRolle).matches()) {
            throw new IllegalArgumentException("Ungültige App-Rolle: " + appRolle);
        }
        this.eigentuemer = eigentuemer;
        this.appRolle = appRolle;
    }

    @Override
    public void schemaAnlegen(String schema) {
        einrichten(schema, true);
    }

    @Override
    public void schemaMigrieren(String schema) {
        einrichten(schema, false);
    }

    @Override
    public boolean schemaVorhanden(String schema) {
        pruefen(schema);
        try (Connection verbindung = eigentuemer.oeffnen();
             PreparedStatement abfrage = verbindung.prepareStatement("select 1 from pg_namespace where nspname = ?")) {
            abfrage.setString(1, schema);
            return abfrage.executeQuery().next();
        } catch (SQLException e) {
            throw new IllegalStateException("Schema " + schema + " nicht prüfbar", e);
        }
    }

    @Override
    public void schemaEntfernen(String schema) {
        pruefen(schema);
        try (Connection verbindung = eigentuemer.oeffnen(); Statement befehl = verbindung.createStatement()) {
            befehl.execute("drop schema if exists " + schema + " cascade");
        } catch (SQLException e) {
            throw new IllegalStateException("Mandanten-Schema " + schema + " nicht entfernt", e);
        }
    }

    private void einrichten(String schema, boolean anlegen) {
        pruefen(schema);
        try (Connection verbindung = eigentuemer.oeffnen(); Statement befehl = verbindung.createStatement()) {
            if (anlegen) {
                befehl.execute("create schema if not exists " + schema);
            }
            befehl.execute("set search_path to " + schema);
            try {
                migrieren(verbindung, schema);
                // Liquibase lässt autoCommit=false stehen; das schreibt die Migration fest
                verbindung.setAutoCommit(true);
                berechtigenAtomar(verbindung, befehl, schema);
            } finally {
                befehl.execute("reset search_path");
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Schema " + schema + " nicht eingerichtet", e);
        }
    }

    @Override
    public void dokumentartenEinspielen(String schema, List<DokumentartVorlage> arten) {
        pruefen(schema);
        String sql = "insert into " + schema + ".dokumentart (code, name, gruppen, pflichtfelder, zusatz_schema, "
                + "vertraulichkeit, aufbewahrung_jahre, aufbewahrung_ab, gesetzliche_pflicht, aktiv, beschreibung, "
                + "selbst_ausgestellt, belegnummer_muster, fremdbeleg_art, ohne_zahlungsdaten_art, ki_vorschlag, "
                + "ki_felder, ki_hinweise, reihenfolge, aufbewahrung_feld) values (?, ?, ?, ?, cast(? as jsonb), ?, ?, ?, ?, ?, ?, ?, "
                + "?, null, null, ?, ?, ?, ?, ?) on conflict (code) do nothing returning code";
        String referenzenAktualisieren = "update " + schema
                + ".dokumentart set fremdbeleg_art = ?, ohne_zahlungsdaten_art = ? where code = ?";
        try (Connection verbindung = eigentuemer.oeffnen(); PreparedStatement einfuegen = verbindung.prepareStatement(sql)) {
            verbindung.setAutoCommit(false);
            try (PreparedStatement referenzen = verbindung.prepareStatement(referenzenAktualisieren)) {
                for (DokumentartVorlage art : arten) {
                    einfuegen.setString(1, art.code());
                    einfuegen.setString(2, art.name());
                    einfuegen.setArray(3, verbindung.createArrayOf("text", art.gruppen().toArray()));
                    einfuegen.setArray(4, verbindung.createArrayOf("text", art.pflichtfelder().toArray()));
                    einfuegen.setString(5, art.zusatzSchemaJson());
                    einfuegen.setString(6, art.vertraulichkeit());
                    if (art.aufbewahrungJahre() == null) {
                        einfuegen.setNull(7, Types.INTEGER);
                    } else {
                        einfuegen.setInt(7, art.aufbewahrungJahre());
                    }
                    einfuegen.setString(8, art.aufbewahrungAb());
                    einfuegen.setBoolean(9, art.gesetzlichePflicht());
                    einfuegen.setBoolean(10, art.aktiv());
                    einfuegen.setString(11, art.beschreibung());
                    einfuegen.setBoolean(12, art.selbstAusgestellt());
                    einfuegen.setArray(13, verbindung.createArrayOf("text", art.belegnummerMuster().toArray()));
                    einfuegen.setBoolean(14, art.kiVorschlag());
                    einfuegen.setArray(15, verbindung.createArrayOf("text", art.kiFelder().toArray()));
                    einfuegen.setString(16, art.kiHinweise());
                    einfuegen.setInt(17, art.reihenfolge());
                    einfuegen.setString(18, art.aufbewahrungFeld());
                    try (ResultSet eingefuegt = einfuegen.executeQuery()) {
                        if (eingefuegt.next()) {
                            referenzen.setString(1, art.fremdbelegArt());
                            referenzen.setString(2, art.ohneZahlungsdatenArt());
                            referenzen.setString(3, art.code());
                            referenzen.addBatch();
                        }
                    }
                }
                referenzen.executeBatch();
            } catch (SQLException e) {
                try {
                    verbindung.rollback();
                } catch (SQLException rollbackFehler) {
                    e.addSuppressed(rollbackFehler);
                }
                throw e;
            }
            verbindung.commit();
        } catch (SQLException e) {
            throw new IllegalStateException("Dokumentarten für " + schema + " nicht eingespielt", e);
        }
    }

    /**
     * Gleicher Aufbau wie das Abbild in Changeset {@code 037-konfiguration-version}; die Nummer ist die nächste freie,
     * und es entsteht nur eine Version, wenn sich das Abbild (ohne Nummer) von der letzten unterscheidet.
     */
    static final String SEED_VERSION = """
            with naechste as (select coalesce(max(version), 0) + 1 as nummer from konfiguration_version),
            abbild as (
                select n.nummer, jsonb_build_object(
                    'version', n.nummer,
                    'einstellungen', jsonb_build_object(
                        'geschaeftsjahrEnde', coalesce(m.geschaeftsjahr_ende, '12-31'),
                        'kennungen', jsonb_build_object(
                            'uid', case when jsonb_typeof(m.kennungen -> 'uid') = 'string'
                                and m.kennungen ->> 'uid' <> '' then m.kennungen -> 'uid' end,
                            'ibans', case when jsonb_typeof(m.kennungen -> 'ibans') = 'array'
                                then m.kennungen -> 'ibans' else '[]'::jsonb end)),
                    'arten', coalesce((
                        select jsonb_agg(jsonb_build_object(
                            'code', a.code,
                            'name', a.name,
                            'beschreibung', a.beschreibung,
                            'gruppen', to_jsonb(coalesce(a.gruppen, '{}'::text[])),
                            'pflichtfelder', to_jsonb(coalesce(a.pflichtfelder, '{}'::text[])),
                            'zusatzSchema', to_jsonb(coalesce(a.zusatz_schema::text,
                                '{"type":"object","properties":{},"additionalProperties":false}')),
                            'vertraulichkeit', a.vertraulichkeit,
                            'aufbewahrung', jsonb_build_object(
                                'jahre', a.aufbewahrung_jahre,
                                'ab', a.aufbewahrung_ab)
                                || jsonb_strip_nulls(jsonb_build_object('feld', a.aufbewahrung_feld)),
                            'gesetzlichePflicht', a.gesetzliche_pflicht,
                            'aktiv', a.aktiv,
                            'selbstAusgestellt', a.selbst_ausgestellt,
                            'belegnummerMuster', to_jsonb(coalesce(a.belegnummer_muster, '{}'::text[])),
                            'fremdbelegArt', a.fremdbeleg_art,
                            'ohneZahlungsdatenArt', a.ohne_zahlungsdaten_art,
                            'kiVorschlag', a.ki_vorschlag,
                            'kiFelder', to_jsonb(coalesce(a.ki_felder, '{}'::text[])),
                            'kiHinweise', a.ki_hinweise,
                            'reihenfolge', a.reihenfolge)
                            order by a.code collate "C")
                        from dokumentart a), '[]'::jsonb),
                    'regeln', coalesce((
                        select jsonb_agg(jsonb_build_object(
                            'code', r.code,
                            'art', r.art,
                            'typ', r.typ,
                            'parameter', r.parameter,
                            'schwere', r.schwere,
                            'meldung', r.meldung,
                            'aktiv', r.aktiv)
                            order by r.code collate "C")
                        from regel r), '[]'::jsonb),
                    'kiTexte', (
                        select jsonb_build_object(
                            'rolle', t.rolle,
                            'feldregeln', t.feldregeln,
                            'kiFreigegeben', t.ki_freigegeben)
                        from ki_texte t where t.id = 1)
                ) as inhalt
                from naechste n, (select current_schema() as schema_name) s
                left join public.mandant m on m.schema_name = s.schema_name
            )
            insert into konfiguration_version (version, wer, grund, quelle, inhalt, sha256)
            select nummer, 'system:seed', null, 'SEED', inhalt,
                encode(sha256(convert_to(inhalt::text, 'UTF8')), 'hex')
            from abbild
            where (inhalt - 'version') is distinct from (
                select inhalt - 'version' from konfiguration_version order by version desc limit 1)
            """;

    @Override
    public void regelnEinspielen(String schema) {
        pruefen(schema);
        String sql;
        try (java.io.InputStream ein = LiquibaseMandantDatenbank.class.getClassLoader().getResourceAsStream(REGELN_SEED)) {
            if (ein == null) {
                throw new IllegalStateException("Seed der Regeln fehlt: " + REGELN_SEED);
            }
            sql = new String(ein.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Seed der Regeln nicht lesbar", e);
        }
        try (Connection verbindung = eigentuemer.oeffnen(); Statement befehl = verbindung.createStatement()) {
            verbindung.setAutoCommit(true);
            befehl.execute("set search_path to " + schema);
            try {
                befehl.execute(sql);
            } finally {
                befehl.execute("reset search_path");
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Regeln für " + schema + " nicht eingespielt", e);
        }
    }

    @Override
    public void konfigurationSeedAnlegen(String schema) {
        pruefen(schema);
        try (Connection verbindung = eigentuemer.oeffnen(); Statement befehl = verbindung.createStatement()) {
            verbindung.setAutoCommit(true);
            befehl.execute("set search_path to " + schema);
            try {
                befehl.execute(SEED_VERSION);
            } finally {
                befehl.execute("reset search_path");
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Konfigurationsversion für " + schema + " nicht angelegt", e);
        }
    }

    @Override
    public void gemeinsamesSchemaBerechtigen() {
        try (Connection verbindung = eigentuemer.oeffnen(); Statement befehl = verbindung.createStatement()) {
            verbindung.setAutoCommit(true);
            if (!rolleVorhanden(verbindung)) {
                return;
            }
            befehl.execute("grant usage on schema public to " + appRolle);
            befehl.execute("grant select, insert, update, delete on public.mandant, public.auftrag, public.api_client to "
                    + appRolle);
            befehl.execute("grant usage, select on all sequences in schema public to " + appRolle);
        } catch (SQLException e) {
            throw new IllegalStateException("Rechte im Schema public nicht vergeben", e);
        }
    }

    private void migrieren(Connection verbindung, String schema) {
        try {
            Database datenbank = DatabaseFactory.getInstance()
                    .findCorrectDatabaseImplementation(new JdbcConnection(verbindung));
            datenbank.setDefaultSchemaName(schema);
            datenbank.setLiquibaseSchemaName(schema);
            // Meldungen wie «UPDATE SUMMARY» ins Log statt auf stdout
            Scope.child(Map.of(Scope.Attr.resourceAccessor.name(),
                    new ClassLoaderResourceAccessor(LiquibaseMandantDatenbank.class.getClassLoader()),
                    Scope.Attr.ui.name(), new LoggerUIService()), () ->
                    new CommandScope(UpdateCommandStep.COMMAND_NAME)
                            .addArgumentValue(DbUrlConnectionArgumentsCommandStep.DATABASE_ARG, datenbank)
                            .addArgumentValue(UpdateCommandStep.CHANGELOG_FILE_ARG, CHANGELOG)
                            .execute());
        } catch (Exception e) {
            throw new IllegalStateException("Liquibase db/mandant für " + schema + " fehlgeschlagen", e);
        }
    }

    /**
     * Alle Rechte in einer Transaktion: Sonst hätte die App-Rolle zwischen dem GRANT auf alle Tabellen und dem REVOKE
     * (nach einem Fehler dazwischen auch länger) DML auf {@code databasechangelog*}.
     */
    private void berechtigenAtomar(Connection verbindung, Statement befehl, String schema) throws SQLException {
        verbindung.setAutoCommit(false);
        try {
            berechtigen(befehl, schema);
            verbindung.commit();
        } catch (SQLException | RuntimeException e) {
            verbindung.rollback();
            throw e;
        } finally {
            verbindung.setAutoCommit(true);
        }
    }

    /**
     * Die App-Rolle liest und schreibt, legt aber keine Tabellen an; ohne die Rolle (Dev Services) entfällt das. Auf den
     * Tabellen aus {@link #NUR_EINFUEGEN} entzieht sie UPDATE, DELETE und TRUNCATE wieder, in derselben Transaktion wie
     * das GRANT (auch über die Default Privileges vergebene Rechte).
     */
    private void berechtigen(Statement befehl, String schema) throws SQLException {
        if (!rolleVorhanden(befehl.getConnection())) {
            return;
        }
        befehl.execute("grant usage on schema " + schema + " to " + appRolle);
        befehl.execute("grant select, insert, update, delete on all tables in schema " + schema + " to " + appRolle);
        befehl.execute("grant usage, select on all sequences in schema " + schema + " to " + appRolle);
        befehl.execute("alter default privileges in schema " + schema
                + " grant select, insert, update, delete on tables to " + appRolle);
        befehl.execute("alter default privileges in schema " + schema + " grant usage, select on sequences to " + appRolle);
        befehl.execute("revoke all on " + schema + ".databasechangelog, " + schema + ".databasechangeloglock from " + appRolle);
        for (String tabelle : NUR_EINFUEGEN) {
            if (tabelleVorhanden(befehl.getConnection(), schema, tabelle)) {
                befehl.execute("revoke update, delete, truncate on " + schema + "." + tabelle + " from " + appRolle);
            }
        }
    }

    private static boolean tabelleVorhanden(Connection verbindung, String schema, String tabelle) throws SQLException {
        try (PreparedStatement abfrage = verbindung.prepareStatement("select to_regclass(?) is not null")) {
            abfrage.setString(1, schema + "." + tabelle);
            try (ResultSet zeile = abfrage.executeQuery()) {
                return zeile.next() && zeile.getBoolean(1);
            }
        }
    }

    private boolean rolleVorhanden(Connection verbindung) throws SQLException {
        try (PreparedStatement abfrage = verbindung.prepareStatement("select 1 from pg_roles where rolname = ?")) {
            abfrage.setString(1, appRolle);
            return abfrage.executeQuery().next();
        }
    }

    private static void pruefen(String schema) {
        if (!SCHEMA.matcher(schema).matches()) {
            throw new IllegalArgumentException("Ungültiges Schema: " + schema);
        }
    }
}
