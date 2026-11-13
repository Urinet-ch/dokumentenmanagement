package ch.urinet.archiv.verarbeitung.adapter.out.persistence;

import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.verarbeitung.application.AuftragZaehler;
import io.agroal.api.AgroalDataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;

/** Zählt die Aufträge eines Mandanten in {@code public.auftrag} je Status, ohne {@code FERTIG} (Req 16.4). */
@ApplicationScoped
public class JdbcAuftragZaehler implements AuftragZaehler {

    private final Instance<AgroalDataSource> datenquelle;

    public JdbcAuftragZaehler(Instance<AgroalDataSource> datenquelle) {
        this.datenquelle = datenquelle;
    }

    @Override
    public Map<String, Long> anzahlJeStatus(MandantCode mandant) {
        try (Connection verbindung = datenquelle.get().getConnection(); PreparedStatement zaehlen =
                verbindung.prepareStatement("select status, count(*) from public.auftrag "
                        + "where mandant = ? and status <> 'FERTIG' group by status")) {
            zaehlen.setString(1, mandant.wert());
            Map<String, Long> anzahl = new HashMap<>();
            try (ResultSet zeilen = zaehlen.executeQuery()) {
                while (zeilen.next()) {
                    anzahl.put(zeilen.getString(1), zeilen.getLong(2));
                }
            }
            return anzahl;
        } catch (SQLException e) {
            throw JdbcAuftragSchlange.fehler("Aufträge nicht gezählt", e);
        }
    }
}
