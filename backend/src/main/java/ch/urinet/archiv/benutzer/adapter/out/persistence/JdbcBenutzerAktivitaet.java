package ch.urinet.archiv.benutzer.adapter.out.persistence;

import ch.urinet.archiv.benutzer.application.BenutzerAktivitaet;
import ch.urinet.archiv.mandant.application.MandantAufloesung;
import io.agroal.api.AgroalDataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.regex.Pattern;

@ApplicationScoped
public class JdbcBenutzerAktivitaet implements BenutzerAktivitaet {
    private static final Pattern SCHEMA = Pattern.compile("^m_[a-z0-9_]+$");
    private final Instance<AgroalDataSource> datenquelle;
    private final MandantAufloesung aufloesung;

    public JdbcBenutzerAktivitaet(Instance<AgroalDataSource> datenquelle, MandantAufloesung aufloesung) {
        this.datenquelle = datenquelle;
        this.aufloesung = aufloesung;
    }

    @Override
    public Angaben lesen(String benutzer) {
        String schema = aufloesung.schema();
        if (!SCHEMA.matcher(schema).matches()) {
            throw new IllegalStateException("Benutzeraktivität ohne Mandanten-Schema");
        }
        String sql = "select (select zeit from " + schema + ".zugriff_protokoll "
                + "where details->>'ziel' = ? and details->>'phase' = 'ABGESCHLOSSEN' "
                + "and details->>'vorgang' in ('ANLEGEN', 'REAKTIVIEREN', 'EINLADUNG_ERNEUT') "
                + "order by zeit desc, id desc limit 1) as einladung, "
                + "(select zeit from " + schema + ".zugriff_protokoll where benutzer = ? "
                + "order by zeit desc, id desc limit 1) as letzte_zeit, "
                + "(select aktion from " + schema + ".zugriff_protokoll where benutzer = ? "
                + "order by zeit desc, id desc limit 1) as letzte_aktion";
        try (Connection verbindung = datenquelle.get().getConnection();
             PreparedStatement abfrage = verbindung.prepareStatement(sql)) {
            abfrage.setString(1, benutzer);
            abfrage.setString(2, benutzer);
            abfrage.setString(3, benutzer);
            try (ResultSet zeile = abfrage.executeQuery()) {
                if (!zeile.next()) {
                    throw new IllegalStateException("Benutzeraktivität nicht lesbar");
                }
                OffsetDateTime einladung = zeile.getObject("einladung", OffsetDateTime.class);
                OffsetDateTime zeit = zeile.getObject("letzte_zeit", OffsetDateTime.class);
                LetzteAktion letzte = zeit == null ? null : new LetzteAktion(zeit.toInstant(),
                        zeile.getString("letzte_aktion"));
                return new Angaben(einladung == null ? null : einladung.toInstant(), letzte);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Benutzeraktivität nicht lesbar", e);
        }
    }
}
