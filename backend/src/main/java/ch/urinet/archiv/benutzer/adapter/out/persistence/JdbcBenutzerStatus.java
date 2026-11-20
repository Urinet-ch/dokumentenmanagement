package ch.urinet.archiv.benutzer.adapter.out.persistence;

import ch.urinet.archiv.benutzer.application.BenutzerStatusSpeicher;
import ch.urinet.archiv.benutzer.domain.BenutzerStatus;
import ch.urinet.archiv.mandant.application.MandantAufloesung;
import io.agroal.api.AgroalDataSource;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

@ApplicationScoped
public class JdbcBenutzerStatus implements BenutzerStatusSpeicher {

    private static final Pattern SCHEMA = Pattern.compile("^m_[a-z0-9_]+$");
    private final Instance<AgroalDataSource> datenquelle;
    private final MandantAufloesung aufloesung;

    public JdbcBenutzerStatus(Instance<AgroalDataSource> datenquelle, MandantAufloesung aufloesung) {
        this.datenquelle = datenquelle;
        this.aufloesung = aufloesung;
    }

    @Override
    public Optional<Eintrag> lesen(String benutzer) {
        benutzerPruefen(benutzer);
        String sql = "select art, seit, grund, von from " + schema() + ".benutzer_status where benutzer = ?";
        try (Connection verbindung = datenquelle.get().getConnection();
             PreparedStatement abfrage = verbindung.prepareStatement(sql)) {
            abfrage.setString(1, benutzer);
            try (ResultSet zeile = abfrage.executeQuery()) {
                return zeile.next() ? Optional.of(new Eintrag(BenutzerStatus.valueOf(zeile.getString("art")),
                        zeile.getObject("seit", OffsetDateTime.class).toInstant(),
                        zeile.getString("grund"), zeile.getString("von"))) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Benutzerstatus nicht lesbar", e);
        }
    }

    @Override
    public void setzen(String benutzer, Eintrag eintrag) {
        benutzerPruefen(benutzer);
        Objects.requireNonNull(eintrag);
        transaktionPruefen();
        String sql = "insert into " + schema() + ".benutzer_status (benutzer, art, seit, grund, von) "
                + "values (?, ?, ?, ?, ?) on conflict (benutzer) do update set "
                + "art = excluded.art, seit = excluded.seit, grund = excluded.grund, von = excluded.von";
        try (Connection verbindung = datenquelle.get().getConnection();
             PreparedStatement befehl = verbindung.prepareStatement(sql)) {
            befehl.setString(1, benutzer);
            befehl.setString(2, eintrag.status().name());
            befehl.setObject(3, eintrag.seit().atOffset(ZoneOffset.UTC));
            befehl.setString(4, eintrag.grund());
            befehl.setString(5, eintrag.von());
            befehl.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Benutzerstatus nicht gespeichert", e);
        }
    }

    @Override
    public boolean entfernen(String benutzer) {
        benutzerPruefen(benutzer);
        transaktionPruefen();
        String sql = "delete from " + schema() + ".benutzer_status where benutzer = ?";
        try (Connection verbindung = datenquelle.get().getConnection();
             PreparedStatement befehl = verbindung.prepareStatement(sql)) {
            befehl.setString(1, benutzer);
            return befehl.executeUpdate() == 1;
        } catch (SQLException e) {
            throw new IllegalStateException("Benutzerstatus nicht entfernt", e);
        }
    }

    private String schema() {
        String schema = aufloesung.schema();
        if (!SCHEMA.matcher(schema).matches()) {
            throw new IllegalStateException("Benutzerstatus ohne Mandanten-Schema");
        }
        return schema;
    }

    private static void transaktionPruefen() {
        if (!QuarkusTransaction.isActive()) {
            throw new IllegalStateException("Benutzerstatus nur in einer Transaktion ändern");
        }
    }

    private static void benutzerPruefen(String benutzer) {
        if (benutzer == null || benutzer.isBlank()) {
            throw new IllegalArgumentException("Benutzerkennung ist Pflicht");
        }
    }
}
