package ch.urinet.archiv.benutzer.adapter.out.persistence;

import ch.urinet.archiv.benutzer.application.BenutzerNamen;
import ch.urinet.archiv.mandant.application.MandantAufloesung;
import io.agroal.api.AgroalDataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
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
        String schema = aufloesung.schema();
        if (!SCHEMA.matcher(schema).matches()) {
            throw new IllegalStateException("Namensprüfung ohne Mandanten-Schema");
        }
        try (Connection verbindung = datenquelle.get().getConnection();
             PreparedStatement mandanten = verbindung.prepareStatement(
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
        } catch (SQLException e) {
            throw new IllegalStateException("Namensprüfung fehlgeschlagen", e);
        }
    }
}
