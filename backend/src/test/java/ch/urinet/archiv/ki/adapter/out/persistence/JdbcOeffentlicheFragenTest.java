package ch.urinet.archiv.ki.adapter.out.persistence;

import ch.urinet.archiv.ki.application.oeffentlich.OeffentlicheFragenZaehler;
import ch.urinet.archiv.mandant.application.MandantAufloesung;
import ch.urinet.archiv.mandant.application.MandantProvisionierung;
import ch.urinet.archiv.mandant.application.MandantVerzeichnis;
import ch.urinet.archiv.mandant.domain.MandantCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agroal.api.AgroalDataSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@QuarkusTest
class JdbcOeffentlicheFragenTest {

    private static final MandantCode URINET = new MandantCode("urinet");
    private static final MandantCode VHF = new MandantCode("vhf-gsk");

    @Inject
    OeffentlicheFragenZaehler zaehler;

    @Inject
    MandantProvisionierung provisionierung;

    @Inject
    MandantAufloesung aufloesung;

    @Inject
    ObjectMapper json;

    @Inject
    Instance<AgroalDataSource> datenquelle;

    @Inject
    S3Client s3;

    @ConfigProperty(name = "archiv.ablage.bucket-praefix")
    String bucketPraefix;

    private final List<Eintrag> testzeilen = new ArrayList<>();
    private final List<MandantCode> mandantenNeuAngelegt = new ArrayList<>();

    private record Eintrag(MandantCode mandant, long id) {
    }

    @BeforeEach
    void mandantenSicherstellen() throws SQLException {
        for (MandantCode code : List.of(URINET, VHF)) {
            if (!vorhanden(code)) {
                mandantenNeuAngelegt.add(code);
                provisionierung.anlegen(code.wert(), null, null);
            }
        }
    }

    @AfterEach
    void testzeilenLoeschen() throws SQLException {
        for (Eintrag zeile : testzeilen) {
            try (Connection verbindung = datenquelle.get().getConnection();
                 PreparedStatement loeschen = verbindung.prepareStatement(
                         "delete from " + zeile.mandant().schema() + ".oeffentliche_frage where id = ?")) {
                loeschen.setLong(1, zeile.id());
                loeschen.executeUpdate();
            }
        }
        testzeilen.clear();
        List<MandantCode> mandantenZumEntfernen = new ArrayList<>(mandantenNeuAngelegt);
        mandantenNeuAngelegt.clear();
        for (MandantCode mandant : mandantenZumEntfernen) {
            try {
                s3.deleteBucket(r -> r.bucket(mandant.bucket(bucketPraefix)));
            } catch (S3Exception e) {
                if (e.statusCode() != 404) {
                    throw e;
                }
            }
            try (Connection verbindung = datenquelle.get().getConnection();
                 Statement befehl = verbindung.createStatement()) {
                befehl.execute("drop schema if exists " + mandant.schema() + " cascade");
                try (PreparedStatement loeschen = verbindung.prepareStatement(
                        "delete from public.mandant where code = ?")) {
                    loeschen.setString(1, mandant.wert());
                    loeschen.executeUpdate();
                }
            }
        }
    }

    @Test
    void zaehltNurLeereQuellenUndTrenntMandanten() throws SQLException {
        long urinetVorher = zaehler.anzahlOhneBeleg(URINET);
        long vhfVorher = zaehler.anzahlOhneBeleg(VHF);

        einfuegen(URINET, "[]");
        einfuegen(URINET, "[{}]");
        einfuegen(VHF, "[]");
        einfuegen(VHF, "[]");
        einfuegen(VHF, "[{}]");

        assertThat(zaehler.anzahlOhneBeleg(URINET)).isEqualTo(urinetVorher + 1);
        assertThat(zaehler.anzahlOhneBeleg(VHF)).isEqualTo(vhfVorher + 2);
    }

    @Test
    void wirftBeiFehlendemOderUngueltigemSchema() {
        OeffentlicheFragenZaehler ohneSchema = new JdbcOeffentlicheFragen(
                datenquelle, aufloesung, code -> Optional.empty(), json);
        assertThatThrownBy(() -> ohneSchema.anzahlOhneBeleg(URINET))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Öffentliche Fragen ohne aktives Mandantenschema");

        OeffentlicheFragenZaehler ungueltigesSchema = new JdbcOeffentlicheFragen(
                datenquelle, aufloesung, code -> Optional.of("m_urinet;drop"), json);
        assertThatThrownBy(() -> ungueltigesSchema.anzahlOhneBeleg(URINET))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Öffentliche Fragen ohne gültiges Mandantenschema");
    }

    private void einfuegen(MandantCode mandant, String quellen) throws SQLException {
        try (Connection verbindung = datenquelle.get().getConnection();
             PreparedStatement einfuegen = verbindung.prepareStatement(
                     "insert into " + mandant.schema()
                             + ".oeffentliche_frage (frage, quellen, tokens, zeit) values "
                             + "('synthetische Frage', ?::jsonb, 0, now()) returning id")) {
            einfuegen.setString(1, quellen);
            try (ResultSet zeile = einfuegen.executeQuery()) {
                zeile.next();
                testzeilen.add(new Eintrag(mandant, zeile.getLong(1)));
            }
        }
    }

    private boolean vorhanden(MandantCode mandant) throws SQLException {
        try (Connection verbindung = datenquelle.get().getConnection();
             PreparedStatement abfrage = verbindung.prepareStatement(
                     "select 1 from public.mandant where code = ? and aktiv")) {
            abfrage.setString(1, mandant.wert());
            try (ResultSet zeile = abfrage.executeQuery()) {
                return zeile.next();
            }
        }
    }
}
