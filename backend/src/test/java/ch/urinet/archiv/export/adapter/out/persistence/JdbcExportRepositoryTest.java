package ch.urinet.archiv.export.adapter.out.persistence;

import ch.urinet.archiv.export.application.ExportVerwaltung;
import ch.urinet.archiv.export.domain.Export;
import ch.urinet.archiv.export.domain.ExportFehlerart;
import ch.urinet.archiv.export.domain.ExportStatus;
import ch.urinet.archiv.mandant.application.MandantKontext;
import ch.urinet.archiv.mandant.application.MandantProvisionierung;
import ch.urinet.archiv.mandant.domain.MandantCode;
import io.agroal.api.AgroalDataSource;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Reihenfolge und Begrenzung von {@code neueste} gegen die echte Abfrage (Req 7.1): neueste zuerst, bei gleichem
 * Erstellzeitpunkt nach Id, höchstens die verlangte Anzahl. Die Testzeilen liegen im Jahr 2100 und verdrängen damit
 * Zeilen anderer Tests; sie sind {@code FEHLER}, weil höchstens ein Export gleichzeitig {@code OFFEN} sein darf.
 */
@QuarkusTest
class JdbcExportRepositoryTest {

    private static final MandantCode MANDANT = new MandantCode("urinet");
    private static final Instant BASIS = Instant.parse("2100-01-01T00:00:00Z");

    @Inject
    JdbcExportRepository repository;

    @Inject
    MandantKontext kontext;

    @Inject
    MandantProvisionierung provisionierung;

    @Inject
    AgroalDataSource datenbank;

    private final ExecutorService thread = Executors.newSingleThreadExecutor();
    private final List<UUID> angelegt = new ArrayList<>();

    @AfterEach
    void aufraeumen() throws SQLException {
        thread.shutdownNow();
        try (Connection verbindung = datenbank.getConnection();
             PreparedStatement loeschen = verbindung.prepareStatement(
                     "delete from " + MANDANT.schema() + ".export where id = ?")) {
            for (UUID id : angelegt) {
                loeschen.setObject(1, id);
                loeschen.executeUpdate();
            }
        }
    }

    @Test
    void neuesteLiefertHoechstensDieAnzahlNeuesteZuerstUndBeiGleichheitNachId() throws Exception {
        mandantVorhanden();
        // 24 Zeilen, je zwei mit gleichem Zeitpunkt; in gemischter Reihenfolge eingefügt
        List<Zeile> zeilen = new ArrayList<>();
        for (int i = 0; i < 24; i++) {
            zeilen.add(new Zeile(new UUID(0xE000, i + 1), BASIS.minusSeconds(i / 2)));
        }
        List<Zeile> erwartet = zeilen.stream()
                .sorted(Comparator.comparing(Zeile::erstellt).reversed().thenComparing(Zeile::id))
                .limit(ExportVerwaltung.LISTE).toList();
        List<Zeile> gemischt = new ArrayList<>(zeilen);
        Collections.shuffle(gemischt, new java.util.Random(7));
        for (Zeile zeile : gemischt) {
            einfuegen(zeile);
        }

        List<Export> liste = thread.submit(() -> kontext.mit(MANDANT, () -> QuarkusTransaction.requiringNew()
                .call(() -> repository.neueste(ExportVerwaltung.LISTE)))).get(30, TimeUnit.SECONDS);

        assertThat(liste).hasSize(ExportVerwaltung.LISTE);
        assertThat(liste).extracting(Export::id).containsExactlyElementsOf(erwartet.stream().map(Zeile::id).toList());
        assertThat(liste).extracting(Export::erstellt)
                .containsExactlyElementsOf(erwartet.stream().map(Zeile::erstellt).toList());
    }

    @Test
    void fehlerzeilenVorDerFehlerartLassenSichLadenUndLeseneKeineArtVor() throws Exception {
        mandantVorhanden();
        UUID ohneAlles = new UUID(0xE001, 1);
        UUID nurFehlerId = new UUID(0xE001, 2);
        UUID fehlerId = UUID.randomUUID();
        fehlerzeile(ohneAlles, null, null);
        fehlerzeile(nurFehlerId, fehlerId, null);

        Export a = imMandanten(() -> repository.laden(ohneAlles)).orElseThrow();
        Export b = imMandanten(() -> repository.laden(nurFehlerId)).orElseThrow();

        assertThat(a.status()).isEqualTo(ExportStatus.FEHLER);
        assertThat(a.fehlerId()).isEmpty();
        assertThat(a.fehlerart()).isEmpty();
        assertThat(b.fehlerId()).contains(fehlerId);
        assertThat(b.fehlerart()).isEmpty();
    }

