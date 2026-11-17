package ch.urinet.archiv.mandant.adapter.out.persistence;

import ch.urinet.archiv.ablage.domain.AbgelegteDatei;
import ch.urinet.archiv.mandant.application.MandantAufloesung;
import ch.urinet.archiv.mandant.application.MandantVollexportRepository;
import io.agroal.api.AgroalDataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/** Vollexport-Status im Mandanten-Schema des aktuellen Kontextes. */
@ApplicationScoped
public class JdbcMandantVollexportRepository implements MandantVollexportRepository {

    private static final int ZEITGRENZE_S = 30;
    private static final Pattern SCHEMA = Pattern.compile("^m_[a-z0-9_]+$");
    private static final String SPALTEN = "id, status, s3_schluessel, s3_version, sha256, groesse, anzahl, erstellt, fertig";

    private final Instance<AgroalDataSource> datenquelle;
    private final MandantAufloesung aufloesung;

    public JdbcMandantVollexportRepository(Instance<AgroalDataSource> datenquelle, MandantAufloesung aufloesung) {
        this.datenquelle = datenquelle;
        this.aufloesung = aufloesung;
    }

    @Override
    public boolean anlegen(UUID id) {
        return aendern("insert into " + schema() + ".mandant_vollexport (id, status) values (?, 'OFFEN') "
                + "on conflict do nothing", id) == 1;
    }

    @Override
    public Optional<Vollexport> laden(UUID id) {
        try (Connection verbindung = datenquelle.get().getConnection();
             PreparedStatement abfrage = verbindung.prepareStatement(
                     "select " + SPALTEN + " from " + schema() + ".mandant_vollexport where id = ?")) {
            abfrage.setQueryTimeout(ZEITGRENZE_S);
            abfrage.setObject(1, id, Types.OTHER);
            try (ResultSet zeile = abfrage.executeQuery()) {
                if (!zeile.next()) {
                    return Optional.empty();
                }
                long groesse = zeile.getLong("groesse");
                Optional<Long> mitGroesse = zeile.wasNull() ? Optional.empty() : Optional.of(groesse);
                int anzahl = zeile.getInt("anzahl");
                Optional<Integer> mitAnzahl = zeile.wasNull() ? Optional.empty() : Optional.of(anzahl);
                Timestamp fertig = zeile.getTimestamp("fertig");
                return Optional.of(new Vollexport(zeile.getObject("id", UUID.class),
                        Status.valueOf(zeile.getString("status")),
                        Optional.ofNullable(zeile.getString("s3_schluessel")),
                        Optional.ofNullable(zeile.getString("s3_version")),
                        Optional.ofNullable(zeile.getString("sha256")), mitGroesse, mitAnzahl,
                        zeile.getTimestamp("erstellt").toInstant(),
                        Optional.ofNullable(fertig).map(Timestamp::toInstant)));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Mandanten-Vollexport nicht gelesen", e);
        }
    }

    @Override
    public Bestand bestand() {
        try (Connection verbindung = datenquelle.get().getConnection();
             PreparedStatement abfrage = verbindung.prepareStatement(
                     "select count(*), greatest("
                             + "(select max(geaendert) from " + schema() + ".dokument), "
                             + "(select max(empfangen) from " + schema() + ".dokument_datei), "
                             + "(select max(zeit) from " + schema() + ".dokument_version)) from "
                             + schema() + ".dokument")) {
            abfrage.setQueryTimeout(ZEITGRENZE_S);
            try (ResultSet zeile = abfrage.executeQuery()) {
                zeile.next();
                int dokumente = zeile.getInt(1);
                Timestamp letzte = zeile.getTimestamp(2);
                return new Bestand(dokumente, Optional.ofNullable(letzte).map(Timestamp::toInstant));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Dokumentbestand für Vollexport nicht gelesen", e);
        }
    }

    @Override
    public boolean fertig(UUID id, AbgelegteDatei zip, int anzahlDokumente) {
        if (anzahlDokumente < 0) {
            throw new IllegalArgumentException("Dokumentanzahl darf nicht negativ sein");
        }
        return aendern("update " + schema() + ".mandant_vollexport set status = 'FERTIG', s3_schluessel = ?, "
                        + "s3_version = ?, sha256 = ?, groesse = ?, anzahl = ?, fertig = clock_timestamp() "
                        + "where id = ? and status = 'OFFEN'",
                zip.schluessel(), zip.versionId(), zip.sha256(), zip.groesse(), anzahlDokumente, id) == 1;
    }

    @Override
    public boolean fehler(UUID id, UUID fehlerId) {
        return aendern("update " + schema() + ".mandant_vollexport set status = 'FEHLER', fehler_id = ? "
                + "where id = ? and status = 'OFFEN'", fehlerId, id) == 1;
    }

    private String schema() {
        String schema = aufloesung.schema();
        if (!SCHEMA.matcher(schema).matches()) {
            throw new IllegalStateException("Vollexport ohne Mandanten-Schema");
        }
        return schema;
    }

    private int aendern(String sql, Object... werte) {
        try (Connection verbindung = datenquelle.get().getConnection();
             PreparedStatement befehl = verbindung.prepareStatement(sql)) {
            befehl.setQueryTimeout(ZEITGRENZE_S);
            for (int i = 0; i < werte.length; i++) {
                Object wert = werte[i];
                if (wert instanceof UUID uuid) {
                    befehl.setObject(i + 1, uuid, Types.OTHER);
                } else {
                    befehl.setObject(i + 1, wert);
                }
            }
            return befehl.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Mandanten-Vollexport nicht geschrieben", e);
        }
    }
}
