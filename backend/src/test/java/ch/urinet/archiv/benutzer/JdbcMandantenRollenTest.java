package ch.urinet.archiv.benutzer;

import ch.urinet.archiv.benutzer.application.MandantenRollen;
import ch.urinet.archiv.mandant.application.MandantKontext;
import ch.urinet.archiv.mandant.application.MandantVerweigert;
import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.sicherheit.domain.Rolle;
import io.agroal.api.AgroalDataSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@QuarkusTest
class JdbcMandantenRollenTest {
    @Inject MandantenRollen rollen;
    @Inject MandantKontext kontext;
    @Inject AgroalDataSource datenquelle;

    @Test
    void liestNurRollenDesAktivenMandanten() throws Exception {
        String basis = "rt" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String erster = basis + "a";
        String zweiter = basis + "b";
        String inaktiv = basis + "c";
        List<String> angelegt = new ArrayList<>();
        try (var thread = Executors.newSingleThreadExecutor()) {
            anlegen(erster, "m_" + erster, new String[]{"inhaber", "mitarbeiter"}, true);
            angelegt.add(erster);
            anlegen(zweiter, "m_" + zweiter, new String[]{"geschaeftsstelle"}, true);
            angelegt.add(zweiter);
            anlegen(inaktiv, "m_" + inaktiv, new String[]{"treuhaender"}, false);
            angelegt.add(inaktiv);
            assertThat(thread.submit(() -> kontext.mit(new MandantCode(erster), rollen::lesen))
                    .get(30, TimeUnit.SECONDS))
                    .containsExactlyInAnyOrder(Rolle.INHABER, Rolle.MITARBEITER);
            assertThat(thread.submit(() -> kontext.mit(new MandantCode(zweiter), rollen::lesen))
                    .get(30, TimeUnit.SECONDS))
                    .containsExactly(Rolle.GESCHAEFTSSTELLE);
            assertThatThrownBy(() -> thread.submit(rollen::lesen).get(30, TimeUnit.SECONDS))
                    .hasRootCauseInstanceOf(MandantVerweigert.class);
            assertThatThrownBy(() -> thread.submit(() -> kontext.mit(new MandantCode(inaktiv), rollen::lesen))
                    .get(30, TimeUnit.SECONDS)).hasRootCauseInstanceOf(MandantVerweigert.class);
        } finally {
            try (Connection verbindung = datenquelle.getConnection();
                 PreparedStatement entfernen = verbindung.prepareStatement(
                         "delete from public.mandant where code = ?")) {
                for (String code : angelegt) {
                    entfernen.setString(1, code);
                    entfernen.executeUpdate();
                }
            }
        }
    }

    private void anlegen(String code, String schema, String[] rollen, boolean aktiv) throws SQLException {
        try (Connection verbindung = datenquelle.getConnection();
             PreparedStatement einfuegen = verbindung.prepareStatement(
                     "insert into public.mandant (code, name, schema_name, bucket, verwaltung_rollen, aktiv) "
                             + "values (?, ?, ?, ?, ?, ?)")) {
            einfuegen.setString(1, code);
            einfuegen.setString(2, code);
            einfuegen.setString(3, schema);
            einfuegen.setString(4, "test-" + code);
            einfuegen.setArray(5, verbindung.createArrayOf("text", rollen));
            einfuegen.setBoolean(6, aktiv);
            einfuegen.executeUpdate();
        }
    }
}
