package ch.urinet.archiv.sicherung.adapter.out.persistence;

import ch.urinet.archiv.mandant.application.MandantAufloesung;
import ch.urinet.archiv.mandant.application.MandantKontext;
import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.sicherung.application.WiederherstellungsReferenz;
import io.agroal.api.AgroalDataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Objects;
import java.util.regex.Pattern;

/** Schreibt beim Restore die veränderlichen Dateireferenzen unter {@code archiv/} und {@code eingang/} um. */
@ApplicationScoped
public class JdbcWiederherstellungsReferenz implements WiederherstellungsReferenz {

    private static final Pattern SCHEMA = Pattern.compile("^m_[a-z0-9_]+$");
    private static final String DATEI_AKTUALISIEREN = """
            update %s.dokument_datei set s3_version = ?
            where s3_schluessel = ? and s3_version = ?""";
    private static final String PDFA_AKTUALISIEREN = """
            update %s.dokument set s3_version_pdfa = ?
            where s3_pdfa = ? and s3_version_pdfa = ?""";

    private final Instance<AgroalDataSource> datenquelle;
    private final MandantAufloesung aufloesung;
    private final MandantKontext kontext;

    @Inject
    public JdbcWiederherstellungsReferenz(Instance<AgroalDataSource> datenquelle, MandantAufloesung aufloesung,
                                          MandantKontext kontext) {
        this.datenquelle = Objects.requireNonNull(datenquelle, "datenquelle");
        this.aufloesung = Objects.requireNonNull(aufloesung, "aufloesung");
        this.kontext = Objects.requireNonNull(kontext, "kontext");
    }

    @Override
    public void versionErsetzt(MandantCode mandant, String schluessel, String alteVersion, String neueVersion) {
        Objects.requireNonNull(mandant, "mandant");
        Objects.requireNonNull(schluessel, "schluessel");
        Objects.requireNonNull(alteVersion, "alteVersion");
        Objects.requireNonNull(neueVersion, "neueVersion");
        if (!(schluessel.startsWith("archiv/") || schluessel.startsWith("eingang/"))
                || alteVersion.equals(neueVersion)) {
            return;
        }
        kontext.mit(mandant, () -> aktualisieren(schluessel, alteVersion, neueVersion));
    }

    private void aktualisieren(String schluessel, String alteVersion, String neueVersion) {
        String schema = aufloesung.schema();
        if (!SCHEMA.matcher(schema).matches()) {
            throw new IllegalStateException("Mandantenschema für die Wiederherstellung ist ungültig");
        }
        try (Connection verbindung = datenquelle.get().getConnection()) {
            verbindung.setAutoCommit(false);
            try {
                aktualisieren(verbindung, DATEI_AKTUALISIEREN.formatted(schema), schluessel, alteVersion, neueVersion);
                aktualisieren(verbindung, PDFA_AKTUALISIEREN.formatted(schema), schluessel, alteVersion, neueVersion);
                verbindung.commit();
            } catch (SQLException | RuntimeException e) {
                try {
                    verbindung.rollback();
                } catch (SQLException rollbackFehler) {
                    e.addSuppressed(rollbackFehler);
                }
                throw e;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Dokumentdateireferenz nach Wiederherstellung nicht aktualisiert", e);
        }
    }

    private static void aktualisieren(Connection verbindung, String sql, String schluessel,
                                      String alteVersion, String neueVersion) throws SQLException {
        try (PreparedStatement befehl = verbindung.prepareStatement(sql)) {
            befehl.setString(1, neueVersion);
            befehl.setString(2, schluessel);
            befehl.setString(3, alteVersion);
            if (befehl.executeUpdate() > 1) {
                throw new IllegalStateException("Mehrere Dokumentdateireferenzen passen zur wiederhergestellten Version");
            }
        }
    }
}
