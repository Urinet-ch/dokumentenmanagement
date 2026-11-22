package ch.urinet.archiv.protokoll.adapter.out.persistence;

import ch.urinet.archiv.protokoll.domain.ProtokollAktion;
import ch.urinet.archiv.protokoll.domain.ZugriffEintrag;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

/** Zählt Verwaltungsaktionen und Verweigerungen unter einer mandantenbezogenen Transaktionssperre. */
final class VerwaltungsHaeufungen {
    private static final String AKTIONEN = "(aktion in ('BENUTZER_ANLEGEN', 'BENUTZER_AENDERN', "
            + "'BENUTZER_LOESCHEN', 'ROLLE_ZUWEISEN') and details->>'phase' = 'ANGEFRAGT' "
            + "or aktion = 'BENUTZER_ANTRAG' and details->>'phase' = 'ANGEFRAGT' "
            + "or aktion = 'KONFIGURATION_AENDERN' and exists "
            + "(select 1 from jsonb_array_elements(coalesce(details->'aenderungen', '[]'::jsonb)) a "
            + "where a->>'pfad' like 'einstellungen.%'))";
    private static final String VERWEIGERUNGEN = "(aktion = 'VERWEIGERT' "
            + "and details->>'grund' in ('A20', 'A21', 'A22', 'A23'))";

    record Stand(long aktionen, long verweigerungen) {
    }

    private VerwaltungsHaeufungen() {
    }

    static Stand vorEintrag(Connection verbindung, String schema, List<ZugriffEintrag> eintraege)
            throws SQLException {
        if (eintraege.stream().map(ZugriffEintrag::aktion).noneMatch(VerwaltungsHaeufungen::relevant)) {
            return null;
        }
        try (PreparedStatement sperre = verbindung.prepareStatement("select pg_advisory_xact_lock(1913, hashtext(?))")) {
            sperre.setString(1, schema);
            try (ResultSet zeile = sperre.executeQuery()) {
                if (!zeile.next()) {
                    throw new IllegalStateException("Verwaltungsalarme nicht gesperrt");
                }
            }
        }
        return zaehlen(verbindung, schema);
    }

    static void nachEintrag(Connection verbindung, String schema, Stand vorher) throws SQLException {
        if (vorher == null) {
            return;
        }
        Stand nachher = zaehlen(verbindung, schema);
        if (vorher.aktionen() < 10 && nachher.aktionen() >= 10) {
            alarm(verbindung, schema, "VERWALTUNG_AKTIONEN");
        }
        if (vorher.verweigerungen() < 5 && nachher.verweigerungen() >= 5) {
            alarm(verbindung, schema, "VERWALTUNG_VERWEIGERUNGEN");
        }
    }

    private static boolean relevant(ProtokollAktion aktion) {
        return switch (aktion) {
            case BENUTZER_ANLEGEN, BENUTZER_AENDERN, BENUTZER_LOESCHEN, ROLLE_ZUWEISEN, BENUTZER_ANTRAG,
                    KONFIGURATION_AENDERN, VERWEIGERT -> true;
            default -> false;
        };
    }

    private static Stand zaehlen(Connection verbindung, String schema) throws SQLException {
        String sql = "select count(*) filter (where " + AKTIONEN + "), count(*) filter (where "
                + VERWEIGERUNGEN + ") from " + schema
                + ".zugriff_protokoll where zeit > clock_timestamp() - interval '10 minutes'";
        try (PreparedStatement abfrage = verbindung.prepareStatement(sql); ResultSet zeile = abfrage.executeQuery()) {
            if (!zeile.next()) {
                throw new IllegalStateException("Verwaltungshäufigkeit nicht gelesen");
            }
            return new Stand(zeile.getLong(1), zeile.getLong(2));
        }
    }

    private static void alarm(Connection verbindung, String schema, String status) throws SQLException {
        try (PreparedStatement einfuegen = verbindung.prepareStatement(
                "insert into " + schema + ".alarm (schluessel) values (?)")) {
            einfuegen.setString(1, "verwaltungsalarm:" + UUID.randomUUID() + ":" + status);
            einfuegen.executeUpdate();
        }
    }
}
