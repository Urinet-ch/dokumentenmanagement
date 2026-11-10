package ch.urinet.archiv.dokument;

import ch.urinet.archiv.mandant.domain.MandantCode;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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
            "dokument_vertrag", "belegnummer_eigen", "dokument");

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
