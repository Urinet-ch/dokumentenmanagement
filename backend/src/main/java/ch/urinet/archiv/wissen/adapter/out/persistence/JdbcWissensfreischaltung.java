package ch.urinet.archiv.wissen.adapter.out.persistence;

import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.wissen.application.WissensfreischaltungPort;
import io.agroal.api.AgroalDataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;

/**
 * Liest und setzt {@code public.mandant.wissen_freigeschaltet} per JDBC (Tabelle immer qualifiziert, wie
 * {@code JdbcMandantVerzeichnis}); in einer JTA-Transaktion hängt die Verbindung an ihr.
 */
@ApplicationScoped
public class JdbcWissensfreischaltung implements WissensfreischaltungPort {

    private final Instance<AgroalDataSource> datenquelle;

    public JdbcWissensfreischaltung(Instance<AgroalDataSource> datenquelle) {
        this.datenquelle = datenquelle;
    }

    @Override
    public Optional<Boolean> lesen(MandantCode mandant) {
        return abfragen(mandant, "select wissen_freigeschaltet from public.mandant where code = ?");
    }

    @Override
    public Optional<Boolean> sperrenUndLesen(MandantCode mandant) {
        return abfragen(mandant, "select wissen_freigeschaltet from public.mandant where code = ? for update");
    }

    @Override
    public void setzen(MandantCode mandant, boolean freigeschaltet) {
        try (Connection verbindung = datenquelle.get().getConnection();
             PreparedStatement aendern = verbindung.prepareStatement(
                     "update public.mandant set wissen_freigeschaltet = ? where code = ?")) {
            aendern.setBoolean(1, freigeschaltet);
            aendern.setString(2, mandant.wert());
            if (aendern.executeUpdate() != 1) {
                throw new IllegalStateException("Mandant nicht im Register");
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Freischaltung der Wissensbasis nicht speicherbar", e);
        }
    }

    private Optional<Boolean> abfragen(MandantCode mandant, String sql) {
        try (Connection verbindung = datenquelle.get().getConnection();
             PreparedStatement abfrage = verbindung.prepareStatement(sql)) {
            abfrage.setString(1, mandant.wert());
            try (ResultSet zeile = abfrage.executeQuery()) {
                return zeile.next() ? Optional.of(zeile.getBoolean(1)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Freischaltung der Wissensbasis nicht lesbar", e);
        }
    }
}
