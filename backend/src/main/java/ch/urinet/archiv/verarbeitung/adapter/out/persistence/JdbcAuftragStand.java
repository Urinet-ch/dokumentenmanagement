package ch.urinet.archiv.verarbeitung.adapter.out.persistence;

import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.verarbeitung.application.AuftragStand;
import ch.urinet.archiv.verarbeitung.domain.AuftragTyp;
import io.agroal.api.AgroalDataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Optional;

/** Liest das Ende des zuletzt gescheiterten Auftrags eines Typs und Mandanten aus {@code public.auftrag}. */
@ApplicationScoped
public class JdbcAuftragStand implements AuftragStand {

    private final Instance<AgroalDataSource> datenquelle;

    public JdbcAuftragStand(Instance<AgroalDataSource> datenquelle) {
        this.datenquelle = datenquelle;
    }

    @Override
    public Optional<Instant> letzterFehler(MandantCode mandant, AuftragTyp typ) {
        try (Connection verbindung = datenquelle.get().getConnection(); PreparedStatement abfrage =
                verbindung.prepareStatement("select max(beendet) from public.auftrag where mandant = ? and typ = ? "
                        + "and status = 'FEHLER'")) {
            abfrage.setString(1, mandant.wert());
            abfrage.setString(2, typ.wert());
            try (ResultSet zeile = abfrage.executeQuery()) {
                zeile.next();
                return Optional.ofNullable(zeile.getObject(1, OffsetDateTime.class)).map(OffsetDateTime::toInstant);
            }
        } catch (SQLException e) {
            throw JdbcAuftragSchlange.fehler("Stand der Aufträge nicht gelesen", e);
        }
    }

    @Override
    public Optional<Juengster> juengster(MandantCode mandant, AuftragTyp typ) {
        try (Connection verbindung = datenquelle.get().getConnection(); PreparedStatement abfrage =
                verbindung.prepareStatement("select status, beendet from public.auftrag where mandant = ? and typ = ? "
                        + "order by id desc limit 1")) {
            abfrage.setString(1, mandant.wert());
            abfrage.setString(2, typ.wert());
            try (ResultSet zeile = abfrage.executeQuery()) {
                if (!zeile.next()) {
                    return Optional.empty();
                }
                return Optional.of(new Juengster(zeile.getString(1), Optional
                        .ofNullable(zeile.getObject(2, OffsetDateTime.class)).map(OffsetDateTime::toInstant)));
            }
        } catch (SQLException e) {
            throw JdbcAuftragSchlange.fehler("Stand der Aufträge nicht gelesen", e);
        }
    }
}
