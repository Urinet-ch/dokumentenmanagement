package ch.urinet.archiv.benutzer.adapter.out.persistence;

import ch.urinet.archiv.benutzer.application.BenutzerNamen;
import ch.urinet.archiv.gemeinsam.domain.FachFehler;
import ch.urinet.archiv.gemeinsam.domain.Fehlercode;
import ch.urinet.archiv.mandant.application.MandantAufloesung;
import io.agroal.api.AgroalDataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.security.MessageDigest;
import java.util.regex.Pattern;

@ApplicationScoped
public class JdbcBenutzerNamen implements BenutzerNamen {
    private static final Pattern SCHEMA = Pattern.compile("^m_[a-z0-9_]+$");
    private final Instance<AgroalDataSource> datenquelle;
    private final MandantAufloesung aufloesung;

    public JdbcBenutzerNamen(Instance<AgroalDataSource> datenquelle, MandantAufloesung aufloesung) {
        this.datenquelle = datenquelle;
        this.aufloesung = aufloesung;
    }

    @Override
    public boolean vergeben(String benutzername) {
        NamenssperreHash hash = hash();
        String schema = aufloesung.schema();
        if (!SCHEMA.matcher(schema).matches()) {
            throw new IllegalStateException("Namensprüfung ohne Mandanten-Schema");
        }
        try (Connection verbindung = datenquelle.get().getConnection()) {
            pruefen(verbindung, hash);
            try (PreparedStatement abfrage = verbindung.prepareStatement(
                    "select exists (select 1 from public.benutzername_sperre where hash = ?)")) {
                abfrage.setBytes(1, hash.hash(benutzername));
                try (ResultSet zeile = abfrage.executeQuery()) {
                    if (!zeile.next()) {
                        throw new IllegalStateException("Namensprüfung fehlgeschlagen");
                    }
                    if (zeile.getBoolean(1)) {
                        return true;
                    }
                }
            }
            try (PreparedStatement mandanten = verbindung.prepareStatement(
                    "select schema_name from public.mandant where aktiv or "
                            + "(deaktiviert_am is not null and exists "
                            + "(select 1 from pg_namespace where nspname = schema_name))");
                 ResultSet schemata = mandanten.executeQuery()) {
                while (schemata.next()) {
                    String zielSchema = schemata.getString(1);
                    if (zielSchema == null || !SCHEMA.matcher(zielSchema).matches()) {
                        throw new IllegalStateException("Ungültiges Schema bei Namensprüfung");
                    }
                    String sql = "select exists (select 1 from " + zielSchema
                            + ".benutzer_verzeichnis where lower(name) = lower(?))";
                    try (PreparedStatement abfrage = verbindung.prepareStatement(sql)) {
                        abfrage.setString(1, benutzername);
                        try (ResultSet zeile = abfrage.executeQuery()) {
                            if (!zeile.next()) {
                                throw new IllegalStateException("Namensprüfung fehlgeschlagen");
                            }
                            if (zeile.getBoolean(1)) {
                                return true;
                            }
                        }
                    }
                }
                return false;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Namensprüfung fehlgeschlagen", e);
        }
    }

    @Override
    public void sperren(String benutzername) {
        try (Connection verbindung = datenquelle.get().getConnection();
             PreparedStatement einfuegen = verbindung.prepareStatement(
                     "insert into public.benutzername_sperre (hash) values (?) on conflict do nothing")) {
            NamenssperreHash hash = hash();
            pruefen(verbindung, hash);
            einfuegen.setBytes(1, hash.hash(benutzername));
            if (einfuegen.executeUpdate() != 1) {
                throw new FachFehler(Fehlercode.BENUTZER_EXISTIERT);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Namenssperre nicht geschrieben", e);
        }
    }

    private static void pruefen(Connection verbindung, NamenssperreHash hash) throws SQLException {
        try (PreparedStatement pruefen = verbindung.prepareStatement(
                "select pruefwert from public.benutzername_sperre_status where id = 1");
             ResultSet status = pruefen.executeQuery()) {
            if (!status.next() || !MessageDigest.isEqual(status.getBytes(1), hash.pruefwert())) {
                throw new IllegalStateException("Namenssperre nicht migriert oder Salz stimmt nicht überein");
            }
        }
    }

    @Override
    public void vorMandantenabbauSichern(String schema) {
        if (schema == null || !SCHEMA.matcher(schema).matches()) {
            throw new IllegalStateException("Ungültiges Schema bei Namenssicherung");
        }
        NamenssperreHash hash = hash();
        try (Connection verbindung = datenquelle.get().getConnection()) {
            pruefen(verbindung, hash);
            try (PreparedStatement lesen = verbindung.prepareStatement(
                    "select distinct name from " + schema + ".benutzer_verzeichnis where name is not null");
                 ResultSet namen = lesen.executeQuery();
                 PreparedStatement einfuegen = verbindung.prepareStatement(
                         "insert into public.benutzername_sperre (hash) values (?) on conflict do nothing")) {
                while (namen.next()) {
                    einfuegen.setBytes(1, hash.hash(namen.getString(1)));
                    einfuegen.executeUpdate();
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Namenssicherung vor Mandantenabbau fehlgeschlagen", e);
        }
    }

    private static NamenssperreHash hash() {
        return NamenssperreHash.ausKonfiguration()
                .orElseThrow(() -> new IllegalStateException("Secret-Datei namenssperre_salz fehlt"));
    }
}
