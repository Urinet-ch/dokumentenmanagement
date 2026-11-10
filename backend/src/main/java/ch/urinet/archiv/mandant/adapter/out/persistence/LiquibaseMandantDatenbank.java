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
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.sql.Connection;
import java.sql.PreparedStatement;
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
    // Namen gehen als Bezeichner in SQL ein; nur dieses Muster ist erlaubt
    private static final Pattern SCHEMA = Pattern.compile("^m_[a-z0-9_]+$");
    private static final Pattern ROLLE = Pattern.compile("^[a-z_][a-z0-9_]*$");

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

    private void einrichten(String schema, boolean anlegen) {
        pruefen(schema);
        try (Connection verbindung = eigentuemer.oeffnen(); Statement befehl = verbindung.createStatement()) {
            if (anlegen) {
                befehl.execute("create schema if not exists " + schema);
            }
            befehl.execute("set search_path to " + schema);
            try {
                migrieren(verbindung, schema);
                // Liquibase lässt autoCommit=false stehen; ohne Commit rollt close() die Rechte zurück
                verbindung.setAutoCommit(true);
                berechtigen(befehl, schema);
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
                + "vertraulichkeit, aufbewahrung_jahre, aufbewahrung_ab, gesetzliche_pflicht, aktiv) "
                + "values (?, ?, ?, ?, cast(? as jsonb), ?, ?, ?, ?, ?) on conflict (code) do nothing";
        try (Connection verbindung = eigentuemer.oeffnen(); PreparedStatement einfuegen = verbindung.prepareStatement(sql)) {
            verbindung.setAutoCommit(true);
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
                einfuegen.addBatch();
            }
            einfuegen.executeBatch();
        } catch (SQLException e) {
            throw new IllegalStateException("Dokumentarten für " + schema + " nicht eingespielt", e);
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
            Scope.child(Map.of(Scope.Attr.resourceAccessor.name(),
                    new ClassLoaderResourceAccessor(LiquibaseMandantDatenbank.class.getClassLoader())), () ->
                    new CommandScope(UpdateCommandStep.COMMAND_NAME)
                            .addArgumentValue(DbUrlConnectionArgumentsCommandStep.DATABASE_ARG, datenbank)
                            .addArgumentValue(UpdateCommandStep.CHANGELOG_FILE_ARG, CHANGELOG)
                            .execute());
        } catch (Exception e) {
            throw new IllegalStateException("Liquibase db/mandant für " + schema + " fehlgeschlagen", e);
        }
    }

    /** Die App-Rolle liest und schreibt, legt aber keine Tabellen an; ohne die Rolle (Dev Services) entfällt das. */
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
