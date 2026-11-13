package ch.urinet.archiv.dokument;

import ch.urinet.archiv.mandant.domain.MandantCode;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Testdokumente per JDBC, bis Erfassung (Welle 3) und Bestätigung echte Dokumente anlegen. Die Probe-Dokumente
 * «NORMAL» und «INTERN» liegen bestätigt im Mandanten {@code urinet}; «UNBEKANNT» gibt es nirgends.
 */
public final class Testdokumente {

    public static final Map<String, UUID> NAMEN = Map.of(
            "NORMAL", UUID.fromString("00000000-0000-4000-8000-000000000001"),
            "INTERN", UUID.fromString("00000000-0000-4000-8000-000000000002"),
            "UNBEKANNT", UUID.fromString("00000000-0000-4000-8000-0000000000ff"));

    private static final List<String> TABELLEN = List.of("dokument_version", "dokument_datei", "dokument_finanz",
            "dokument_vertrag", "belegnummer_eigen", "ki_beispiel", "dokument");

    /** Angaben eines Testdokuments; {@code bestaetigt = false} heisst Art unbestätigt. */
    public record Angaben(String mandant, String art, String vertraulichkeit, boolean bestaetigt, String erstelltVon,
                          String personBenutzer, String status) {
    }

    private Testdokumente() {
    }

    /** Legt die Probe-Dokumente «NORMAL» und «INTERN» im Mandanten {@code urinet} an, falls sie fehlen. */
    public static void probeDokumenteAnlegen(DataSource datenquelle) throws SQLException {
        for (String stufe : List.of("NORMAL", "INTERN")) {
            einfuegen(datenquelle, NAMEN.get(stufe),
                    new Angaben("urinet", null, stufe, true, "probe", null, "ARCHIVIERT"), true);
        }
    }

    public static UUID anlegen(DataSource datenquelle, Angaben angaben) throws SQLException {
        UUID id = UUID.randomUUID();
        einfuegen(datenquelle, id, angaben, false);
        return id;
    }

    /**
     * Entfernt Testdokumente samt Versionen, Dateien und Belegnummern. {@code dokument_version} ist nur Einfügen;
     * {@code session_replication_role = replica} schaltet die Trigger für diese Transaktion ab (nur in Tests).
     */
    public static void loeschen(DataSource datenquelle, String mandant, Collection<UUID> ids) throws SQLException {
        if (ids.isEmpty()) {
            return;
        }
        String schema = new MandantCode(mandant).schema();
        try (Connection verbindung = datenquelle.getConnection()) {
            verbindung.setAutoCommit(false);
            try (Statement befehl = verbindung.createStatement()) {
                befehl.execute("set local session_replication_role = replica");
                for (String tabelle : TABELLEN) {
                    String spalte = "dokument".equals(tabelle) ? "id" : "dokument_id";
                    try (PreparedStatement loeschen = verbindung.prepareStatement(
                            "delete from " + schema + "." + tabelle + " where " + spalte + " = any (?)")) {
                        loeschen.setArray(1, verbindung.createArrayOf("uuid", ids.toArray()));
                        loeschen.executeUpdate();
                    }
                }
                verbindung.commit();
            } catch (SQLException e) {
                verbindung.rollback();
                throw e;
            } finally {
                verbindung.setAutoCommit(true);
            }
        }
    }

    /** Das Zusatzfeld ist in {@code dokument.zusatz} als JSON-Zahl gespeichert, nicht als Text, und hat den Wert. */
    public static void zusatzZahlPruefen(DataSource datenquelle, String mandant, UUID id, String feld, String zahl)
            throws SQLException {
        try (Connection verbindung = datenquelle.getConnection();
             PreparedStatement abfrage = verbindung.prepareStatement("select jsonb_typeof(zusatz -> ?) as typ, "
                     + "(zusatz ->> ?)::numeric = ?::numeric as gleich from " + new MandantCode(mandant).schema()
                     + ".dokument where id = ?")) {
            abfrage.setString(1, feld);
            abfrage.setString(2, feld);
            abfrage.setString(3, zahl);
            abfrage.setObject(4, id);
            try (ResultSet z = abfrage.executeQuery()) {
                assertThat(z.next()).as("Dokument " + id).isTrue();
                assertThat(z.getString("typ")).as("Typ von zusatz." + feld).isEqualTo("number");
                assertThat(z.getBoolean("gleich")).as("Wert von zusatz." + feld).isTrue();
            }
        }
    }

    /** Entfernt alle Dokumente eines Mandanten, deren Art mit {@code praefix} beginnt (Probe-Arten der Szenarien). */
    public static void loeschenMitArt(DataSource datenquelle, String mandant, String praefix) throws SQLException {
        List<UUID> ids = new ArrayList<>();
        try (Connection verbindung = datenquelle.getConnection();
             PreparedStatement abfrage = verbindung.prepareStatement("select id from "
                     + new MandantCode(mandant).schema() + ".dokument where art like ?")) {
            abfrage.setString(1, praefix + "%");
            try (ResultSet z = abfrage.executeQuery()) {
                while (z.next()) {
                    ids.add(z.getObject(1, UUID.class));
                }
            }
        }
        loeschen(datenquelle, mandant, ids);
    }

    private static void einfuegen(DataSource datenquelle, UUID id, Angaben angaben, boolean fallsFehlt)
            throws SQLException {
        String schema = new MandantCode(angaben.mandant()).schema();
        String sql = "insert into " + schema + ".dokument (id, art, titel, quelle, vertraulichkeit, erstellt_von, "
                + "person_benutzer, status, bestaetigt, bestaetigt_von) values (?, ?, ?, 'UPLOAD', ?, ?, ?, ?, ?, ?)"
                + (fallsFehlt ? " on conflict (id) do nothing" : "");
        try (Connection verbindung = datenquelle.getConnection();
             PreparedStatement einfuegen = verbindung.prepareStatement(sql)) {
            einfuegen.setObject(1, id);
            einfuegen.setString(2, angaben.art());
            einfuegen.setString(3, "Testdokument " + angaben.vertraulichkeit());
            einfuegen.setString(4, angaben.vertraulichkeit());
            einfuegen.setString(5, angaben.erstelltVon());
            einfuegen.setString(6, angaben.personBenutzer());
            einfuegen.setString(7, angaben.status());
            einfuegen.setObject(8, angaben.bestaetigt() ? OffsetDateTime.now() : null, Types.TIMESTAMP_WITH_TIMEZONE);
            einfuegen.setString(9, angaben.bestaetigt() ? "probe" : null);
            einfuegen.executeUpdate();
        }
    }
}
