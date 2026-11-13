package ch.urinet.archiv.dokument.adapter.out.persistence;

import ch.urinet.archiv.mandant.application.MandantProvisionierung;
import ch.urinet.archiv.mandant.domain.MandantCode;
import io.agroal.api.AgroalDataSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Hilfsfunktion {@code archiv_dateiname_text} des Suchindex gegen PostgreSQL der Dev Services (Req 14.8; design.md
 * «Suche»): Endung weg, {@code _}, {@code -} und {@code .} trennen Wörter, {@code null} wird leer.
 */
@QuarkusTest
class ArchivDateinameTextTest {

    private static final String SCHEMA = new MandantCode("urinet").schema();

    @Inject
    AgroalDataSource datenbank;

    @Inject
    MandantProvisionierung provisionierung;

    @BeforeEach
    void mandant() throws SQLException {
        try (Connection verbindung = datenbank.getConnection();
             PreparedStatement abfrage = verbindung.prepareStatement(
                     "select 1 from public.mandant where code = 'urinet'");
             ResultSet zeile = abfrage.executeQuery()) {
            if (!zeile.next()) {
                provisionierung.anlegen("urinet", null, null);
            }
        }
    }

    @ParameterizedTest(name = "{0} → «{1}»")
    @CsvSource(delimiter = '|', nullValues = "NULL", value = {
            "Scan_Rechnung-2026.pdf    | Scan Rechnung 2026",
            "beleg.PDF                 | beleg",
            "foto.jpeg                 | foto",
            "archiv.7z                 | archiv",
            "Beleg 13.11.2026          | Beleg 13 11 2026",
            ".pdf                      | ' pdf'",
            "notiz.prüf                | notiz prüf",
            "a.b.c.txt                 | a b c",
            "Aufnahme 13.11.2026 08:15 | Aufnahme 13 11 2026 08:15",
            "zahl.123456               | zahl 123456",
            "lang.abcdef               | lang abcdef",
            "NULL                      | ''",
    })
    void bereitetDenNamenFuerDenIndexAuf(String name, String erwartet) throws SQLException {
        try (Connection verbindung = datenbank.getConnection();
             PreparedStatement abfrage = verbindung.prepareStatement(
                     "select " + SCHEMA + ".archiv_dateiname_text(?)")) {
            abfrage.setString(1, name);
            try (ResultSet zeile = abfrage.executeQuery()) {
                zeile.next();
                assertThat(zeile.getString(1)).isEqualTo(erwartet);
            }
        }
    }
}
