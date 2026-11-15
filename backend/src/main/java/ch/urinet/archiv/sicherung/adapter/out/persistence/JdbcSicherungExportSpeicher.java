package ch.urinet.archiv.sicherung.adapter.out.persistence;

import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.sicherung.application.SicherungExportSpeicher;
import ch.urinet.archiv.sicherung.domain.SicherungsArt;
import io.agroal.api.AgroalDataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.util.Optional;

/** JDBC-Adapter für {@code public.sicherung_export}. */
@ApplicationScoped
public class JdbcSicherungExportSpeicher implements SicherungExportSpeicher {

    private final Instance<AgroalDataSource> datenquelle;

    public JdbcSicherungExportSpeicher(Instance<AgroalDataSource> datenquelle) {
        this.datenquelle = datenquelle;
    }

    @Override
    public void festhalten(SicherungsArt art, Optional<MandantCode> mandant, String sha256) {
        String sql = "insert into public.sicherung_export (art, mandant, sha256) values (?, ?, ?)";
        try (Connection verbindung = datenquelle.get().getConnection();
             PreparedStatement einfuegen = verbindung.prepareStatement(sql)) {
            einfuegen.setString(1, art.code());
            if (mandant.isPresent()) {
                einfuegen.setString(2, mandant.get().wert());
            } else {
                einfuegen.setNull(2, Types.VARCHAR);
            }
            einfuegen.setString(3, sha256);
            einfuegen.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Sicherungsexport nicht festgehalten", e);
        }
    }
}
