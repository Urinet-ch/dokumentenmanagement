package ch.urinet.archiv.benutzer.adapter.out.persistence;

import liquibase.change.custom.CustomTaskChange;
import liquibase.database.Database;
import liquibase.database.jvm.JdbcConnection;
import liquibase.exception.CustomChangeException;
import liquibase.exception.ValidationErrors;
import liquibase.resource.ResourceAccessor;

import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.regex.Pattern;

public class NamenssperreBestandMigration implements CustomTaskChange {
    private static final Pattern SCHEMA = Pattern.compile("^m_[a-z0-9_]+$");

    @Override
    public void execute(Database database) throws CustomChangeException {
        var hash = NamenssperreHash.ausKonfiguration()
                .orElseThrow(() -> new CustomChangeException("Namenssperre-Salz fehlt"));
        Connection verbindung = ((JdbcConnection) database.getConnection()).getWrappedConnection();
        try {
            try (PreparedStatement pruefen = verbindung.prepareStatement(
                    "select pruefwert from public.benutzername_sperre_status where id = 1");
                 ResultSet status = pruefen.executeQuery()) {
                if (status.next() && !MessageDigest.isEqual(status.getBytes(1), hash.pruefwert())) {
                    throw new CustomChangeException("Namenssperre-Salz stimmt nicht mit dem Bestand überein");
                }
            }
            uebernehmen(verbindung, hash);
            try (PreparedStatement markieren = verbindung.prepareStatement(
                    "insert into public.benutzername_sperre_status (id, pruefwert) values (1, ?) "
                            + "on conflict (id) do nothing")) {
                markieren.setBytes(1, hash.pruefwert());
                markieren.executeUpdate();
            }
        } catch (SQLException e) {
            throw new CustomChangeException("Bestandsmigration der Namenssperre fehlgeschlagen", e);
        }
    }

    private void uebernehmen(Connection verbindung, NamenssperreHash hash) throws SQLException, CustomChangeException {
        try (PreparedStatement schemata = verbindung.prepareStatement(
                "select schema_name from public.mandant where exists "
                        + "(select 1 from pg_namespace where nspname = schema_name)");
             ResultSet zeilen = schemata.executeQuery();
             PreparedStatement einfuegen = verbindung.prepareStatement(
                     "insert into public.benutzername_sperre (hash) values (?) on conflict do nothing")) {
            while (zeilen.next()) {
                String schema = zeilen.getString(1);
                if (schema == null || !SCHEMA.matcher(schema).matches()) {
                    throw new CustomChangeException("Ungültiges Mandantenschema bei Namenssperre-Migration");
                }
                try (PreparedStatement vorhanden = verbindung.prepareStatement("select to_regclass(?)")) {
                    vorhanden.setString(1, schema + ".benutzer_verzeichnis");
                    try (ResultSet tabelle = vorhanden.executeQuery()) {
                        if (!tabelle.next() || tabelle.getString(1) == null) {
                            continue;
                        }
                    }
                }
                try (PreparedStatement lesen = verbindung.prepareStatement(
                        "select distinct name from " + schema + ".benutzer_verzeichnis where name is not null");
                     ResultSet namen = lesen.executeQuery()) {
                    while (namen.next()) {
                        einfuegen.setBytes(1, hash.hash(namen.getString(1)));
                        einfuegen.executeUpdate();
                    }
                }
            }
        }
    }

    @Override
    public String getConfirmationMessage() {
        return "Namenssperre aus Benutzerverzeichnissen übernommen";
    }

    @Override
    public void setUp() {
    }

    @Override
    public void setFileOpener(ResourceAccessor resourceAccessor) {
    }

    @Override
    public ValidationErrors validate(Database database) {
        return new ValidationErrors();
    }
}
