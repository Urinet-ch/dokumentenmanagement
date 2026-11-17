package ch.urinet.archiv.mandant;

import ch.urinet.archiv.ablage.domain.AbgelegteDatei;
import ch.urinet.archiv.mandant.application.MandantKontext;
import ch.urinet.archiv.mandant.application.MandantProvisionierung;
import ch.urinet.archiv.mandant.application.MandantVollexportDaten;
import ch.urinet.archiv.mandant.application.MandantVollexportRepository;
import ch.urinet.archiv.mandant.domain.MandantCode;
import io.agroal.api.AgroalDataSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Vollexport gegen die echte Datenbank (Req 8.2): Die JDBC-Adapter lesen nur das Schema des Mandanten im Kontext,
 * der Seitenwechsel über 200 Dokumente verliert und verdoppelt nichts, und der Exportstatus wechselt nur einmal.
 */
@QuarkusTest
class MandantVollexportDbTest {

    private static final MandantCode A = new MandantCode("urinet");
    private static final MandantCode B = new MandantCode("vhf-gsk");
    // eigener Thread ohne Request-Kontext, wie im Worker
    private static final ExecutorService THREADS = Executors.newSingleThreadExecutor();

    @Inject
    MandantKontext kontext;

    @Inject
    MandantProvisionierung provisionierung;

    @Inject
    AgroalDataSource datenbank;

    @Inject
    MandantVollexportDaten daten;

    @Inject
    MandantVollexportRepository repository;

    @AfterAll
    static void beenden() {
        THREADS.shutdownNow();
    }

    @BeforeEach
    void mandanten() throws SQLException {
        for (MandantCode code : List.of(A, B)) {
            if (!imRegister(code.wert())) {
                provisionierung.anlegen(code.wert(), null, null);
            }
        }
    }

    @Test
    void exportLiestNurDasSchemaDesKontextesUeberAlleSeiten() throws Exception {
        Set<UUID> idsA = dokumenteEinfuegen("m_urinet", 205);
        Set<UUID> idsB = dokumenteEinfuegen("m_vhf_gsk", 3);

        List<UUID> gelesenA = alleIds(A);
        List<UUID> gelesenB = alleIds(B);

        assertThat(gelesenA).doesNotHaveDuplicates().containsAll(idsA).doesNotContainAnyElementsOf(idsB);
        assertThat(gelesenB).doesNotHaveDuplicates().containsAll(idsB).doesNotContainAnyElementsOf(idsA);
        assertThat(gelesenA.size()).isGreaterThanOrEqualTo(205);
    }

    @Test
    void statusWechseltNurEinmalVonOffenZuFertigOderFehler() throws Exception {
        UUID fertig = UUID.randomUUID();
        UUID fehler = UUID.randomUUID();
        var zip = new AbgelegteDatei("exporte/" + fertig + ".zip", "v1", "a".repeat(64), 12, Optional.empty());

        boolean[] ergebnisse = THREADS.submit(() -> kontext.mit(A, () -> new boolean[]{
                repository.anlegen(fertig), repository.anlegen(fertig),
                repository.fertig(fertig, zip, 7), repository.fertig(fertig, zip, 8),
                repository.anlegen(fehler), repository.fehler(fehler, UUID.randomUUID()),
                repository.fertig(fehler, zip, 1)})).get();

        assertThat(ergebnisse).containsExactly(true, false, true, false, true, true, false);
        var geladen = THREADS.submit(() -> kontext.mit(A, () -> repository.laden(fertig))).get().orElseThrow();
        assertThat(geladen.status()).isEqualTo(MandantVollexportRepository.Status.FERTIG);
        assertThat(geladen.anzahl()).contains(7);
        assertThat(geladen.sha256()).contains("a".repeat(64));
        // der Export gehört nur dem Schema A
        assertThat(THREADS.submit(() -> kontext.mit(B, () -> repository.laden(fertig))).get()).isEmpty();
    }

    @Test
    void bestandZaehltNurDasEigeneSchemaUndMeldetJuengsteAenderung() throws Exception {
        dokumenteEinfuegen("m_vhf_gsk", 2);
        var vorher = THREADS.submit(() -> kontext.mit(B, () -> repository.bestand())).get();
        var aVorher = THREADS.submit(() -> kontext.mit(A, () -> repository.bestand())).get();

        dokumenteEinfuegen("m_vhf_gsk", 1);
        var nachher = THREADS.submit(() -> kontext.mit(B, () -> repository.bestand())).get();
        var a = THREADS.submit(() -> kontext.mit(A, () -> repository.bestand())).get();

        assertThat(nachher.dokumente()).isEqualTo(vorher.dokumente() + 1);
        assertThat(nachher.letzteAenderung()).isPresent();
        assertThat(nachher.letzteAenderung().get()).isAfterOrEqualTo(vorher.letzteAenderung().orElseThrow());
        assertThat(a.dokumente()).isEqualTo(aVorher.dokumente());
    }

    @Test
    void bestandHebtLetzteAenderungBeiNeuerDateiOhneDokumentUpdate() throws Exception {
        Set<UUID> ids = dokumenteEinfuegen("m_vhf_gsk", 1);
        UUID id = ids.iterator().next();
        var vorher = THREADS.submit(() -> kontext.mit(B, () -> repository.bestand())).get();
        try (Connection verbindung = datenbank.getConnection();
             PreparedStatement befehl = verbindung.prepareStatement("insert into m_vhf_gsk.dokument_datei "
                     + "(dokument_id, nr, rolle, sha256, empfangen) values (?, 1, 'ORIGINAL', ?, "
                     + "clock_timestamp() + interval '1 hour')")) {
            befehl.setObject(1, id);
            befehl.setString(2, "a".repeat(64));
            befehl.executeUpdate();
        }

        var nachher = THREADS.submit(() -> kontext.mit(B, () -> repository.bestand())).get();

        assertThat(nachher.dokumente()).isEqualTo(vorher.dokumente());
        assertThat(nachher.letzteAenderung().orElseThrow()).isAfter(vorher.letzteAenderung().orElseThrow());
    }

    private List<UUID> alleIds(MandantCode code) throws Exception {
        return THREADS.submit(() -> kontext.mit(code, () -> {
            List<UUID> ids = new ArrayList<>();
            Optional<UUID> nach = Optional.empty();
            while (true) {
                var seite = daten.seite(nach, 200);
                seite.forEach(d -> ids.add(d.id()));
                if (seite.size() < 200) {
                    return ids;
                }
                nach = Optional.of(seite.getLast().id());
            }
        })).get();
    }

    private Set<UUID> dokumenteEinfuegen(String schema, int anzahl) throws SQLException {
        Set<UUID> ids = new HashSet<>();
        try (Connection verbindung = datenbank.getConnection();
             PreparedStatement befehl = verbindung.prepareStatement("insert into " + schema
                     + ".dokument (id, quelle, erstellt_von, status) values (?, 'UPLOAD', 'test', 'NEU')")) {
            for (int i = 0; i < anzahl; i++) {
                UUID id = UUID.randomUUID();
                ids.add(id);
                befehl.setObject(1, id);
                befehl.addBatch();
            }
            befehl.executeBatch();
        }
        return ids;
    }

    private boolean imRegister(String code) throws SQLException {
        try (Connection verbindung = datenbank.getConnection(); PreparedStatement abfrage =
                verbindung.prepareStatement("select 1 from public.mandant where code = ?")) {
            abfrage.setString(1, code);
            try (ResultSet zeile = abfrage.executeQuery()) {
                return zeile.next();
            }
        }
    }
}
