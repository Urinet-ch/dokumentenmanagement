package ch.urinet.archiv.protokoll.adapter.out.persistence;

import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.protokoll.application.ManifestFortschritt;
import io.agroal.api.AgroalDataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Optional;

/**
 * Liest den Stand der Manifest-Kette per JDBC im Schema {@code m_<code>}, Schema immer qualifiziert (läuft im Scheduler
 * ohne Mandant der Anfrage).
 */
@ApplicationScoped
public class JdbcManifestFortschritt implements ManifestFortschritt {

    private final Instance<AgroalDataSource> datenquelle;

    public JdbcManifestFortschritt(Instance<AgroalDataSource> datenquelle) {
        this.datenquelle = datenquelle;
    }

    @Override
    public Optional<Stand> stand(MandantCode mandant) {
        try (Connection verbindung = datenquelle.get().getConnection(); PreparedStatement abfrage =
                verbindung.prepareStatement("select max(datum), max(erstellt) from " + mandant.schema() + ".manifest");
             ResultSet zeile = abfrage.executeQuery()) {
            zeile.next();
            LocalDate datum = zeile.getObject(1, LocalDate.class);
            if (datum == null) {
                return Optional.empty();
            }
            return Optional.of(new Stand(datum, zeile.getObject(2, OffsetDateTime.class).toInstant()));
        } catch (SQLException e) {
            // nur der SQLState: Die Meldung von PostgreSQL kann Werte der Zeile enthalten (Req 16.7)
            throw new IllegalStateException("Stand der Manifeste nicht gelesen (SQLState " + e.getSQLState() + ")",
                    new SQLException((String) null, e.getSQLState()));
        }
    }
}
