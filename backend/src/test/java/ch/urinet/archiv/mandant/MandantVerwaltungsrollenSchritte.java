package ch.urinet.archiv.mandant;

import ch.urinet.archiv.mandant.adapter.in.kommando.MandantKommando;
import ch.urinet.archiv.mandant.domain.MandantCode;
import io.agroal.api.AgroalDataSource;
import io.cucumber.java.After;
import io.cucumber.java.de.Dann;
import io.cucumber.java.de.Wenn;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Spec 03: Verwaltungsrollen werden beim Anlegen aus der Mandantenkonfiguration übernommen. */
public class MandantVerwaltungsrollenSchritte {

    @Inject
    MandantKommando kommando;

    @Inject
    Instance<AgroalDataSource> datenbank;

    @Inject
    S3Client s3;

    @ConfigProperty(name = "archiv.ablage.bucket-praefix")
    String bucketPraefix;

    private boolean neuAngelegt;

    @Wenn("der Betreiber den Mandanten «{word}» über das Kommando anlegt")
    public void anlegen(String code) throws SQLException {
        if (imRegister(code)) {
            return;
        }
        neuAngelegt = true;
        var ausgabe = new ByteArrayOutputStream();
        var fehler = new ByteArrayOutputStream();
        int status = kommando.ausfuehren(List.of("anlegen", code),
                new PrintStream(ausgabe, true, StandardCharsets.UTF_8),
                new PrintStream(fehler, true, StandardCharsets.UTF_8));
        assertThat(status).as(fehler.toString(StandardCharsets.UTF_8)).isZero();
        assertThat(ausgabe.toString(StandardCharsets.UTF_8)).contains("Mandant " + code + " angelegt");
    }

    @Dann("enthält der Registereintrag für «{word}» die Verwaltungsrolle «{word}»")
    public void rolleImRegister(String code, String rolle) throws SQLException {
        try (Connection verbindung = datenbank.get().getConnection();
             PreparedStatement abfrage = verbindung.prepareStatement(
                     "select array_to_string(verwaltung_rollen, ',') from public.mandant where code = ?")) {
            abfrage.setString(1, code);
            try (ResultSet ergebnis = abfrage.executeQuery()) {
                assertThat(ergebnis.next()).as("Mandant im Register").isTrue();
                assertThat(ergebnis.getString(1).split(",")).containsExactly(rolle);
            }
        }
    }

    @After
    public void aufraeumen() throws SQLException {
        if (!neuAngelegt) {
            return;
        }
        MandantCode mandant = new MandantCode("vhf-gsk");
        try {
            s3.deleteBucket(r -> r.bucket(mandant.bucket(bucketPraefix)));
        } catch (S3Exception e) {
            if (e.statusCode() != 404) {
                throw e;
            }
        }
        try (Connection verbindung = datenbank.get().getConnection(); Statement befehl = verbindung.createStatement()) {
            befehl.execute("drop schema if exists " + mandant.schema() + " cascade");
            try (PreparedStatement loeschen = verbindung.prepareStatement(
                    "delete from public.mandant where code = ?")) {
                loeschen.setString(1, mandant.wert());
                loeschen.executeUpdate();
            }
        }
    }

    private boolean imRegister(String code) throws SQLException {
        try (Connection verbindung = datenbank.get().getConnection();
             PreparedStatement abfrage = verbindung.prepareStatement(
                     "select exists(select 1 from public.mandant where code = ?)")) {
            abfrage.setString(1, code);
            try (ResultSet ergebnis = abfrage.executeQuery()) {
                return ergebnis.next() && ergebnis.getBoolean(1);
            }
        }
    }
}
