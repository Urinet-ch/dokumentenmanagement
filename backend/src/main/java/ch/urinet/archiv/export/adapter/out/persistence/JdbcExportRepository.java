package ch.urinet.archiv.export.adapter.out.persistence;

import ch.urinet.archiv.ablage.domain.AbgelegteDatei;
import ch.urinet.archiv.export.application.ExportRepository;
import ch.urinet.archiv.export.domain.Export;
import ch.urinet.archiv.export.domain.ExportStatus;
import ch.urinet.archiv.mandant.application.MandantAufloesung;
import io.agroal.api.AgroalDataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Tabelle {@code m_<code>.export} des Mandanten aus dem Kontext (Schema immer qualifiziert, Changeset 019). Jede
 * Anweisung mit eigener Zeitgrenze ({@value #ZEITGRENZE_S} s), weil auch der Worker sie aufruft (design.md
 * «Verarbeitung»).
 */
@ApplicationScoped
public class JdbcExportRepository implements ExportRepository {

    static final int ZEITGRENZE_S = 30;
    private static final Pattern SCHEMA = Pattern.compile("^m_[a-z0-9_]+$");
    private static final String SPALTEN = "id, geschaeftsjahr, status, s3_schluessel, s3_version, sha256, groesse, "
            + "anzahl, benutzer, rolle, erstellt, fertig";

    private final Instance<AgroalDataSource> datenquelle;
    private final MandantAufloesung aufloesung;

    public JdbcExportRepository(Instance<AgroalDataSource> datenquelle, MandantAufloesung aufloesung) {
        this.datenquelle = datenquelle;
        this.aufloesung = aufloesung;
    }

    @Override
    public boolean anlegen(UUID id, int geschaeftsjahr, String benutzer, String rolle) {
        String sql = "insert into " + schema() + ".export (id, geschaeftsjahr, status, benutzer, rolle) "
                + "values (?, ?, 'OFFEN', ?, ?) on conflict do nothing";
        return aendern(sql, id, geschaeftsjahr, benutzer, rolle) == 1;
    }

    @Override
    public Optional<Export> laden(UUID id) {
        List<Export> treffer = lesen("select " + SPALTEN + " from " + schema() + ".export where id = ?", id);
        return treffer.stream().findFirst();
    }

    @Override
    public List<Export> neueste(int anzahl) {
        return lesen("select " + SPALTEN + " from " + schema() + ".export order by erstellt desc, id limit ?", anzahl);
    }

    @Override
    public boolean fertig(UUID id, AbgelegteDatei zip, int anzahl) {
        return aendern("update " + schema() + ".export set status = 'FERTIG', s3_schluessel = ?, s3_version = ?, "
                        + "sha256 = ?, groesse = ?, anzahl = ?, fertig = clock_timestamp() where id = ? and status = 'OFFEN'",
                zip.schluessel(), zip.versionId(), zip.sha256(), zip.groesse(), anzahl, id) == 1;
    }

    @Override
    public boolean fehler(UUID id, UUID fehlerId) {
        return aendern("update " + schema() + ".export set status = 'FEHLER', fehler_id = ? where id = ? "
                + "and status = 'OFFEN'", fehlerId, id) == 1;
    }

    private String schema() {
        String schema = aufloesung.schema();
        if (!SCHEMA.matcher(schema).matches()) {
            throw new IllegalStateException("Export ohne Mandanten-Schema");
        }
        return schema;
    }

    private int aendern(String sql, Object... werte) {
        try (Connection verbindung = datenquelle.get().getConnection();
             PreparedStatement befehl = verbindung.prepareStatement(sql)) {
            befehl.setQueryTimeout(ZEITGRENZE_S);
            setzen(befehl, werte);
            return befehl.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Export nicht geschrieben", e);
        }
    }

    private List<Export> lesen(String sql, Object... werte) {
        try (Connection verbindung = datenquelle.get().getConnection();
             PreparedStatement abfrage = verbindung.prepareStatement(sql)) {
            abfrage.setQueryTimeout(ZEITGRENZE_S);
            setzen(abfrage, werte);
            List<Export> liste = new ArrayList<>();
            try (ResultSet z = abfrage.executeQuery()) {
                while (z.next()) {
                    liste.add(export(z));
                }
            }
            return liste;
        } catch (SQLException e) {
            throw new IllegalStateException("Export nicht gelesen", e);
        }
    }

    private static void setzen(PreparedStatement befehl, Object... werte) throws SQLException {
        for (int i = 0; i < werte.length; i++) {
            if (werte[i] instanceof UUID uuid) {
                befehl.setObject(i + 1, uuid, Types.OTHER);
            } else {
                befehl.setObject(i + 1, werte[i]);
            }
        }
    }

    private static Export export(ResultSet z) throws SQLException {
        Timestamp fertig = z.getTimestamp("fertig");
        long groesse = z.getLong("groesse");
        Optional<Long> mitGroesse = z.wasNull() ? Optional.empty() : Optional.of(groesse);
        int anzahl = z.getInt("anzahl");
        Optional<Integer> mitAnzahl = z.wasNull() ? Optional.empty() : Optional.of(anzahl);
        return new Export(z.getObject("id", UUID.class), z.getInt("geschaeftsjahr"),
                ExportStatus.valueOf(z.getString("status")), Optional.ofNullable(z.getString("s3_schluessel")),
                Optional.ofNullable(z.getString("s3_version")), Optional.ofNullable(z.getString("sha256")), mitGroesse,
                mitAnzahl, z.getString("benutzer"), z.getString("rolle"), z.getTimestamp("erstellt").toInstant(),
                Optional.ofNullable(fertig).map(Timestamp::toInstant));
    }
}
