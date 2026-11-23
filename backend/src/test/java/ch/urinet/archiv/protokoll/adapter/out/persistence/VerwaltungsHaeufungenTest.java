package ch.urinet.archiv.protokoll.adapter.out.persistence;

import ch.urinet.archiv.protokoll.domain.ProtokollAktion;
import ch.urinet.archiv.protokoll.domain.ProtokollDetails;
import ch.urinet.archiv.protokoll.domain.ZugriffEintrag;
import io.agroal.api.AgroalDataSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
class VerwaltungsHaeufungenTest {

    @Inject AgroalDataSource datenquelle;

    @Test
    void schwellenGeltenJeZehnMinutenUndNurFuerVerwaltungsverweigerungen() throws Exception {
        try (Connection verbindung = datenquelle.getConnection()) {
            verbindung.setAutoCommit(false);
            try {
                try (PreparedStatement tabellen = verbindung.prepareStatement(
                        "create temporary table zugriff_protokoll "
                                + "(zeit timestamptz not null default clock_timestamp(), aktion text, details jsonb) "
                                + "on commit drop; create temporary table alarm (schluessel text) on commit drop")) {
                    tabellen.execute();
                }
                try (PreparedStatement alte = verbindung.prepareStatement(
                        "insert into pg_temp.zugriff_protokoll (zeit, aktion, details) "
                                + "select clock_timestamp() - interval '11 minutes', 'BENUTZER_AENDERN', "
                                + "'{\"phase\":\"ANGEFRAGT\"}'::jsonb from generate_series(1, 10)")) {
                    alte.executeUpdate();
                }
                ZugriffEintrag aktion = vorgang();
                for (int i = 0; i < 9; i++) {
                    schreiben(verbindung, aktion);
                }
                assertThat(anzahl(verbindung, "VERWALTUNG_AKTIONEN")).isZero();
                schreiben(verbindung, aktion);
                schreiben(verbindung, aktion);
                assertThat(anzahl(verbindung, "VERWALTUNG_AKTIONEN")).isEqualTo(1);

                for (int i = 0; i < 2; i++) {
                    schreiben(verbindung, verweigerung("A20"));
                }
                schreiben(verbindung, verweigerung("A13"));
                schreiben(verbindung, verweigerung("A14"));
                schreiben(verbindung, verweigerung("A2"));
                assertThat(anzahl(verbindung, "VERWALTUNG_VERWEIGERUNGEN")).isZero();
                schreiben(verbindung, verweigerung("A23"));
                schreiben(verbindung, verweigerung("A20"));
                assertThat(anzahl(verbindung, "VERWALTUNG_VERWEIGERUNGEN")).isEqualTo(1);
            } finally {
                verbindung.rollback();
            }
        }
    }

    private static void schreiben(Connection verbindung, ZugriffEintrag eintrag) throws Exception {
        var vorher = VerwaltungsHaeufungen.vorEintrag(verbindung, "pg_temp", List.of(eintrag));
        try (PreparedStatement einfuegen = verbindung.prepareStatement(
                "insert into pg_temp.zugriff_protokoll (aktion, details) values (?, ?::jsonb)")) {
            einfuegen.setString(1, eintrag.aktion().name());
            einfuegen.setString(2, JdbcZugriffProtokoll.json(eintrag.details()));
            einfuegen.executeUpdate();
        }
        VerwaltungsHaeufungen.nachEintrag(verbindung, "pg_temp", vorher);
    }

    private static long anzahl(Connection verbindung, String status) throws Exception {
        try (PreparedStatement abfrage = verbindung.prepareStatement(
                "select count(*) from pg_temp.alarm where split_part(schluessel, ':', 3) = ?")) {
            abfrage.setString(1, status);
            try (ResultSet zeile = abfrage.executeQuery()) {
                assertThat(zeile.next()).isTrue();
                return zeile.getLong(1);
            }
        }
    }

    private static ZugriffEintrag vorgang() {
        var details = new ProtokollDetails.Verwaltung(UUID.randomUUID(),
                ProtokollDetails.Verwaltungsphase.ANGEFRAGT, UUID.randomUUID(),
                ProtokollDetails.Verwaltungsvorgang.NAME_AENDERN, null, null, List.of());
        return new ZugriffEintrag(Optional.empty(), Optional.empty(), ProtokollAktion.BENUTZER_AENDERN,
                Optional.empty(), Optional.empty(), ProtokollDetails.verwaltung(details));
    }

    private static ZugriffEintrag verweigerung(String aktion) {
        return new ZugriffEintrag(Optional.empty(), Optional.empty(), ProtokollAktion.VERWEIGERT,
                Optional.empty(), Optional.empty(), new ProtokollDetails(aktion, null, "NICHT_ERLAUBT"));
    }
}
