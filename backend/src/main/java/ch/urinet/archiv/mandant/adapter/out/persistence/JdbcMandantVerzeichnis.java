package ch.urinet.archiv.mandant.adapter.out.persistence;

import ch.urinet.archiv.mandant.application.MandantVerzeichnis;
import ch.urinet.archiv.mandant.domain.MandantCode;
import io.agroal.api.AgroalDataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;

/**
 * Liest {@code public.mandant} per JDBC statt über Hibernate: Der Tenant-Resolver ruft das Verzeichnis beim Öffnen
 * jeder Session auf, eine Hibernate-Abfrage darin würde sich selbst auflösen. Tabelle immer qualifiziert, weil eine
 * Verbindung aus dem Pool noch auf einem Mandanten-Schema stehen kann.
 */
@ApplicationScoped
public class JdbcMandantVerzeichnis implements MandantVerzeichnis {

    // lazy: ohne Datasource (z. B. Bereitschaftsprüfung) ist die Bean inaktiv
    private final Instance<AgroalDataSource> datenquelle;

    public JdbcMandantVerzeichnis(Instance<AgroalDataSource> datenquelle) {
        this.datenquelle = datenquelle;
    }

    @Override
    public Optional<String> aktivesSchema(MandantCode code) {
        try (Connection verbindung = datenquelle.get().getConnection();
             PreparedStatement abfrage = verbindung.prepareStatement(
                     "select schema_name from public.mandant where code = ? and aktiv")) {
            abfrage.setString(1, code.wert());
            try (ResultSet zeile = abfrage.executeQuery()) {
                return zeile.next() ? Optional.of(zeile.getString(1)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Mandantenregister nicht lesbar", e);
        }
    }
}
