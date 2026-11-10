package ch.urinet.archiv.mandant;

import io.agroal.api.AgroalDataSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Ein Mandanten-Schema bleibt nicht an einer Pool-Verbindung hängen (design.md «Mandant»): Quarkus setzt den Mandanten
 * per {@code Connection.setSchema}, Agroal setzt es bei der Rückgabe nicht zurück; {@code PublicSchemaBeimAusleihen}
 * stellt beim nächsten Ausleihen wieder {@code public} ein.
 */
@QuarkusTest
class MandantVerbindungTest {

    @Inject
    AgroalDataSource datenbank;

    @Test
    void ausgelieheneVerbindungStehtAufPublic() throws SQLException {
        int prozess;
        try (Connection verbindung = datenbank.getConnection()) {
            verbindung.setSchema("information_schema");
            assertThat(wert(verbindung, "select current_schema()")).isEqualTo("information_schema");
            prozess = Integer.parseInt(wert(verbindung, "select pg_backend_pid()::text"));
        }
        try (Connection verbindung = datenbank.getConnection()) {
            assertThat(Integer.parseInt(wert(verbindung, "select pg_backend_pid()::text")))
                    .as("dieselbe Pool-Verbindung").isEqualTo(prozess);
            assertThat(wert(verbindung, "select current_schema()")).isEqualTo("public");
        }
    }

    private static String wert(Connection verbindung, String sql) throws SQLException {
        try (Statement befehl = verbindung.createStatement(); ResultSet zeile = befehl.executeQuery(sql)) {
            zeile.next();
            return zeile.getString(1);
        }
    }
}