    @Test
    void fehlerSchreibtIdUndArtNurBeiOffenenExporten() throws Exception {
        mandantVorhanden();
        UUID offen = new UUID(0xE002, 1);
        UUID fertigerFehler = new UUID(0xE002, 2);
        UUID alteId = UUID.randomUUID();
        fehlerzeile(fertigerFehler, alteId, ExportFehlerart.ZEITGRENZE.name());
        offen(offen);

        boolean schonFehler = imMandanten(() -> repository.fehler(fertigerFehler, UUID.randomUUID(),
                ExportFehlerart.VERWAIST));
        UUID neueId = UUID.randomUUID();
        boolean geschrieben = imMandanten(() -> repository.fehler(offen, neueId, ExportFehlerart.VERWAIST));
        boolean zweimal = imMandanten(() -> repository.fehler(offen, UUID.randomUUID(), ExportFehlerart.FEHLGESCHLAGEN));

        assertThat(schonFehler).as("ein Fehler wird nicht überschrieben").isFalse();
        assertThat(geschrieben).isTrue();
        assertThat(zweimal).as("der erste Fehlversuch bleibt stehen").isFalse();
        Export unveraendert = imMandanten(() -> repository.laden(fertigerFehler)).orElseThrow();
        assertThat(unveraendert.fehlerId()).contains(alteId);
        assertThat(unveraendert.fehlerart()).contains(ExportFehlerart.ZEITGRENZE);
        Export gesetzt = imMandanten(() -> repository.laden(offen)).orElseThrow();
        assertThat(gesetzt.status()).isEqualTo(ExportStatus.FEHLER);
        assertThat(gesetzt.fehlerId()).contains(neueId);
        assertThat(gesetzt.fehlerart()).contains(ExportFehlerart.VERWAIST);
    }

    @Test
    void dieDatenbankLehntEineUnbekannteFehlerartAb() throws Exception {
        mandantVorhanden();
        UUID id = new UUID(0xE003, 1);

        assertThatThrownBy(() -> fehlerzeile(id, UUID.randomUUID(), "verwaist")).isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> fehlerzeile(id, UUID.randomUUID(), "ABGESTUERZT")).isInstanceOf(SQLException.class);
    }

    private <T> T imMandanten(java.util.concurrent.Callable<T> arbeit) throws Exception {
        return thread.submit(() -> kontext.mit(MANDANT, () -> QuarkusTransaction.requiringNew().call(arbeit)))
                .get(30, TimeUnit.SECONDS);
    }

    private void fehlerzeile(UUID id, UUID fehlerId, String art) throws SQLException {
        try (Connection verbindung = datenbank.getConnection();
             PreparedStatement einfuegen = verbindung.prepareStatement("insert into " + MANDANT.schema()
                     + ".export (id, geschaeftsjahr, status, benutzer, rolle, erstellt, fehler_id, fehler_art) "
                     + "values (?, 2025, 'FEHLER', 'probe-export', 'INHABER', ?, ?, ?)")) {
            einfuegen.setObject(1, id);
            einfuegen.setTimestamp(2, Timestamp.from(BASIS.minusSeconds(5000)));
            einfuegen.setObject(3, fehlerId);
            einfuegen.setObject(4, art);
            angelegt.add(id);
            einfuegen.executeUpdate();
        }
    }

    private void offen(UUID id) throws SQLException {
        try (Connection verbindung = datenbank.getConnection();
             PreparedStatement einfuegen = verbindung.prepareStatement("insert into " + MANDANT.schema()
                     + ".export (id, geschaeftsjahr, status, benutzer, rolle, erstellt) "
                     + "values (?, 2025, 'OFFEN', 'probe-export', 'INHABER', ?)")) {
            einfuegen.setObject(1, id);
            einfuegen.setTimestamp(2, Timestamp.from(BASIS.minusSeconds(5000)));
            angelegt.add(id);
            einfuegen.executeUpdate();
        }
    }

    private record Zeile(UUID id, Instant erstellt) {
    }

    private void einfuegen(Zeile zeile) throws SQLException {
        try (Connection verbindung = datenbank.getConnection();
             PreparedStatement einfuegen = verbindung.prepareStatement("insert into " + MANDANT.schema()
                     + ".export (id, geschaeftsjahr, status, benutzer, rolle, erstellt, fehler_id) "
                     + "values (?, 2025, 'FEHLER', 'probe-export', 'INHABER', ?, ?)")) {
            einfuegen.setObject(1, zeile.id());
            einfuegen.setTimestamp(2, Timestamp.from(zeile.erstellt()));
            einfuegen.setObject(3, UUID.randomUUID());
            einfuegen.executeUpdate();
            angelegt.add(zeile.id());
        }
    }

    private void mandantVorhanden() throws SQLException {
        try (Connection verbindung = datenbank.getConnection();
             PreparedStatement abfrage = verbindung.prepareStatement("select 1 from public.mandant where code = ?")) {
            abfrage.setString(1, MANDANT.wert());
            try (ResultSet zeile = abfrage.executeQuery()) {
                if (zeile.next()) {
                    return;
                }
            }
        }
        provisionierung.anlegen(MANDANT.wert(), null, null);
    }
}
