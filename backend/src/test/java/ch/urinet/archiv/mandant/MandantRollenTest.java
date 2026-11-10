package ch.urinet.archiv.mandant;

import ch.urinet.archiv.mandant.application.MandantProvisionierung;
import io.agroal.api.AgroalDataSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

/** Die App-Rolle {@code archiv_app} liest und schreibt im Mandanten-Schema, legt aber nichts an (Req 1.7, design.md). */
@QuarkusTest
class MandantRollenTest {

    @Inject
    MandantProvisionierung provisionierung;

    @Inject
    AgroalDataSource datenbank;

    @Test
    void appRolleOhneSchemaRechte() throws SQLException {
        try (Connection verbindung = datenbank.getConnection(); Statement befehl = verbindung.createStatement()) {
            befehl.execute("do $$ begin if not exists (select 1 from pg_roles where rolname = 'archiv_app') "
                    + "then create role archiv_app nologin; end if; end $$");
        }
        provisionierung.anlegen("probe-rolle", null, null);
        try (Connection verbindung = datenbank.getConnection(); Statement befehl = verbindung.createStatement()) {
            assertThat(wahr(befehl, "has_schema_privilege('archiv_app', 'm_probe_rolle', 'USAGE')")).isTrue();
            assertThat(wahr(befehl, "has_schema_privilege('archiv_app', 'm_probe_rolle', 'CREATE')")).isFalse();
            assertThat(wahr(befehl, "has_table_privilege('archiv_app', 'm_probe_rolle.dokumentart', 'SELECT, INSERT, UPDATE, DELETE')")).isTrue();
            assertThat(wahr(befehl, "has_table_privilege('archiv_app', 'm_probe_rolle.databasechangelog', 'INSERT')")).isFalse();
            assertThat(wahr(befehl, "has_database_privilege('archiv_app', current_database(), 'CREATE')")).isFalse();
        }
    }

    private static boolean wahr(Statement befehl, String ausdruck) throws SQLException {
        try (ResultSet ergebnis = befehl.executeQuery("select " + ausdruck)) {
            ergebnis.next();
            return ergebnis.getBoolean(1);
        }
    }
}
