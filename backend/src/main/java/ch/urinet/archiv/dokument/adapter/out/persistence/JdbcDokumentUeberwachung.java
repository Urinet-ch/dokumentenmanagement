package ch.urinet.archiv.dokument.adapter.out.persistence;

import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.verarbeitung.application.DokumentUeberwachung;
import ch.urinet.archiv.verarbeitung.domain.Befund;
import io.agroal.api.AgroalDataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Überwachung der Dokumente per JDBC im Schema {@code m_<code>}, Schema immer qualifiziert (läuft im Scheduler ohne
 * Mandant der Anfrage). Liest nur ID und Status; {@code status_seit} setzt ein Trigger bei jedem Statuswechsel
 * (Changeset 012).
 */
@ApplicationScoped
public class JdbcDokumentUeberwachung implements DokumentUeberwachung {

    private final Instance<AgroalDataSource> datenquelle;

    public JdbcDokumentUeberwachung(Instance<AgroalDataSource> datenquelle) {
        this.datenquelle = datenquelle;
    }

    @Override
    public List<Befund> laengerAls(MandantCode mandant, Duration grenze) {
        try (Connection verbindung = datenquelle.get().getConnection(); PreparedStatement suchen =
                verbindung.prepareStatement("select id, status from " + mandant.schema() + ".dokument "
                        + "where status = any(?::text[]) and status_seit < clock_timestamp() - ? * interval '1 ms' "
                        + "order by status_seit, id")) {
            suchen.setArray(1, verbindung.createArrayOf("text", UEBERWACHTE_STATUS.toArray()));
            suchen.setLong(2, grenze.toMillis());
            List<Befund> befunde = new ArrayList<>();
            try (ResultSet zeilen = suchen.executeQuery()) {
                while (zeilen.next()) {
                    befunde.add(new Befund(Befund.Quelle.DOKUMENT, zeilen.getString(1), zeilen.getString(2)));
                }
            }
            return befunde;
        } catch (SQLException e) {
            throw fehler("Dokumente nicht überwacht", e);
        }
    }

    @Override
    public Map<String, Long> anzahlJeStatus(MandantCode mandant) {
        try (Connection verbindung = datenquelle.get().getConnection(); PreparedStatement zaehlen =
                verbindung.prepareStatement("select status, count(*) from " + mandant.schema() + ".dokument "
                        + "where status not in ('ARCHIVIERT', 'VERWORFEN') group by status")) {
            Map<String, Long> anzahl = new HashMap<>();
            try (ResultSet zeilen = zaehlen.executeQuery()) {
                while (zeilen.next()) {
                    anzahl.put(zeilen.getString(1), zeilen.getLong(2));
                }
            }
            return anzahl;
        } catch (SQLException e) {
            throw fehler("Dokumente nicht gezählt", e);
        }
    }

    // nur der SQLState: Die Meldung von PostgreSQL kann Werte der Zeile enthalten (Req 16.7)
    private static IllegalStateException fehler(String was, SQLException e) {
        return new IllegalStateException(was + " (SQLState " + e.getSQLState() + ")",
                new SQLException((String) null, e.getSQLState()));
    }
}
