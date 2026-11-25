package ch.urinet.archiv.benutzer.adapter.out.persistence;

import ch.urinet.archiv.benutzer.application.BenutzerAktivitaet;
import ch.urinet.archiv.benutzer.application.BenutzerEinladungSpeicher;
import ch.urinet.archiv.mandant.application.MandantAufloesung;
import io.agroal.api.AgroalDataSource;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.regex.Pattern;

@ApplicationScoped
public class JdbcBenutzerAktivitaet implements BenutzerAktivitaet, BenutzerEinladungSpeicher {
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
                + "(select gueltig_bis from " + schema + ".benutzer_einladung where benutzer = ?) "
                + "as einladung_gueltig_bis, "
                + "(select zeit from " + schema + ".zugriff_protokoll where benutzer = ? "
                + "order by zeit desc, id desc limit 1) as letzte_zeit, "
                + "(select aktion from " + schema + ".zugriff_protokoll where benutzer = ? "
                + "order by zeit desc, id desc limit 1) as letzte_aktion";
        try (Connection verbindung = datenquelle.get().getConnection();
             PreparedStatement abfrage = verbindung.prepareStatement(sql)) {
            abfrage.setString(1, benutzer);
            abfrage.setString(2, benutzer);
            abfrage.setString(3, benutzer);
            abfrage.setString(4, benutzer);
            try (ResultSet zeile = abfrage.executeQuery()) {
                if (!zeile.next()) {
                    throw new IllegalStateException("Benutzeraktivität nicht lesbar");
                }
                OffsetDateTime einladung = zeile.getObject("einladung", OffsetDateTime.class);
                OffsetDateTime ablauf = zeile.getObject("einladung_gueltig_bis", OffsetDateTime.class);
                OffsetDateTime zeit = zeile.getObject("letzte_zeit", OffsetDateTime.class);
                LetzteAktion letzte = zeit == null ? null : new LetzteAktion(zeit.toInstant(),
                        zeile.getString("letzte_aktion"));
                return new Angaben(einladung == null ? null : einladung.toInstant(),
                        ablauf == null ? null : ablauf.toInstant(), letzte);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Benutzeraktivität nicht lesbar", e);
        }
    }

    @Override
    public void speichern(String benutzer, Duration gueltigkeit) {
        if (benutzer == null || benutzer.isBlank()) {
            throw new IllegalArgumentException("Benutzerkennung ist Pflicht");
        }
        Objects.requireNonNull(gueltigkeit);
        if (gueltigkeit.isZero() || gueltigkeit.isNegative() || gueltigkeit.compareTo(Duration.ofDays(7)) > 0) {
            throw new IllegalArgumentException("Ungültige Einladungsgültigkeit");
        }
        if (!QuarkusTransaction.isActive()) {
            throw new IllegalStateException("Einladungsablauf nur in einer Transaktion speichern");
        }
        Instant versendetAm = Instant.now();
        Instant gueltigBis = versendetAm.plus(gueltigkeit);
        String sql = "insert into " + schema() + ".benutzer_einladung (benutzer, versendet_am, gueltig_bis) "
                + "values (?, ?, ?) on conflict (benutzer) do update set "
                + "versendet_am = excluded.versendet_am, gueltig_bis = excluded.gueltig_bis";
        try (Connection verbindung = datenquelle.get().getConnection();
             PreparedStatement befehl = verbindung.prepareStatement(sql)) {
            befehl.setString(1, benutzer);
            befehl.setObject(2, versendetAm.atOffset(ZoneOffset.UTC));
            befehl.setObject(3, gueltigBis.atOffset(ZoneOffset.UTC));
            befehl.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Einladungsablauf nicht gespeichert", e);
        }
    }

    private String schema() {
        String schema = aufloesung.schema();
        if (!SCHEMA.matcher(schema).matches()) {
            throw new IllegalStateException("Benutzereinladung ohne Mandanten-Schema");
        }
        return schema;
    }
}
