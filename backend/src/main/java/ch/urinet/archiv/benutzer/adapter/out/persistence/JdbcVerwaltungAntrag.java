package ch.urinet.archiv.benutzer.adapter.out.persistence;

import ch.urinet.archiv.benutzer.application.VerwaltungAntragSpeicher;
import ch.urinet.archiv.mandant.application.MandantAufloesung;
import ch.urinet.archiv.sicherheit.domain.Rolle;
import io.agroal.api.AgroalDataSource;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

@ApplicationScoped
public class JdbcVerwaltungAntrag implements VerwaltungAntragSpeicher {

    private static final Pattern SCHEMA = Pattern.compile("^m_[a-z0-9_]+$");
    private final Instance<AgroalDataSource> datenquelle;
    private final MandantAufloesung aufloesung;

    public JdbcVerwaltungAntrag(Instance<AgroalDataSource> datenquelle, MandantAufloesung aufloesung) {
        this.datenquelle = datenquelle;
        this.aufloesung = aufloesung;
    }

    @Override
    public void anlegen(Antrag antrag) {
        Objects.requireNonNull(antrag);
        if (antrag.status() != Status.OFFEN) {
            throw new IllegalArgumentException("Neuer Antrag muss offen sein");
        }
        transaktionPruefen();
        String sql = "insert into " + schema() + ".verwaltung_antrag "
                + "(id, art, ziel, rolle, grund, beantragt_von, beantragt_am, gueltig_bis, status) "
                + "values (?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (Connection verbindung = datenquelle.get().getConnection();
             PreparedStatement befehl = verbindung.prepareStatement(sql)) {
            befehl.setObject(1, antrag.id());
            befehl.setString(2, antrag.art().name());
            befehl.setString(3, antrag.ziel());
            befehl.setString(4, antrag.rolle() == null ? null : antrag.rolle().code());
            befehl.setString(5, antrag.grund());
            befehl.setString(6, antrag.beantragtVon());
            befehl.setObject(7, antrag.beantragtAm().atOffset(ZoneOffset.UTC));
            befehl.setObject(8, antrag.gueltigBis().atOffset(ZoneOffset.UTC));
            befehl.setString(9, antrag.status().name());
            befehl.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Antrag nicht gespeichert", e);
        }
    }

    @Override
    public Optional<Antrag> lesen(UUID id) {
        Objects.requireNonNull(id);
        String sql = "select art, ziel, rolle, grund, beantragt_von, beantragt_am, gueltig_bis, "
                + "status, entschieden_von, entschieden_am from " + schema() + ".verwaltung_antrag where id = ?";
        try (Connection verbindung = datenquelle.get().getConnection();
             PreparedStatement abfrage = verbindung.prepareStatement(sql)) {
            abfrage.setObject(1, id);
            try (ResultSet zeile = abfrage.executeQuery()) {
                if (!zeile.next()) {
                    return Optional.empty();
                }
                String rolle = zeile.getString("rolle");
                OffsetDateTime entschiedenAm = zeile.getObject("entschieden_am", OffsetDateTime.class);
                return Optional.of(new Antrag(id, Art.valueOf(zeile.getString("art")), zeile.getString("ziel"),
                        rolle == null ? null : Rolle.valueOf(rolle.toUpperCase(Locale.ROOT)), zeile.getString("grund"),
                        zeile.getString("beantragt_von"), zeile.getObject("beantragt_am", OffsetDateTime.class).toInstant(),
                        zeile.getObject("gueltig_bis", OffsetDateTime.class).toInstant(),
                        Status.valueOf(zeile.getString("status")), zeile.getString("entschieden_von"),
                        entschiedenAm == null ? null : entschiedenAm.toInstant()));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Antrag nicht lesbar", e);
        }
    }

    @Override
    public boolean entscheiden(UUID id, Status status, String akteur, Instant zeit) {
        Objects.requireNonNull(id);
        Objects.requireNonNull(status);
        Objects.requireNonNull(zeit);
        if (status == Status.OFFEN || (status != Status.ABGELAUFEN && (akteur == null || akteur.isBlank()))) {
            throw new IllegalArgumentException("Antragsentscheid ist ungültig");
        }
        transaktionPruefen();
        String bedingung = switch (status) {
            case BESTAETIGT, ABGELEHNT -> "gueltig_bis > ? and beantragt_von <> ?";
            case ZURUECKGEZOGEN -> "gueltig_bis > ? and beantragt_von = ?";
            case ABGELAUFEN -> "gueltig_bis <= ?";
            case OFFEN -> throw new IllegalArgumentException("Antragsentscheid ist ungültig");
        };
        String sql = "update " + schema() + ".verwaltung_antrag set status = ?, entschieden_von = ?, "
                + "entschieden_am = ? where id = ? and status = 'OFFEN' and beantragt_am <= ? and " + bedingung;
        try (Connection verbindung = datenquelle.get().getConnection();
             PreparedStatement befehl = verbindung.prepareStatement(sql)) {
            befehl.setString(1, status.name());
            befehl.setString(2, akteur);
            befehl.setObject(3, zeit.atOffset(ZoneOffset.UTC));
            befehl.setObject(4, id);
            befehl.setObject(5, zeit.atOffset(ZoneOffset.UTC));
            befehl.setObject(6, zeit.atOffset(ZoneOffset.UTC));
            if (status != Status.ABGELAUFEN) {
                befehl.setString(7, akteur);
            }
            return befehl.executeUpdate() == 1;
        } catch (SQLException e) {
            throw new IllegalStateException("Antrag nicht entschieden", e);
        }
    }

    private String schema() {
        String schema = aufloesung.schema();
        if (!SCHEMA.matcher(schema).matches()) {
            throw new IllegalStateException("Antrag ohne Mandanten-Schema");
        }
        return schema;
    }

    private static void transaktionPruefen() {
        if (!QuarkusTransaction.isActive()) {
            throw new IllegalStateException("Antrag nur in einer Transaktion ändern");
        }
    }
}
