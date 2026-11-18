package ch.urinet.archiv.mandant;

import io.agroal.api.AgroalDataSource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Der echte Start der API migriert aktive Mandanten, deren Changesets fehlen (design.md «Mandant», Req 1.5). */
@Tag("start-2")
@QuarkusTest
@TestProfile(MigrationBeimStartTest.MitStartMandant.class)
class MigrationBeimStartTest {

    public static class MitStartMandant implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("archiv.test.start-mandant", "probe-start");
        }
    }

    @Inject
    AgroalDataSource datenbank;

    @Test
    void startMigriertAktiveMandanten() throws SQLException {
        try (Connection verbindung = datenbank.getConnection(); Statement befehl = verbindung.createStatement();
             ResultSet tabellen = befehl.executeQuery("select count(*) from information_schema.tables "
                     + "where table_schema = 'm_probe_start' and table_name in ('dokumentart', 'databasechangelog')")) {
            tabellen.next();
            assertThat(tabellen.getInt(1)).isEqualTo(2);
        }
    }
}
