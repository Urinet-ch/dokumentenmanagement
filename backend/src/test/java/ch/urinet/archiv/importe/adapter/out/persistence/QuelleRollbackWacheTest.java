package ch.urinet.archiv.importe.adapter.out.persistence;

import io.agroal.api.AgroalDataSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.InputStream;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Wache im Rollback von {@code 150-quelle} (Review zu #750): Der Rückfall gilt nur vor der ersten Nutzung und bricht
 * sonst ab, statt Läufe, Verweise oder Quellen zu verwerfen. Geprüft wird die Wache aus dem Changeset selbst in einem
 * Wegwerf-Schema mit den nötigen Spalten, in einer Transaktion, die zurückgerollt wird.
 */
@QuarkusTest
class QuelleRollbackWacheTest {

    private static final String CHANGESET = "db/mandant/changesets/150-quelle.xml";

    @Inject AgroalDataSource datenbank;

    @Test
    void wacheLaesstNurDenUnbenutztenStandZurueck() throws Exception {
        String wache = wache();
        assertThat(wache).contains("raise exception");
        String schema = "wache_" + UUID.randomUUID().toString().replace("-", "");
        List<String> nutzung = List.of(
                "insert into quelle_lauf values (gen_random_uuid())",
                "insert into dokument values (gen_random_uuid(), 'UPLOAD')",
                "insert into dokument values (null, 'GMAIL')",
                "insert into dokument values (null, 'ORDNER')",
                "update quelle set version = 1 where code = 'drive'",
                "update quelle set geheimnis = '\\x00' where code = 'gmail'",
                "insert into quelle values ('eigene', 0, null)");
        try (Connection verbindung = datenbank.getConnection()) {
            verbindung.setAutoCommit(false);
            try (Statement befehl = verbindung.createStatement()) {
                befehl.execute("create schema " + schema);
                befehl.execute("set local search_path to " + schema);
                befehl.execute("create table quelle_lauf (id uuid)");
                befehl.execute("create table dokument (quelle_lauf_id uuid, quelle text)");
                befehl.execute("create table quelle (code text, version int, geheimnis bytea)");
                befehl.execute("insert into quelle values ('generatoren', 0, null), ('drive', 0, null), "
                        + "('mac-ordner', 0, null), ('gmail', 0, null), ('crm-csv', 0, null), ('papier', 0, null)");
                befehl.execute("insert into dokument values (null, 'UPLOAD')");
                befehl.execute("savepoint leer");
                assertThatCode(() -> befehl.execute(wache)).doesNotThrowAnyException();
                for (String schritt : nutzung) {
                    befehl.execute(schritt);
                    assertThatThrownBy(() -> befehl.execute(wache)).as(schritt).isInstanceOf(SQLException.class)
                            .hasMessageContaining("nur vor der ersten Nutzung");
                    befehl.execute("rollback to savepoint leer");
                }
            } finally {
                verbindung.rollback();
            }
        }
    }

    private static String wache() throws Exception {
        try (InputStream xml = QuelleRollbackWacheTest.class.getClassLoader().getResourceAsStream(CHANGESET)) {
            assertThat(xml).as(CHANGESET).isNotNull();
            var dokument = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(xml);
            Element rollback = (Element) dokument.getElementsByTagName("rollback").item(0);
            return rollback.getElementsByTagName("sql").item(0).getTextContent();
        }
    }
}
