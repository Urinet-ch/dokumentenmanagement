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

    @Inject
    Instance<AgroalDataSource> datenbank;

    @Dann("hat der Mandant «{word}» die Dokumentart WISSENSQUELLE mit den gemeinsamen Vorgaben")
    public void gemeinsameVorgaben(String mandant) throws SQLException {
        String schema = new MandantCode(mandant).schema();
        try (Connection verbindung = datenbank.get().getConnection(); PreparedStatement abfrage =
                     verbindung.prepareStatement("select name, gruppen = array['frei']::text[], "
                             + "pflichtfelder = array['titel', 'datum']::text[], vertraulichkeit, "
                             + "aufbewahrung_jahre, gesetzliche_pflicht, ki_vorschlag, "
                             + "zusatz_schema -> 'properties' -> 'herausgeber' ->> 'type' "
                             + "from " + schema + ".dokumentart where code = 'WISSENSQUELLE'");
             ResultSet zeile = abfrage.executeQuery()) {
            assertThat(zeile.next()).isTrue();
            assertThat(zeile.getString(1)).isEqualTo("Wissensquelle");
            assertThat(zeile.getBoolean(2)).isTrue();
            assertThat(zeile.getBoolean(3)).isTrue();
            assertThat(zeile.getString(4)).isEqualTo("NORMAL");
            assertThat(zeile.getObject(5)).isNull();
            assertThat(zeile.getBoolean(6)).isFalse();
            assertThat(zeile.getBoolean(7)).isFalse();
            assertThat(zeile.getString(8)).isEqualTo("string");
            assertThat(zeile.next()).isFalse();
        }
    }

    @Dann("verlangt die WISSENSQUELLE von «{word}» «{word}» mit der Vorgabe «{word}» und «{word}» mit der Vorgabe «{word}»")
    public void reservierteFelder(String mandant, String erstes, String erstesVorgabe, String zweites,
                                  String zweitesVorgabe) throws SQLException {
        String schema = new MandantCode(mandant).schema();
        try (Connection verbindung = datenbank.get().getConnection(); PreparedStatement abfrage =
                     verbindung.prepareStatement("select jsonb_exists(zusatz_schema -> 'required', ?), "
                             + "zusatz_schema -> 'properties' -> ? ->> 'default', "
                             + "jsonb_exists(zusatz_schema -> 'required', ?), "
                             + "zusatz_schema -> 'properties' -> ? ->> 'default', "
                             + "zusatz_schema ->> 'additionalProperties' "
                             + "from " + schema + ".dokumentart where code = 'WISSENSQUELLE'")) {
            abfrage.setString(1, erstes);
            abfrage.setString(2, erstes);
            abfrage.setString(3, zweites);
            abfrage.setString(4, zweites);
            try (ResultSet zeile = abfrage.executeQuery()) {
                assertThat(zeile.next()).isTrue();
                assertThat(zeile.getBoolean(1)).as(erstes + " Pflicht").isTrue();
                assertThat(zeile.getString(2)).isEqualTo(erstesVorgabe);
                assertThat(zeile.getBoolean(3)).as(zweites + " Pflicht").isTrue();
                assertThat(zeile.getString(4)).isEqualTo(zweitesVorgabe);
                assertThat(zeile.getString(5)).isEqualTo("false");
            }
        }
    }
}
