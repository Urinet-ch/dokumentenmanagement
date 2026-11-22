package ch.urinet.archiv.mandant.adapter.out.persistence;

import ch.urinet.archiv.mandant.application.MandantAufloesung;
import ch.urinet.archiv.mandant.application.MandantVollexportKonfiguration;
import io.agroal.api.AgroalDataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.regex.Pattern;

/** Liest die unveränderlichen Konfigurationsabbilder des aktuellen Mandantenschemas. */
@ApplicationScoped
public class JdbcMandantVollexportKonfiguration implements MandantVollexportKonfiguration {

    private static final int MAX_VERSIONEN = 200;
    private static final int MAX_JSON_BYTES = 131_072;
    private static final Pattern SCHEMA = Pattern.compile("^m_[a-z0-9_]+$");
    private final Instance<AgroalDataSource> datenquelle;
    private final MandantAufloesung aufloesung;

    public JdbcMandantVollexportKonfiguration(Instance<AgroalDataSource> datenquelle, MandantAufloesung aufloesung) {
        this.datenquelle = datenquelle;
        this.aufloesung = aufloesung;
    }

    @Override
    public List<Version> versionen() {
        String schema = aufloesung.schema();
        if (!SCHEMA.matcher(schema).matches()) {
            throw new IllegalStateException("Vollexport ohne Mandanten-Schema");
        }
        String sql = "select version, inhalt::text from " + schema + ".konfiguration_version "
                + "order by version limit " + (MAX_VERSIONEN + 1);
        try (Connection verbindung = datenquelle.get().getConnection();
             PreparedStatement abfrage = verbindung.prepareStatement(sql)) {
            abfrage.setQueryTimeout(30);
            try (ResultSet zeilen = abfrage.executeQuery()) {
                var versionen = new java.util.ArrayList<Version>();
                while (zeilen.next()) {
                    String json = zeilen.getString(2);
                    if (json.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_JSON_BYTES) {
                        throw new IllegalStateException("Konfigurationsabbild überschreitet die Exportgrenze");
                    }
                    versionen.add(new Version(zeilen.getInt(1), json));
                }
                if (versionen.size() > MAX_VERSIONEN) {
                    throw new IllegalStateException("Zu viele Konfigurationsversionen für Vollexport");
                }
                return List.copyOf(versionen);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Konfigurationsabbilder für Vollexport nicht gelesen", e);
        }
    }
}
