package ch.urinet.archiv.sicherheit;

import ch.urinet.archiv.mandant.application.MandantFehler;
import ch.urinet.archiv.mandant.application.MandantProvisionierung;
import io.agroal.api.AgroalDataSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Das Check-Constraint {@code api_client_webseite_ck} (Changeset 010) hält die Webseiten-Felder konsistent (Req 12.2). */
@QuarkusTest
class WebseitenSchluesselConstraintTest {

    @Inject
    AgroalDataSource datenquelle;

    @Inject
    MandantProvisionierung provisionierung;

    @BeforeEach
    void mandantAnlegen() {
        try {
            provisionierung.anlegen("urinet", null, null);
        } catch (MandantFehler e) {
            assertThat(e.grund()).isEqualTo(MandantFehler.Grund.MANDANT_EXISTIERT);
        }
    }

    @Test
    void webseitenScopeVerlangtWebseiteUndHoechsteStufe() throws SQLException {
        assertThatThrownBy(() -> einfuegen("webseite-lesen", null, "oeffentlich", false)).isInstanceOf(SQLException.class)
                .hasMessageContaining("api_client_webseite_ck");
        assertThatThrownBy(() -> einfuegen("webseite-lesen", "probe", null, false)).isInstanceOf(SQLException.class)
                .hasMessageContaining("api_client_webseite_ck");
        assertThatThrownBy(() -> einfuegen("webseite-lesen", "  ", "oeffentlich", false)).isInstanceOf(SQLException.class)
                .hasMessageContaining("api_client_webseite_ck");
        assertThatThrownBy(() -> einfuegen("webseite-erfassung", "probe", "mitglieder", false))
                .isInstanceOf(SQLException.class).hasMessageContaining("api_client_webseite_ck");
        assertThatThrownBy(() -> einfuegen("import", "probe", "oeffentlich", false)).isInstanceOf(SQLException.class)
                .hasMessageContaining("api_client_webseite_ck");
        assertThatThrownBy(() -> einfuegen("import", null, null, true)).isInstanceOf(SQLException.class)
                .hasMessageContaining("api_client_webseite_ck");
        assertThat(einfuegen("webseite-lesen", "probe", "mitglieder", false)).isEqualTo(1);
        assertThat(einfuegen("import", null, null, false)).isEqualTo(1);
    }

    private int einfuegen(String scope, String webseite, String stufe, boolean absenderPruefen) throws SQLException {
        String id = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        try (Connection verbindung = datenquelle.getConnection()) {
            try (PreparedStatement befehl = verbindung.prepareStatement("insert into public.api_client (id, mandant, "
                    + "name, scope, geheimnis_hash, aktiv, erstellt, webseite, hoechste_stufe, absender_pruefen) "
                    + "values (?, 'urinet', 'constraint-test', ?, 'hash', true, now(), ?, ?, ?)")) {
                befehl.setString(1, id);
                befehl.setString(2, scope);
                befehl.setString(3, webseite);
                befehl.setString(4, stufe);
                befehl.setBoolean(5, absenderPruefen);
                return befehl.executeUpdate();
            }
        } finally {
            try (Connection verbindung = datenquelle.getConnection(); PreparedStatement weg = verbindung.prepareStatement(
                    "delete from public.api_client where id = ?")) {
                weg.setString(1, id);
                weg.executeUpdate();
            }
        }
    }
}
