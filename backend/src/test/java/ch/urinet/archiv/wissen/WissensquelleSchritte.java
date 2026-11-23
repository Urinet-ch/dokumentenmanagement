package ch.urinet.archiv.wissen;

import ch.urinet.archiv.mandant.domain.MandantCode;
import io.agroal.api.AgroalDataSource;
import io.cucumber.java.de.Dann;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;

public class WissensquelleSchritte {
    @Inject Instance<AgroalDataSource> datenbank;

    @Dann("hat der Mandant «{word}» keine Dokumentart WISSENSQUELLE")
    public void artEntfallen(String mandant) throws SQLException {
        String schema = new MandantCode(mandant).schema();
        try (Connection verbindung = datenbank.get().getConnection();
             PreparedStatement abfrage = verbindung.prepareStatement(
                     "select count(*) from " + schema + ".dokumentart where code = 'WISSENSQUELLE'");
             ResultSet zeile = abfrage.executeQuery()) {
            assertThat(zeile.next()).isTrue();
            assertThat(zeile.getInt(1)).isZero();
        }
    }
}
