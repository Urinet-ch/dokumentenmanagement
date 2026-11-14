package ch.urinet.archiv.kontakt.adapter.out.persistence;

import ch.urinet.archiv.kontakt.application.KontaktUebernahme;
import ch.urinet.archiv.kontakt.domain.Kontakt;
import ch.urinet.archiv.kontakt.domain.KontaktAngaben;
import ch.urinet.archiv.kontakt.domain.Verkaufschance;
import ch.urinet.archiv.mandant.application.MandantAufloesung;
import io.agroal.api.AgroalDataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.LocalDate;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Schreibt die Übernahme des CSV-Imports per JDBC in {@code m_<code>.kontakt} und {@code m_<code>.verkaufschance}
 * (Aufgabe 4.3), Schema wie {@link JdbcKontaktRegister} aus der Anmeldung bzw. dem Mandanten-Kontext. Nur in einer
 * Transaktion; ein {@code delete} gibt es nicht (Req 13.7).
 */
@ApplicationScoped
public class JdbcKontaktUebernahme implements KontaktUebernahme {

    private static final String VK_SPALTEN = "id, crm_id, kontakt_id, titel, phase, betrag, waehrung, datum, import_lauf";

    private final Instance<AgroalDataSource> datenquelle;
    private final MandantAufloesung aufloesung;

    public JdbcKontaktUebernahme(Instance<AgroalDataSource> datenquelle, MandantAufloesung aufloesung) {
        this.datenquelle = datenquelle;
        this.aufloesung = aufloesung;
    }

