package ch.urinet.archiv.verarbeitung.adapter.out.persistence;

import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.verarbeitung.application.AlarmSpeicher;
import ch.urinet.archiv.verarbeitung.domain.Befund;
import io.agroal.api.AgroalDataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.Optional;

/**
 * Tabelle {@code alarm} im Schema {@code m_<code>} per JDBC, Schema immer qualifiziert (läuft im Scheduler ohne
 * Mandant der Anfrage). {@link #beanspruchen} ist ein bedingtes {@code UPDATE}; bei Verwaltungsalarmen läuft der
 * Anspruch nach zehn Minuten ab, damit ein abgebrochener Worker die Zustellung nicht dauerhaft verhindert.
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
                     + "and not (schluessel = any(?::text[])) and (not ? or gemeldet is not null)")) {
            // zwei Anweisungen ohne gemeinsame Transaktion: Bricht die zweite ab, holt der nächste Lauf sie nach
            neu.setArray(1, verbindung.createArrayOf("text", schluessel));
            neu.executeUpdate();
            erledigt.setString(1, quelle.praefix());
            erledigt.setArray(2, verbindung.createArrayOf("text", schluessel));
            erledigt.setBoolean(3, quelle == Befund.Quelle.ABGLEICH);
            erledigt.executeUpdate();
        } catch (SQLException e) {
            throw JdbcAuftragSchlange.fehler("Alarme nicht abgeglichen", e);
        }
    }

    @Override
    public Optional<Instant> beanspruchen(MandantCode mandant, Befund befund) {
        String sql = verwaltungsalarm(befund)
                ? "update " + mandant.schema() + ".alarm set anspruch_bis = clock_timestamp() + interval '10 minutes' "
                    + "where schluessel = ? and gemeldet is null and erledigt is null "
                    + "and (anspruch_bis is null or anspruch_bis < clock_timestamp()) returning anspruch_bis"
                : "update " + mandant.schema() + ".alarm set gemeldet = clock_timestamp() "
                    + "where schluessel = ? and gemeldet is null and erledigt is null returning gemeldet";
        try (Connection verbindung = datenquelle.get().getConnection(); PreparedStatement aendern =
                verbindung.prepareStatement(sql)) {
            aendern.setString(1, befund.schluessel());
            try (ResultSet zeile = aendern.executeQuery()) {
                return zeile.next() ? Optional.of(zeile.getObject(1, OffsetDateTime.class).toInstant())
                        : Optional.empty();
            }
        } catch (SQLException e) {
            throw JdbcAuftragSchlange.fehler("Alarm nicht beansprucht", e);
        }
    }

    @Override
    public void bestaetigen(MandantCode mandant, Befund befund, Instant anspruch) {
        if (!verwaltungsalarm(befund)) {
            return;
        }
        try (Connection verbindung = datenquelle.get().getConnection(); PreparedStatement aendern =
                verbindung.prepareStatement("update " + mandant.schema()
                        + ".alarm set gemeldet = clock_timestamp(), anspruch_bis = null, "
                        + "erledigt = case when ? then clock_timestamp() else erledigt end "
                        + "where schluessel = ? and anspruch_bis = ? and gemeldet is null")) {
            aendern.setBoolean(1, befund.quelle() == Befund.Quelle.VERWALTUNGSALARM);
            aendern.setString(2, befund.schluessel());
            aendern.setObject(3, anspruch.atOffset(ZoneOffset.UTC));
            if (aendern.executeUpdate() != 1) {
                throw new IllegalStateException("Zustellung des Verwaltungsalarms nicht bestätigt");
            }
        } catch (SQLException e) {
            throw JdbcAuftragSchlange.fehler("Zustellung des Verwaltungsalarms nicht bestätigt", e);
        }
    }

    @Override
    public void freigeben(MandantCode mandant, Befund befund, Instant anspruch) {
        String spalte = verwaltungsalarm(befund) ? "anspruch_bis" : "gemeldet";
        try (Connection verbindung = datenquelle.get().getConnection(); PreparedStatement aendern =
                verbindung.prepareStatement("update " + mandant.schema() + ".alarm set " + spalte + " = null "
                        + "where schluessel = ? and " + spalte + " = ?")) {
            aendern.setString(1, befund.schluessel());
            aendern.setObject(2, anspruch.atOffset(ZoneOffset.UTC));
            aendern.executeUpdate();
        } catch (SQLException e) {
            throw JdbcAuftragSchlange.fehler("Alarm nicht freigegeben", e);
        }
    }

    private static boolean verwaltungsalarm(Befund befund) {
        return befund.quelle() == Befund.Quelle.ABGLEICH || befund.quelle() == Befund.Quelle.VERWALTUNGSALARM;
    }
}
