package ch.urinet.archiv.kontakt.adapter.out.persistence;

import ch.urinet.archiv.kontakt.application.KontaktVerweise;
import ch.urinet.archiv.mandant.application.MandantAufloesung;
import io.agroal.api.AgroalDataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

/** {@link KontaktVerweise} per JDBC im Schema des Mandanten (immer qualifiziert), in der Transaktion des Aufrufers. */
@ApplicationScoped
public class JdbcKontaktVerweise implements KontaktVerweise {

    private final Instance<AgroalDataSource> datenquelle;
    private final MandantAufloesung aufloesung;

    public JdbcKontaktVerweise(Instance<AgroalDataSource> datenquelle, MandantAufloesung aufloesung) {
        this.datenquelle = datenquelle;
        this.aufloesung = aufloesung;
    }

    @Override
    public Zahlen zaehlen(UUID kontakt) {
        String schema = schema();
        String sql = "select (select count(*) from " + schema + ".dokument where partner_id = ?), "
                + "(select count(*) from " + schema + ".verkaufschance where kontakt_id = ?), "
                + "(select count(*) from " + schema + ".ki_beispiel where partner_id = ?)";
        try (Connection verbindung = datenquelle.get().getConnection();
             PreparedStatement abfrage = verbindung.prepareStatement(sql)) {
            abfrage.setObject(1, kontakt);
            abfrage.setObject(2, kontakt);
            abfrage.setObject(3, kontakt);
            try (ResultSet zeile = abfrage.executeQuery()) {
                zeile.next();
                return new Zahlen(zeile.getInt(1), zeile.getInt(2), zeile.getInt(3));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Verweise nicht gezählt", e);
        }
    }

    @Override
    public Zahlen umhaengen(UUID quelle, UUID ziel) {
        JdbcKontaktRegister.transaktionPruefen();
        String schema = schema();
        try (Connection verbindung = datenquelle.get().getConnection()) {
            int verkaufschancen = umhaengen(verbindung, schema + ".verkaufschance", "kontakt_id", quelle, ziel);
            int korrekturbeispiele = umhaengen(verbindung, schema + ".ki_beispiel", "partner_id", quelle, ziel);
            umhaengen(verbindung, schema + ".dokument", "ki_partner_id", quelle, ziel);
            return new Zahlen(0, verkaufschancen, korrekturbeispiele);
        } catch (SQLException e) {
            throw new IllegalStateException("Verweise nicht umgehängt", e);
        }
    }

    private static int umhaengen(Connection verbindung, String tabelle, String spalte, UUID quelle, UUID ziel)
            throws SQLException {
        try (PreparedStatement aendern = verbindung.prepareStatement(
                "update " + tabelle + " set " + spalte + " = ? where " + spalte + " = ?")) {
            aendern.setObject(1, ziel);
            aendern.setObject(2, quelle);
            return aendern.executeUpdate();
        }
    }

    private String schema() {
        String schema = aufloesung.schema();
        if (!JdbcKontaktRegister.SCHEMA.matcher(schema).matches()) {
            throw new IllegalStateException("Verweise ohne Mandanten-Schema");
        }
        return schema;
    }
}
