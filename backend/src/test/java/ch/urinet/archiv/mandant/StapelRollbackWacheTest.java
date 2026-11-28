package ch.urinet.archiv.mandant;

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

@QuarkusTest
class StapelRollbackWacheTest {

    private static final String CHANGESET = "db/mandant/changesets/143-stapel-datenmodell.xml";

    @Inject AgroalDataSource datenbank;

    @Test
    void rollbackBrichtSobaldStapelDatenVorhandenSind() throws Exception {
        String wache = wache();
        String schema = "wache_" + UUID.randomUUID().toString().replace("-", "");
        List<String> nutzung = List.of(
                "insert into stapel values ('00000000-0000-0000-0000-000000000001')",
                "insert into stapel_seite values ('00000000-0000-0000-0000-000000000001')",
                "insert into dokument values ('00000000-0000-0000-0000-000000000001')");
        try (Connection verbindung = datenbank.getConnection()) {
            verbindung.setAutoCommit(false);
            try (Statement befehl = verbindung.createStatement()) {
                befehl.execute("create schema " + schema);
                befehl.execute("set local search_path to " + schema);
                befehl.execute("create table stapel (id uuid)");
                befehl.execute("create table stapel_seite (stapel_id uuid)");
                befehl.execute("create table dokument (stapel_id uuid)");
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
        try (InputStream xml = StapelRollbackWacheTest.class.getClassLoader().getResourceAsStream(CHANGESET)) {
            assertThat(xml).as(CHANGESET).isNotNull();
            var dokument = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(xml);
            Element rollback = (Element) dokument.getElementsByTagName("rollback").item(0);
            return rollback.getElementsByTagName("sql").item(0).getTextContent();
        }
    }
}
