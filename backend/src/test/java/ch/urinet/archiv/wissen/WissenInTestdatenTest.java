package ch.urinet.archiv.wissen;

import ch.urinet.archiv.mandant.application.MandantProvisionierung;
import io.agroal.api.AgroalDataSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;

/** Die Testdaten ({@link WissenInTestdaten}) schalten Wissen für die beiden Standard-Testmandanten ein. */
@QuarkusTest
class WissenInTestdatenTest {

    @Inject
    MandantProvisionierung provisionierung;

    @Inject
    Instance<AgroalDataSource> datenbank;

    @Test
    void standardTestmandantenHabenWissen() throws SQLException {
        for (String code : new String[] {WissenInTestdaten.URINET, WissenInTestdaten.VHF_GSK}) {
            if (wert("select count(*) from public.mandant where code = ?", code) == 0) {
                provisionierung.anlegen(code, null, null);
            }
            assertThat(wert("select count(*) from public.mandant where code = ? and wissen_freigeschaltet", code))
                    .as(code).isEqualTo(1);
        }
    }

    private long wert(String sql, String code) throws SQLException {
        try (Connection verbindung = datenbank.get().getConnection();
             PreparedStatement abfrage = verbindung.prepareStatement(sql)) {
            abfrage.setString(1, code);
            try (ResultSet zeile = abfrage.executeQuery()) {
                zeile.next();
                return zeile.getLong(1);
            }
        }
    }
}
