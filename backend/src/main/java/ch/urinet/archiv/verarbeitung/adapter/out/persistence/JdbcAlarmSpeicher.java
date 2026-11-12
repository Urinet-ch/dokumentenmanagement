package ch.urinet.archiv.verarbeitung.adapter.out.persistence;

import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.verarbeitung.application.AlarmSpeicher;
import ch.urinet.archiv.verarbeitung.domain.Befund;
import io.agroal.api.AgroalDataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Collection;

/**
 * Tabelle {@code alarm} im Schema {@code m_<code>} per JDBC, Schema immer qualifiziert (läuft im Scheduler ohne
 * Mandant der Anfrage). {@link #beanspruchen} ist ein bedingtes {@code UPDATE}: Auch zwei Worker melden einen Befund
 * nur einmal.
 */
@ApplicationScoped
public class JdbcAlarmSpeicher implements AlarmSpeicher {

    private final Instance<AgroalDataSource> datenquelle;

    public JdbcAlarmSpeicher(Instance<AgroalDataSource> datenquelle) {
        this.datenquelle = datenquelle;
    }

    @Override
    public void abgleichen(MandantCode mandant, Befund.Quelle quelle, Collection<Befund> befunde) {
        String tabelle = mandant.schema() + ".alarm";
        String[] schluessel = befunde.stream().map(Befund::schluessel).distinct().toArray(String[]::new);
        try (Connection verbindung = datenquelle.get().getConnection();
             PreparedStatement neu = verbindung.prepareStatement("insert into " + tabelle + " (schluessel) "
                     + "select unnest(?::text[]) on conflict (schluessel) do update set erstmals = clock_timestamp(), "
                     + "gemeldet = null, erledigt = null where alarm.erledigt is not null");
             PreparedStatement erledigt = verbindung.prepareStatement("update " + tabelle
                     + " set erledigt = clock_timestamp() where erledigt is null and starts_with(schluessel, ?) "
                     + "and not (schluessel = any(?::text[]))")) {
            // zwei Anweisungen ohne gemeinsame Transaktion: Bricht die zweite ab, holt der nächste Lauf sie nach
            neu.setArray(1, verbindung.createArrayOf("text", schluessel));
            neu.executeUpdate();
            erledigt.setString(1, quelle.praefix());
            erledigt.setArray(2, verbindung.createArrayOf("text", schluessel));
            erledigt.executeUpdate();
        } catch (SQLException e) {
            throw JdbcAuftragSchlange.fehler("Alarme nicht abgeglichen", e);
        }
    }

    @Override
    public boolean beanspruchen(MandantCode mandant, Befund befund) {
        return aendern(mandant, "gemeldet = clock_timestamp()", "gemeldet is null and erledigt is null", befund,
                "Alarm nicht beansprucht") == 1;
    }

    @Override
    public void freigeben(MandantCode mandant, Befund befund) {
        aendern(mandant, "gemeldet = null", "erledigt is null", befund, "Alarm nicht freigegeben");
    }

    private int aendern(MandantCode mandant, String setzen, String bedingung, Befund befund, String was) {
        try (Connection verbindung = datenquelle.get().getConnection(); PreparedStatement aendern =
                verbindung.prepareStatement("update " + mandant.schema() + ".alarm set " + setzen
                        + " where schluessel = ? and " + bedingung)) {
            aendern.setString(1, befund.schluessel());
            return aendern.executeUpdate();
        } catch (SQLException e) {
            throw JdbcAuftragSchlange.fehler(was, e);
        }
    }
}
