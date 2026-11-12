package ch.urinet.archiv.dokument.adapter.out.persistence;

import ch.urinet.archiv.ablage.domain.DateiRolle;
import ch.urinet.archiv.dokument.Testdokumente;
import ch.urinet.archiv.dokument.application.DateiFundstelle;
import ch.urinet.archiv.dokument.application.DokumentRepository;
import ch.urinet.archiv.mandant.application.MandantKontext;
import ch.urinet.archiv.mandant.application.MandantProvisionierung;
import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.sicherheit.domain.Benutzer;
import ch.urinet.archiv.sicherheit.domain.Rolle;
import ch.urinet.archiv.sicherheit.domain.Sichtregel;
import io.agroal.api.AgroalDataSource;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Zweite Schicht beim Download (Req 14.5, 14.6): {@code dateiFinden} wendet die Sichtregel selbst an, auch wenn der
 * ZugangFilter vorher schon 404 liefert, und findet nur im Schema des Mandanten aus dem Kontext. Synthetische Dokumente
 * ohne Dateien im Bucket; geprüft werden nur Pfad und Versions-ID aus der Datenbank. Läuft in eigenen Threads ohne
 * Request-Kontext.
 */
@QuarkusTest
class DateiFindenTest {

    private static final Benutzer INHABER = new Benutzer("probe-inhaber", Rolle.INHABER);
    private static final Benutzer TREUHAENDER = new Benutzer("probe-treuhaender", Rolle.TREUHAENDER);
    private static final Benutzer JOEL = new Benutzer("probe-joel", Rolle.MITARBEITER);
    private static final Benutzer ANDERER = new Benutzer("probe-anderer", Rolle.MITARBEITER);
    private static final Benutzer GESCHAEFTSSTELLE = new Benutzer("probe-gs", Rolle.GESCHAEFTSSTELLE);

    @Inject
    DokumentRepository repository;

    @Inject
    MandantKontext kontext;

    @Inject
    MandantProvisionierung provisionierung;

    @Inject
    AgroalDataSource datenbank;

    private final ExecutorService thread = Executors.newSingleThreadExecutor();
    private final Map<String, List<UUID>> angelegt = new HashMap<>();

    @BeforeEach
    void mandanten() throws SQLException {
        for (String code : List.of("urinet", "vhf-gsk")) {
            if (!imRegister(code)) {
                provisionierung.anlegen(code, null, null);
            }
        }
    }

    @AfterEach
    void aufraeumen() throws SQLException {
        thread.shutdownNow();
        for (Map.Entry<String, List<UUID>> eintrag : angelegt.entrySet()) {
            Testdokumente.loeschen(datenbank, eintrag.getKey(), eintrag.getValue());
        }
    }

    @Test
    void internNurFuerRollenMitA3() throws Exception {
        UUID intern = mitDateien("urinet", "INTERN", true, INHABER.id(), null, "ARCHIVIERT");
        for (DateiRolle rolle : List.of(DateiRolle.PDFA, DateiRolle.ORIGINAL)) {
            assertThat(finden("urinet", intern, INHABER, rolle)).as("inhaber " + rolle).isPresent();
            assertThat(finden("urinet", intern, TREUHAENDER, rolle)).as("treuhaender " + rolle).isPresent();
            assertThat(finden("urinet", intern, JOEL, rolle)).as("mitarbeiter " + rolle).isEmpty();
        }
    }

    @Test
    void personalNurFuerDieBetroffenePerson() throws Exception {
        UUID personal = mitDateien("urinet", "PERSONAL", true, INHABER.id(), JOEL.id(), "ARCHIVIERT");
        for (DateiRolle rolle : List.of(DateiRolle.PDFA, DateiRolle.ORIGINAL)) {
            assertThat(finden("urinet", personal, JOEL, rolle)).as("betroffen " + rolle).isPresent();
            assertThat(finden("urinet", personal, ANDERER, rolle)).as("anderer " + rolle).isEmpty();
        }
    }