    @Override
    public Optional<Kontakt> sperren(UUID id) {
        JdbcKontaktRegister.transaktionPruefen();
        String sql = "select " + JdbcKontaktRegister.SPALTEN + " from " + schema() + ".kontakt where id = ? for update";
        try (Connection verbindung = datenquelle.get().getConnection();
             PreparedStatement abfrage = verbindung.prepareStatement(sql)) {
            abfrage.setObject(1, id);
            try (ResultSet zeile = abfrage.executeQuery()) {
                return zeile.next() ? Optional.of(JdbcKontaktRegister.kontakt(zeile)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Kontakt nicht gesperrt", e);
        }
    }

    @Override
    public Optional<UUID> anlegen(String crmId, KontaktAngaben angaben, String nameNormalisiert) {
        JdbcKontaktRegister.transaktionPruefen();
        String sql = "insert into " + schema() + ".kontakt (id, typ, name, vorname, strasse, plz, ort, email, telefon, "
                + "uid, iban, kategorie, name_normalisiert, quelle, crm_id) "
                + "values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) on conflict (crm_id) do nothing returning id";
        try (Connection verbindung = datenquelle.get().getConnection();
             PreparedStatement einfuegen = verbindung.prepareStatement(sql)) {
            einfuegen.setObject(1, UUID.randomUUID());
            int i = JdbcKontaktRegister.felder(einfuegen, 2, angaben, nameNormalisiert);
            einfuegen.setString(i++, QUELLE_CSV_IMPORT);
            einfuegen.setString(i, crmId);
            try (ResultSet zeile = einfuegen.executeQuery()) {
                return zeile.next() ? Optional.of(zeile.getObject(1, UUID.class)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Kontakt nicht angelegt", e);
        }
    }

    @Override
    public void aktualisieren(UUID id, String crmId, KontaktAngaben angaben, String nameNormalisiert) {
        JdbcKontaktRegister.transaktionPruefen();
        String tabelle = schema() + ".kontakt";
        String sql = "update " + tabelle + " k set typ = ?, name = ?, vorname = ?, strasse = ?, plz = ?, ort = ?, "
                + "email = ?, telefon = ?, uid = ?, iban = ?, kategorie = ?, name_normalisiert = ?, "
                + "crm_id = case when k.crm_id is null and ?::text is not null and not exists "
                + "(select 1 from " + tabelle + " a where a.crm_id = ?::text) then ?::text else k.crm_id end, "
                + "geaendert = now(), version = k.version + 1 where k.id = ?";
        try (Connection verbindung = datenquelle.get().getConnection();
             PreparedStatement aendern = verbindung.prepareStatement(sql)) {
            int i = JdbcKontaktRegister.felder(aendern, 1, angaben, nameNormalisiert);
            aendern.setString(i++, crmId);
            aendern.setString(i++, crmId);
            aendern.setString(i++, crmId);
            aendern.setObject(i, id);
            if (aendern.executeUpdate() != 1) {
                throw new IllegalStateException("Kontakt nicht geändert: " + id);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Kontakt nicht geändert", e);
        }
    }

    @Override
    public int exportAbgleichen(Collection<String> imExport) {
        JdbcKontaktRegister.transaktionPruefen();
        String tabelle = schema() + ".kontakt";
        try (Connection verbindung = datenquelle.get().getConnection();
             PreparedStatement kennzeichnen = verbindung.prepareStatement("update " + tabelle
                     + " set nicht_im_export = true, geaendert = now() "
                     + "where aktiv and crm_id is not null and not nicht_im_export and not (crm_id = any(?))");
             PreparedStatement zuruecksetzen = verbindung.prepareStatement("update " + tabelle
                     + " set nicht_im_export = false, geaendert = now() where aktiv and nicht_im_export "
                     + "and crm_id = any(?)")) {
            var ids = verbindung.createArrayOf("text", imExport.toArray());
            kennzeichnen.setArray(1, ids);
            int gekennzeichnet = kennzeichnen.executeUpdate();
            zuruecksetzen.setArray(1, ids);
            zuruecksetzen.executeUpdate();
            return gekennzeichnet;
        } catch (SQLException e) {
            throw new IllegalStateException("Kennzeichnung «nicht mehr im CRM-Export» nicht gesetzt", e);
        }
    }

    @Override
    public Set<UUID> inaktive(Collection<UUID> ids) {
        Set<UUID> ergebnis = new HashSet<>();
        if (ids.isEmpty()) {
            return ergebnis;
        }
        JdbcKontaktRegister.transaktionPruefen();
        String sql = "select id from " + schema() + ".kontakt where not aktiv and id = any(?)";
        try (Connection verbindung = datenquelle.get().getConnection();
             PreparedStatement abfrage = verbindung.prepareStatement(sql)) {
            abfrage.setArray(1, verbindung.createArrayOf("uuid", ids.toArray()));
            try (ResultSet zeile = abfrage.executeQuery()) {
                while (zeile.next()) {
                    ergebnis.add(zeile.getObject(1, UUID.class));
                }
            }
            return ergebnis;
        } catch (SQLException e) {
            throw new IllegalStateException("Kontakte nicht lesbar", e);
        }
    }

    @Override
    public Map<String, UUID> kontakteMitCrmId(Collection<String> crmIds) {
        Map<String, UUID> ergebnis = new HashMap<>();
        if (crmIds.isEmpty()) {
            return ergebnis;
        }
        JdbcKontaktRegister.transaktionPruefen();
        String sql = "select crm_id, id from " + schema() + ".kontakt where aktiv and crm_id = any(?)";
        try (Connection verbindung = datenquelle.get().getConnection();
             PreparedStatement abfrage = verbindung.prepareStatement(sql)) {
            abfrage.setArray(1, verbindung.createArrayOf("text", crmIds.toArray()));
            try (ResultSet zeile = abfrage.executeQuery()) {
                while (zeile.next()) {
                    ergebnis.put(zeile.getString(1), zeile.getObject(2, UUID.class));
                }
            }
            return ergebnis;
        } catch (SQLException e) {
            throw new IllegalStateException("Kontakte nicht lesbar", e);
        }
    }

    @Override
    public Map<String, Verkaufschance> verkaufschancen(Collection<String> crmIds) {
        Map<String, Verkaufschance> ergebnis = new HashMap<>();
        if (crmIds.isEmpty()) {
            return ergebnis;
        }
        JdbcKontaktRegister.transaktionPruefen();
        String sql = "select " + VK_SPALTEN + " from " + schema() + ".verkaufschance where crm_id = any(?)";
        try (Connection verbindung = datenquelle.get().getConnection();
             PreparedStatement abfrage = verbindung.prepareStatement(sql)) {
            abfrage.setArray(1, verbindung.createArrayOf("text", crmIds.toArray()));
            try (ResultSet zeile = abfrage.executeQuery()) {
                while (zeile.next()) {
                    Verkaufschance chance = verkaufschance(zeile);
                    ergebnis.put(chance.crmId(), chance);
                }
            }
            return ergebnis;
        } catch (SQLException e) {
            throw new IllegalStateException("Verkaufschancen nicht lesbar", e);
        }
    }

    @Override
    public boolean verkaufschanceSpeichern(Verkaufschance chance) {
        JdbcKontaktRegister.transaktionPruefen();
        String sql = "insert into " + schema() + ".verkaufschance as v (" + VK_SPALTEN + ") "
                + "values (?, ?, ?, ?, ?, ?, ?, ?, ?) on conflict (crm_id) do update set "
                + "kontakt_id = excluded.kontakt_id, titel = coalesce(excluded.titel, v.titel), "
                + "phase = coalesce(excluded.phase, v.phase), betrag = coalesce(excluded.betrag, v.betrag), "
                + "datum = coalesce(excluded.datum, v.datum), import_lauf = excluded.import_lauf "
                + "returning (xmax = 0) as neu";
        try (Connection verbindung = datenquelle.get().getConnection();
             PreparedStatement speichern = verbindung.prepareStatement(sql)) {
            speichern.setObject(1, chance.id());
            speichern.setString(2, chance.crmId());
            speichern.setObject(3, chance.kontaktId());
            speichern.setString(4, chance.titel());
            speichern.setString(5, chance.phase());
            speichern.setBigDecimal(6, chance.betrag());
            speichern.setString(7, chance.waehrung());
            speichern.setObject(8, chance.datum(), Types.DATE);
            speichern.setObject(9, chance.importLauf());
            try (ResultSet zeile = speichern.executeQuery()) {
                zeile.next();
                return zeile.getBoolean(1);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Verkaufschance nicht gespeichert", e);
        }
    }

    private static Verkaufschance verkaufschance(ResultSet zeile) throws SQLException {
        return new Verkaufschance(zeile.getObject("id", UUID.class), zeile.getString("crm_id"),
                zeile.getObject("kontakt_id", UUID.class), zeile.getString("titel"), zeile.getString("phase"),
                zeile.getBigDecimal("betrag"), zeile.getString("waehrung"), zeile.getObject("datum", LocalDate.class),
                zeile.getObject("import_lauf", UUID.class));
    }

    private String schema() {
        String schema = aufloesung.schema();
        if (!JdbcKontaktRegister.SCHEMA.matcher(schema).matches()) {
            throw new IllegalStateException("Kontakte ohne Mandanten-Schema");
        }
        return schema;
    }
}
