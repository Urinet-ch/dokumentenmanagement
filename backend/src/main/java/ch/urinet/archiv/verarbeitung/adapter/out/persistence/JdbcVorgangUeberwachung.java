package ch.urinet.archiv.verarbeitung.adapter.out.persistence;

import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.verarbeitung.application.VorgangUeberwachung;
import ch.urinet.archiv.verarbeitung.domain.Befund;
import io.agroal.api.AgroalDataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

@ApplicationScoped
public class JdbcVorgangUeberwachung implements VorgangUeberwachung {

    private final Instance<AgroalDataSource> datenquelle;

    public JdbcVorgangUeberwachung(Instance<AgroalDataSource> datenquelle) {
        this.datenquelle = datenquelle;
    }

    @Override
    public List<Befund> ungeklaert(MandantCode mandant) {
        String sql = "select vorgang_id from public.verwaltung_vorgang where mandant = ? "
                + "and (phase = 'UNKLAR' or (phase = 'ANGEFRAGT' and bis <= clock_timestamp()))";
        try (Connection verbindung = datenquelle.get().getConnection();
             PreparedStatement abfrage = verbindung.prepareStatement(sql)) {
            abfrage.setString(1, mandant.wert());
            try (ResultSet zeilen = abfrage.executeQuery()) {
                List<Befund> befunde = new ArrayList<>();
                while (zeilen.next()) {
                    befunde.add(new Befund(Befund.Quelle.VERWALTUNG, zeilen.getString(1), "UNKLAR"));
                }
                return List.copyOf(befunde);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Ungeklärte Verwaltungsvorgänge nicht gelesen", e);
        }
    }
}
