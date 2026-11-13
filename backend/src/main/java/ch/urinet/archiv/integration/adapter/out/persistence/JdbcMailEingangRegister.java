package ch.urinet.archiv.integration.adapter.out.persistence;

import ch.urinet.archiv.integration.application.mail.MailEingangRegister;
import ch.urinet.archiv.integration.domain.mail.MailStatus;
import ch.urinet.archiv.mandant.domain.MandantCode;
import io.agroal.api.AgroalDataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Tabelle {@code mail_eingang} im Schema {@code m_<code>} per JDBC, Schema immer qualifiziert. Läuft in der
 * Transaktion der Erfassung: Agroal reiht die Verbindung in die laufende JTA-Transaktion ein, darum gilt die Zeile nur
 * zusammen mit den Dokumenten.
 */
@ApplicationScoped
public class JdbcMailEingangRegister implements MailEingangRegister {

    private final Instance<AgroalDataSource> datenquelle;

    public JdbcMailEingangRegister(Instance<AgroalDataSource> datenquelle) {
        this.datenquelle = datenquelle;
    }

    @Override
    public Optional<MailStatus> status(MandantCode mandant, String messageId) {
        try (Connection verbindung = datenquelle.get().getConnection(); PreparedStatement abfrage =
                verbindung.prepareStatement("select status from " + tabelle(mandant) + " where message_id = ?")) {
            abfrage.setString(1, messageId);
            try (ResultSet zeile = abfrage.executeQuery()) {
                return zeile.next() ? Optional.of(MailStatus.valueOf(zeile.getString(1))) : Optional.empty();
            }
        } catch (SQLException e) {
            throw JdbcMailKonfiguration.fehler("Mail-Eingang nicht gelesen", e);
        }
    }

    @Override
    public boolean eintragen(MandantCode mandant, String messageId, Optional<String> absender,
                             Optional<String> betreff, MailStatus status) {
        try (Connection verbindung = datenquelle.get().getConnection(); PreparedStatement neu =
                verbindung.prepareStatement("insert into " + tabelle(mandant) + " (message_id, absender, betreff, "
                        + "status) values (?, ?, ?, ?) on conflict (message_id) do nothing")) {
            neu.setString(1, messageId);
            neu.setString(2, absender.orElse(null));
            neu.setString(3, betreff.orElse(null));
            neu.setString(4, status.name());
            return neu.executeUpdate() == 1;
        } catch (SQLException e) {
            throw JdbcMailKonfiguration.fehler("Mail-Eingang nicht eingetragen", e);
        }
    }

    @Override
    public boolean ausQuarantaene(MandantCode mandant, String messageId) {
        try (Connection verbindung = datenquelle.get().getConnection(); PreparedStatement aendern =
                verbindung.prepareStatement("update " + tabelle(mandant) + " set status = 'ERFASST', "
                        + "zeit = clock_timestamp() where message_id = ? and status = 'QUARANTAENE'")) {
            aendern.setString(1, messageId);
            return aendern.executeUpdate() == 1;
        } catch (SQLException e) {
            throw JdbcMailKonfiguration.fehler("Mail nicht aus der Quarantäne genommen", e);
        }
    }

    @Override
    public void dokumenteSetzen(MandantCode mandant, String messageId, List<UUID> dokumente) {
        try (Connection verbindung = datenquelle.get().getConnection(); PreparedStatement aendern =
                verbindung.prepareStatement("update " + tabelle(mandant) + " set dokument_ids = ? "
                        + "where message_id = ?")) {
            aendern.setArray(1, verbindung.createArrayOf("uuid", dokumente.toArray()));
            aendern.setString(2, messageId);
            aendern.executeUpdate();
        } catch (SQLException e) {
            throw JdbcMailKonfiguration.fehler("Dokumente des Mail-Eingangs nicht gespeichert", e);
        }
    }

    private static String tabelle(MandantCode mandant) {
        return mandant.schema() + ".mail_eingang";
    }
}
