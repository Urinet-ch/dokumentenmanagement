package ch.urinet.archiv.wissen.adapter.out.persistence;

import ch.urinet.archiv.ablage.domain.AbgelegteDatei;
import ch.urinet.archiv.mandant.application.MandantAufloesung;
import ch.urinet.archiv.wissen.application.WissenExportRepository;
import io.agroal.api.AgroalDataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

@ApplicationScoped
public class JdbcWissenExportRepository implements WissenExportRepository {
    private static final Pattern SCHEMA = Pattern.compile("^m_[a-z0-9_]+$");
    private final Instance<AgroalDataSource> datenbank;
    private final MandantAufloesung aufloesung;

    public JdbcWissenExportRepository(Instance<AgroalDataSource> datenbank, MandantAufloesung aufloesung) {
        this.datenbank = datenbank;
        this.aufloesung = aufloesung;
    }

    @Override
    public void anlegen(UUID id, String benutzer, String rolle) {
        aendern("insert into " + schema() + ".wissens_export (id, status, benutzer, rolle) "
                + "values (?, 'OFFEN', ?, ?)", id, benutzer, rolle);
    }

    @Override
    public Optional<Export> laden(UUID id) {
        String sql = "select id, status, benutzer, rolle, erstellt, fertig, s3_schluessel, s3_version, "
                + "sha256, groesse, fehler_id from " + schema() + ".wissens_export where id = ?";
        try (Connection c = datenbank.get().getConnection(); PreparedStatement abfrage = c.prepareStatement(sql)) {
            abfrage.setQueryTimeout(30);
            abfrage.setObject(1, id);
            try (ResultSet rs = abfrage.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                Timestamp fertig = rs.getTimestamp("fertig");
                return Optional.of(new Export(rs.getObject("id", UUID.class), Status.valueOf(rs.getString("status")),
                        rs.getString("benutzer"), rs.getString("rolle"), rs.getTimestamp("erstellt").toInstant(),
                        fertig == null ? null : fertig.toInstant(), rs.getString("s3_schluessel"),
                        rs.getString("s3_version"), rs.getString("sha256"), rs.getObject("groesse", Long.class),
                        rs.getObject("fehler_id", UUID.class)));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Wissensexport nicht gelesen", e);
        }
    }

    @Override
    public boolean fertig(UUID id, AbgelegteDatei zip) {
        return aendern("update " + schema() + ".wissens_export set status = 'FERTIG', s3_schluessel = ?, "
                        + "s3_version = ?, sha256 = ?, groesse = ?, fertig = clock_timestamp() "
                        + "where id = ? and status = 'OFFEN'",
                zip.schluessel(), zip.versionId(), zip.sha256(), zip.groesse(), id) == 1;
    }

    @Override
    public boolean fehler(UUID id, UUID fehlerId) {
        return aendern("update " + schema() + ".wissens_export set status = 'FEHLER', fehler_id = ? "
                + "where id = ? and status = 'OFFEN'", fehlerId, id) == 1;
    }

    private String schema() {
        String schema = aufloesung.schema();
        if (!SCHEMA.matcher(schema).matches()) {
            throw new IllegalStateException("Wissensexport ohne Mandanten-Schema");
        }
        return schema;
    }

    private int aendern(String sql, Object... werte) {
        try (Connection c = datenbank.get().getConnection(); PreparedStatement befehl = c.prepareStatement(sql)) {
            befehl.setQueryTimeout(30);
            for (int i = 0; i < werte.length; i++) {
                befehl.setObject(i + 1, werte[i]);
            }
            return befehl.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Wissensexport nicht geschrieben", e);
        }
    }
}
