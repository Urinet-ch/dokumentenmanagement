package ch.urinet.archiv.benutzer.adapter.out.persistence;

import ch.urinet.archiv.benutzer.application.AbgleichStand;
import ch.urinet.archiv.mandant.domain.MandantCode;
import io.agroal.api.AgroalDataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.transaction.Transactional;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@ApplicationScoped
public class JdbcAbgleichStand implements AbgleichStand {

    private final Instance<AgroalDataSource> datenquelle;

    public JdbcAbgleichStand(Instance<AgroalDataSource> datenquelle) {
        this.datenquelle = datenquelle;
    }

    @Override
    public Stand lesen(MandantCode mandant) {
        String schema = mandant.schema();
        try (Connection verbindung = datenquelle.get().getConnection()) {
            return new Stand(verwaltet(verbindung, schema), inhaber(verbindung, schema),
                    offen(verbindung, schema, mandant));
        } catch (SQLException e) {
            throw new IllegalStateException("Verwaltungsstand für Abgleich nicht lesbar", e);
        }
    }

    @Override
    @Transactional
    public boolean unklar(MandantCode mandant, UUID vorgang) {
        String schema = mandant.schema();
        try (Connection verbindung = datenquelle.get().getConnection();
             PreparedStatement sperre = verbindung.prepareStatement(
                     "update public.verwaltung_vorgang set phase = 'UNKLAR' "
                             + "where mandant = ? and vorgang_id = ? and phase = 'ANGEFRAGT'")) {
            sperre.setString(1, mandant.wert());
            sperre.setObject(2, vorgang);
            if (sperre.executeUpdate() == 0) {
                return false;
            }
            String sql = "insert into " + schema + ".zugriff_protokoll "
                    + "(benutzer, rolle, aktion, dokument_id, ip, details, bearbeiter) "
                    + "select p.benutzer, p.rolle, p.aktion, p.dokument_id, p.ip, "
                    + "jsonb_set(p.details, '{phase}', '\"UNKLAR\"'::jsonb), p.bearbeiter from " + schema
                    + ".zugriff_protokoll p where p.details->>'vorgangId' = ? "
                    + "and p.details->>'phase' = 'ANGEFRAGT' "
                    + "and p.zeit <= clock_timestamp() - interval '5 minutes' "
                    + "and not exists (select 1 from " + schema + ".zugriff_protokoll ende "
                    + "where ende.details->>'vorgangId' = p.details->>'vorgangId' "
                    + "and ende.details->>'phase' in ('ABGESCHLOSSEN', 'FEHLGESCHLAGEN', 'UNKLAR'))";
            try (PreparedStatement protokoll = verbindung.prepareStatement(sql)) {
                protokoll.setString(1, vorgang.toString());
                if (protokoll.executeUpdate() != 1) {
                    throw new IllegalStateException("Offener Vorgang nicht eindeutig protokolliert");
                }
            }
            return true;
        } catch (SQLException e) {
            throw new IllegalStateException("Offener Vorgang nicht als ungeklärt festgehalten", e);
        }
    }

    private static Set<UUID> verwaltet(Connection verbindung, String schema) throws SQLException {
        String sql = "select benutzer from (select benutzer from " + schema + ".benutzer_verzeichnis "
                + "where anlass in ('ANLAGE', 'UEBERNAHME') union select benutzer from " + schema
                + ".benutzer_status) bekannte where not exists (select 1 from " + schema + ".zugriff_protokoll p "
                + "where p.aktion = 'BENUTZER_LOESCHEN' and p.details->>'phase' = 'ABGESCHLOSSEN' "
                + "and p.details->>'ziel' = bekannte.benutzer)";
        Set<UUID> ergebnis = new HashSet<>();
        try (PreparedStatement abfrage = verbindung.prepareStatement(sql); ResultSet zeilen = abfrage.executeQuery()) {
            while (zeilen.next()) {
                ergebnis.add(id(zeilen.getString(1)));
            }
        }
        return ergebnis;
    }

    private static Set<UUID> inhaber(Connection verbindung, String schema) throws SQLException {
        String sql = "select ziel from (select distinct on (p.details->>'ziel') p.details->>'ziel' as ziel, "
                + "case when p.aktion = 'BENUTZER_LOESCHEN' then null else p.details->>'rolleNeu' end as rolle "
                + "from " + schema + ".zugriff_protokoll p where p.details->>'phase' = 'ABGESCHLOSSEN' "
                + "and p.details->>'ziel' is not null and (p.aktion in ('ROLLE_ZUWEISEN', 'BENUTZER_LOESCHEN') "
                + "or p.aktion = 'BENUTZER_ANLEGEN' and exists (select 1 from " + schema
                + ".benutzer_verzeichnis v where v.benutzer = p.details->>'ziel' "
                + "and v.anlass = 'ANLAGE' and v.rolle is not null)) "
                + "order by p.details->>'ziel', p.zeit desc, p.id desc) zuletzt where rolle = 'inhaber'";
        Set<UUID> ergebnis = new HashSet<>();
        try (PreparedStatement abfrage = verbindung.prepareStatement(sql); ResultSet zeilen = abfrage.executeQuery()) {
            while (zeilen.next()) {
                ergebnis.add(id(zeilen.getString(1)));
            }
        }
        return ergebnis;
    }

    private static Set<OffenerVorgang> offen(Connection verbindung, String schema, MandantCode mandant)
            throws SQLException {
        String sql = "select p.details->>'vorgangId', p.details->>'ziel' from " + schema + ".zugriff_protokoll p "
                + "where p.aktion in ('BENUTZER_ANLEGEN', 'BENUTZER_AENDERN', 'BENUTZER_LOESCHEN', "
                + "'ROLLE_ZUWEISEN', 'BENUTZER_ANTRAG') and p.details->>'phase' = 'ANGEFRAGT' "
                + "and p.zeit <= clock_timestamp() - interval '5 minutes' "
                + "and (p.aktion <> 'BENUTZER_ANTRAG' or exists (select 1 from public.verwaltung_vorgang v "
                + "where v.mandant = ? and v.vorgang_id::text = p.details->>'vorgangId')) "
                + "and not exists (select 1 from " + schema + ".zugriff_protokoll ende "
                + "where ende.details->>'vorgangId' = p.details->>'vorgangId' "
                + "and ende.details->>'phase' in ('ABGESCHLOSSEN', 'FEHLGESCHLAGEN', 'UNKLAR'))";
        Set<OffenerVorgang> ergebnis = new HashSet<>();
        try (PreparedStatement abfrage = verbindung.prepareStatement(sql)) {
            abfrage.setString(1, mandant.wert());
            try (ResultSet zeilen = abfrage.executeQuery()) {
                while (zeilen.next()) {
                    String ziel = zeilen.getString(2);
                    ergebnis.add(new OffenerVorgang(id(zeilen.getString(1)),
                            ziel == null ? Optional.empty() : Optional.of(id(ziel))));
                }
            }
        }
        return ergebnis;
    }

    private static UUID id(String text) {
        try {
            UUID id = UUID.fromString(text);
            if (!id.toString().equalsIgnoreCase(text)) {
                throw new IllegalArgumentException("Ungültiges UUID-Format");
            }
            return id;
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new IllegalStateException("Ungültige Kennung im Verwaltungsstand", e);
        }
    }
}