    @Test
    void unbestaetigtNichtFuerFremdeMitarbeiter() throws Exception {
        UUID offen = mitDateien("urinet", "NORMAL", false, INHABER.id(), null, "EINGANGSKORB");
        for (DateiRolle rolle : List.of(DateiRolle.PDFA, DateiRolle.ORIGINAL)) {
            assertThat(finden("urinet", offen, INHABER, rolle)).as("inhaber " + rolle).isPresent();
            assertThat(finden("urinet", offen, JOEL, rolle)).as("mitarbeiter " + rolle).isEmpty();
            assertThat(finden("urinet", offen, TREUHAENDER, rolle)).as("treuhaender " + rolle).isEmpty();
        }
    }

    @Test
    void nurImEigenenMandanten() throws Exception {
        UUID urinet = mitDateien("urinet", "NORMAL", true, INHABER.id(), null, "ARCHIVIERT");
        UUID vhfGsk = mitDateien("vhf-gsk", "NORMAL", true, GESCHAEFTSSTELLE.id(), null, "ARCHIVIERT");
        for (DateiRolle rolle : List.of(DateiRolle.PDFA, DateiRolle.ORIGINAL)) {
            assertThat(finden("urinet", urinet, INHABER, rolle)).as("eigener Mandant " + rolle).isPresent();
            assertThat(finden("vhf-gsk", urinet, INHABER, rolle)).as("fremdes Dokument " + rolle).isEmpty();
            assertThat(finden("vhf-gsk", vhfGsk, GESCHAEFTSSTELLE, rolle)).as("vhf-gsk " + rolle).isPresent();
            assertThat(finden("urinet", vhfGsk, INHABER, rolle)).as("fremdes Dokument " + rolle).isEmpty();
        }
    }

    private Optional<DateiFundstelle> finden(String mandant, UUID id, Benutzer benutzer, DateiRolle rolle)
            throws Exception {
        int nr = rolle == DateiRolle.PDFA ? 0 : 1;
        return thread.submit(() -> kontext.mit(new MandantCode(mandant), () -> QuarkusTransaction.requiringNew()
                        .call(() -> repository.dateiFinden(id, Sichtregel.fuer(benutzer), rolle, nr))))
                .get(30, TimeUnit.SECONDS);
    }

    /** Dokument mit PDF/A und Original Nr. 1, beide mit Versions-ID. */
    private UUID mitDateien(String mandant, String vertraulichkeit, boolean bestaetigt, String erfasser,
                            String person, String status) throws SQLException {
        UUID id = Testdokumente.anlegen(datenbank, new Testdokumente.Angaben(mandant, null, vertraulichkeit,
                bestaetigt, erfasser, person, status));
        angelegt.computeIfAbsent(mandant, m -> new ArrayList<>()).add(id);
        String schema = new MandantCode(mandant).schema();
        String sha = "0".repeat(64);
        try (Connection verbindung = datenbank.getConnection();
             PreparedStatement pdfa = verbindung.prepareStatement("update " + schema + ".dokument set s3_pdfa = ?, "
                     + "s3_version_pdfa = 'v-pdfa', sha256_pdfa = ? where id = ?");
             PreparedStatement original = verbindung.prepareStatement("insert into " + schema + ".dokument_datei "
                     + "(dokument_id, nr, rolle, sha256, s3_schluessel, s3_version, mime, groesse) "
                     + "values (?, 1, 'ORIGINAL', ?, ?, 'v-original', 'application/pdf', 1)")) {
            pdfa.setString(1, "archiv/2026/" + id + "/pdfa.pdf");
            pdfa.setString(2, sha);
            pdfa.setObject(3, id);
            pdfa.executeUpdate();
            original.setObject(1, id);
            original.setString(2, sha);
            original.setString(3, "eingang/2026/" + id + "/original-1.pdf");
            original.executeUpdate();
        }
        return id;
    }

    private boolean imRegister(String code) throws SQLException {
        try (Connection verbindung = datenbank.getConnection();
             PreparedStatement abfrage = verbindung.prepareStatement("select 1 from public.mandant where code = ?")) {
            abfrage.setString(1, code);
            try (ResultSet zeile = abfrage.executeQuery()) {
                return zeile.next();
            }
        }
    }
}
