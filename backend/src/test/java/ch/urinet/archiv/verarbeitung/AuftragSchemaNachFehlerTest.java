package ch.urinet.archiv.verarbeitung;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pflichttest Aufgabe 2.5 (Req 1.6): Ein Auftrag von Mandant B läuft nie im Schema von A, auch wenn der vorherige
 * Auftrag von A auf derselben Verbindung mit einem SQL-Fehler und Rollback endete. Ein Worker-Platz und ein Pool von
 * einer Verbindung erzwingen, dass B genau die Verbindung erhält, die A zuletzt benutzt hat.
 */
@QuarkusTest
@TestProfile(AuftragSchemaNachFehlerTest.EineVerbindung.class)
class AuftragSchemaNachFehlerTest {

    public static class EineVerbindung implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "quarkus.datasource.jdbc.max-size", "1",
                    "quarkus.datasource.jdbc.min-size", "0",
                    "archiv.worker.parallel", "1");
        }
    }

    @Inject
    AuftragTestHilfe hilfe;

    @BeforeEach
    void vorbereiten() throws Exception {
        hilfe.vorbereiten();
    }

    @Test
    void nieImSchemaVonANachSqlFehlerUndRollback() throws Exception {
        long a = hilfe.einreihen("urinet", TestHandler.SQL_FEHLER);
        long b = hilfe.einreihen("vhf-gsk", TestHandler.OK);

        hilfe.einenStarten().get();
        assertThat(hilfe.zeile(a).fehler()).startsWith("fehlgeschlagen fehler-id=");
        hilfe.einenStarten().get();

        TestHandler.Sicht sichtA = TestHandler.SICHTEN.get(a);
        TestHandler.Sicht sichtB = TestHandler.SICHTEN.get(b);
        assertThat(sichtA.schema()).isEqualTo("m_urinet");
        assertThat(sichtA.tenant()).hasToString("m_urinet");
        // dieselbe Verbindung, zuletzt im Schema von A mit Rollback
        assertThat(sichtB.pid()).isEqualTo(sichtA.pid());
        assertThat(sichtB.schema()).isEqualTo("m_vhf_gsk");
        assertThat(sichtB.tenant()).hasToString("m_vhf_gsk");
        assertThat(hilfe.zeile(b).status()).isEqualTo("FERTIG");
    }
}
