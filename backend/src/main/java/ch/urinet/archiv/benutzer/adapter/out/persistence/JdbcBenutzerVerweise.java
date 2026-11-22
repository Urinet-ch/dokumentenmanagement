package ch.urinet.archiv.benutzer.adapter.out.persistence;

import ch.urinet.archiv.benutzer.application.BenutzerVerweise;
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
public class JdbcBenutzerVerweise implements BenutzerVerweise {
    private static final Pattern SCHEMA = Pattern.compile("^m_[a-z0-9_]+$");
    private final Instance<AgroalDataSource> datenquelle;
    private final MandantAufloesung aufloesung;

    public JdbcBenutzerVerweise(Instance<AgroalDataSource> datenquelle, MandantAufloesung aufloesung) {
        this.datenquelle = datenquelle;
        this.aufloesung = aufloesung;
    }

    @Override
    public Anzahl zaehlen(String benutzer) {
        String schema = aufloesung.schema();
        if (!SCHEMA.matcher(schema).matches()) {
            throw new IllegalStateException("Benutzerverweise ohne Mandanten-Schema");
        }
        String sql = "select (select count(*) from " + schema + ".zugriff_protokoll "
                + "where benutzer = ? or details->>'ziel' = ?) as protokolle, "
                + "(select count(*) from " + schema + ".dokument d where d.erstellt_von = ? "
                + "or d.person_benutzer = ? or d.bestaetigt_von = ? or exists (select 1 from "
                + schema + ".dokument_version v where v.dokument_id = d.id and v.geaendert_von = ?)) as dokumente";
        try (Connection verbindung = datenquelle.get().getConnection();
             PreparedStatement abfrage = verbindung.prepareStatement(sql)) {
            abfrage.setQueryTimeout(30);
            for (int i = 1; i <= 6; i++) {
                abfrage.setString(i, benutzer);
            }
            try (ResultSet zeile = abfrage.executeQuery()) {
                if (!zeile.next()) {
                    throw new IllegalStateException("Benutzerverweise nicht lesbar");
                }
                return new Anzahl(zeile.getLong("protokolle"), zeile.getLong("dokumente"));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Benutzerverweise nicht lesbar", e);
        }
    }
}
