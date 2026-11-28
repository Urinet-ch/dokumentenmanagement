package ch.urinet.archiv.integration.adapter.out.persistence;

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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Der Rollback von Changeset 151 darf die Gmail-Herkunft vorhandener Nachrichten nicht verwerfen. */
@QuarkusTest
class MailEingangHerkunftRollbackWacheTest {

    private static final String CHANGESET = "db/mandant/changesets/151-mail-eingang-herkunft.xml";

    @Inject AgroalDataSource datenbank;

    @Test
    void rollbackErlaubtPostfachUndVerhindertVerlustVonGmailHerkunft() throws Exception {
        String wache = wache();
        assertThat(wache).contains("raise exception");
        String schema = "mail_wache_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection verbindung = datenbank.getConnection()) {
            verbindung.setAutoCommit(false);
            try (Statement befehl = verbindung.createStatement()) {
                befehl.execute("create schema " + schema);
                befehl.execute("set local search_path to " + schema);
                befehl.execute("create table mail_eingang (herkunft text not null)");
                befehl.execute("insert into mail_eingang values ('POSTFACH')");
                befehl.execute("savepoint postfach");
                assertThatCode(() -> befehl.execute(wache)).doesNotThrowAnyException();
                befehl.execute("insert into mail_eingang values ('GMAIL_LABEL')");
                assertThatThrownBy(() -> befehl.execute(wache)).isInstanceOf(SQLException.class)
                        .hasMessageContaining("nur vor Gmail-Nachrichten");
                befehl.execute("rollback to savepoint postfach");
            } finally {
                verbindung.rollback();
            }
        }
    }

    private static String wache() throws Exception {
        try (InputStream xml = MailEingangHerkunftRollbackWacheTest.class.getClassLoader()
                .getResourceAsStream(CHANGESET)) {
            assertThat(xml).as(CHANGESET).isNotNull();
            var dokument = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(xml);
            Element rollback = (Element) dokument.getElementsByTagName("rollback").item(0);
            return rollback.getElementsByTagName("sql").item(0).getTextContent();
        }
    }
}
