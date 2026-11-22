package ch.urinet.archiv.verarbeitung.adapter.out.persistence;

import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.verarbeitung.application.AbgleichAlarmQuelle;
import ch.urinet.archiv.verarbeitung.domain.Befund;
import io.agroal.api.AgroalDataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

@ApplicationScoped
public class JdbcAbgleichAlarmQuelle implements AbgleichAlarmQuelle {
    private final Instance<AgroalDataSource> datenquelle;

    public JdbcAbgleichAlarmQuelle(Instance<AgroalDataSource> datenquelle) {
        this.datenquelle = datenquelle;
    }

    @Override
    public List<Befund> offene(MandantCode mandant) {
        String sql = "select split_part(schluessel, ':', 1), split_part(schluessel, ':', 2), "
                + "split_part(schluessel, ':', 3), erstmals from " + mandant.schema()
                + ".alarm where (starts_with(schluessel, ?) or starts_with(schluessel, ?)) and erledigt is null";
        try (Connection verbindung = datenquelle.get().getConnection();
             PreparedStatement abfrage = verbindung.prepareStatement(sql)) {
            abfrage.setString(1, Befund.Quelle.ABGLEICH.praefix());
            abfrage.setString(2, Befund.Quelle.VERWALTUNGSALARM.praefix());
            try (ResultSet zeilen = abfrage.executeQuery()) {
                List<Befund> befunde = new ArrayList<>();
                while (zeilen.next()) {
                    Befund.Quelle quelle = switch (zeilen.getString(1)) {
                        case "abgleich" -> Befund.Quelle.ABGLEICH;
                        case "verwaltungsalarm" -> Befund.Quelle.VERWALTUNGSALARM;
                        default -> throw new IllegalStateException("Unbekannte Quelle des Verwaltungsalarms");
                    };
                    befunde.add(new Befund(quelle, zeilen.getString(2), zeilen.getString(3),
                            zeilen.getObject(4, OffsetDateTime.class).toInstant()));
                }
                return List.copyOf(befunde);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Abgleichalarme nicht gelesen", e);
        }
    }
}
