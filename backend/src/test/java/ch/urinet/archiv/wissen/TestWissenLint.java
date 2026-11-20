package ch.urinet.archiv.wissen;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

final class TestWissenLint {
    private TestWissenLint() {
    }

    /** Wie Testdokumente: append-only Testdaten nur in einer lokalen Cleanup-Transaktion entfernen. */
    static void loeschen(DataSource datenbank, UUID id) throws SQLException {
        if (id == null) {
            return;
        }
        try (var c = datenbank.getConnection()) {
            c.setAutoCommit(false);
            try (var befehl = c.createStatement()) {
                befehl.execute("set local session_replication_role = replica");
                try (var p = c.prepareStatement("""
                        delete from public.auftrag where typ = 'WISSEN_LINT' and nutzlast->>'lintId' = ?
                        """)) {
                    p.setString(1, id.toString());
                    p.executeUpdate();
                }
                for (String tabelle : List.of("ki_verbrauch", "ki_verbrauch_reservierung",
                        "wissens_aenderung", "wissens_log")) {
                    String spalte = tabelle.equals("ki_verbrauch") ? "ingest_entwurf_id" : "entwurf_id";
                    try (var p = c.prepareStatement("delete from m_urinet." + tabelle + " where " + spalte
                            + " = (select entwurf_id from m_urinet.wissens_lint where id = ?)")) {
                        p.setObject(1, id);
                        p.executeUpdate();
                    }
                }
                try (var p = c.prepareStatement("""
                        with geloescht as (delete from m_urinet.wissens_lint where id = ? returning entwurf_id)
                        delete from m_urinet.wissens_entwurf where id in (select entwurf_id from geloescht)
                        """)) {
                    p.setObject(1, id);
                    p.executeUpdate();
                }
                c.commit();
            } catch (SQLException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(true);
            }
        }
    }
}
