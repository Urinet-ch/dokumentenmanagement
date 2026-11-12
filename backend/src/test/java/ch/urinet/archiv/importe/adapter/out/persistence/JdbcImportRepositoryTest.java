package ch.urinet.archiv.importe.adapter.out.persistence;

import ch.urinet.archiv.importe.domain.ImportArt;
import ch.urinet.archiv.importe.domain.ImportLauf;
import ch.urinet.archiv.importe.domain.ImportZeile;
import ch.urinet.archiv.importe.domain.Kopfzeile;
import ch.urinet.archiv.importe.domain.LaufStatus;
import ch.urinet.archiv.importe.domain.ZeilenPruefung.Ergebnis;
import ch.urinet.archiv.mandant.application.MandantKontext;
import ch.urinet.archiv.mandant.application.MandantProvisionierung;
import ch.urinet.archiv.mandant.domain.MandantCode;
import io.agroal.api.AgroalDataSource;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fenster beim erneuten Prüfen ({@link JdbcImportRepository#zeilen}): Die Abfrage endet mit der Zeile, mit der das JSON
 * in {@code daten} die Zeichengrenze erreicht ({@code davor < zeichen}), mindestens eine Zeile; Zeilen ohne Werte zählen
 * 0. Zwei Mandanten mit derselben Lauf-ID: Jeder liest nur sein Schema (Review PR #48 Runde 4, K2).
 */
@QuarkusTest
class JdbcImportRepositoryTest {

    private static final MandantCode URINET = new MandantCode("urinet");
    private static final MandantCode ZWEITER = new MandantCode("vhf-gsk");
    private static final UUID LAUF = UUID.randomUUID();

    @Inject
    JdbcImportRepository repository;

    @Inject
    MandantKontext kontext;

    @Inject
    MandantProvisionierung provisionierung;

    @Inject
    Instance<AgroalDataSource> datenbank;

    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    /** Länge von {@code daten::text} einer Zeile mit Werten (L). */
    private int laenge;

    @BeforeEach
    void zeilenAnlegen() throws SQLException {
        for (MandantCode code : List.of(URINET, ZWEITER)) {
            if (!vorhanden(code)) {
                provisionierung.anlegen(code.wert(), null, null);
            }
        }
        String wert = "x".repeat(1_000);
        im(URINET, () -> {
            repository.laufAnlegen(lauf());
            repository.zeilenAnlegen(LAUF, List.of(zeile(2, wert), zeile(3, null), zeile(4, wert), zeile(5, wert),
                    zeile(6, wert)));
            return null;
        });
        im(ZWEITER, () -> {
            repository.laufAnlegen(lauf());
            repository.zeilenAnlegen(LAUF, List.of(zeile(2, "fremd")));
            return null;
        });
        try (Connection verbindung = datenbank.get().getConnection();
             PreparedStatement abfrage = verbindung.prepareStatement("select distinct length(daten::text) from "
                     + URINET.schema() + ".import_zeile where lauf_id = ? and daten is not null")) {
            abfrage.setObject(1, LAUF);
            try (ResultSet ergebnis = abfrage.executeQuery()) {
                assertThat(ergebnis.next()).isTrue();
                laenge = ergebnis.getInt(1);
                assertThat(ergebnis.next()).as("alle Zeilen mit Werten gleich lang").isFalse();
            }
        }
        assertThat(laenge).isGreaterThan(1_000);
    }

    @AfterEach
    void aufraeumen() throws SQLException {
        worker.shutdown();
        try (Connection verbindung = datenbank.get().getConnection()) {
            for (MandantCode code : List.of(URINET, ZWEITER)) {
                try (PreparedStatement loeschen = verbindung.prepareStatement(
                        "delete from " + code.schema() + ".import_lauf where id = ?")) {
                    loeschen.setObject(1, LAUF);
                    loeschen.executeUpdate();
                }
            }
        }
    }

    @Test
    void fensterEndetMitDerZeileDieDieGrenzeErreicht() {
        // davor: Nr. 2 → 0, Nr. 3 (ohne Werte) → L, Nr. 4 → L, Nr. 5 → 2L, Nr. 6 → 3L
        assertThat(nummern(0, 500, laenge - 1)).containsExactly(2);
        assertThat(nummern(0, 500, laenge)).containsExactly(2);
        assertThat(nummern(0, 500, laenge + 1)).containsExactly(2, 3, 4);
        assertThat(nummern(0, 500, 2 * laenge)).containsExactly(2, 3, 4);
        assertThat(nummern(0, 500, 2 * laenge + 1)).containsExactly(2, 3, 4, 5);
    }

    @Test
    void mindestensEineZeileKeysetUndAnzahl() {
        assertThat(nummern(0, 500, 1)).containsExactly(2);
        assertThat(nummern(3, 500, 1)).containsExactly(4);
        assertThat(nummern(4, 500, laenge)).containsExactly(5);
        assertThat(nummern(4, 500, laenge + 1)).containsExactly(5, 6);
        assertThat(nummern(0, 2, Integer.MAX_VALUE)).containsExactly(2, 3);
        assertThat(nummern(6, 500, Integer.MAX_VALUE)).isEmpty();
    }

    @Test
    void jederMandantLiestNurSeineZeilen() {
        List<ImportZeile> urinet = im(URINET, () -> repository.zeilen(LAUF, 0, 500, Integer.MAX_VALUE));
        List<ImportZeile> zweiter = im(ZWEITER, () -> repository.zeilen(LAUF, 0, 500, Integer.MAX_VALUE));

        assertThat(urinet).extracting(ImportZeile::nr).containsExactly(2, 3, 4, 5, 6);
        assertThat(urinet.get(1).werte()).isNull();
        assertThat(urinet).filteredOn(z -> z.werte() != null).allSatisfy(z -> assertThat(z.werte())
                .containsExactly("x".repeat(1_000)));
        assertThat(zweiter).singleElement().satisfies(z -> assertThat(z.werte()).containsExactly("fremd"));
    }

    @Test
    void jederMandantBlaettertUndZaehltNurSeineZeilen() {
        assertThat(im(URINET, () -> repository.zeilenSeite(LAUF, 1, 2))).extracting(ImportZeile::nr)
                .containsExactly(4, 5);
        assertThat(im(ZWEITER, () -> repository.zeilenSeite(LAUF, 0, 100))).singleElement()
                .satisfies(z -> assertThat(z.werte()).containsExactly("fremd"));
        assertThat(im(ZWEITER, () -> repository.zeilenSeite(LAUF, 1, 2))).isEmpty();

        assertThat(im(URINET, () -> repository.zaehlen(LAUF)))
                .isEqualTo(new ImportLauf.Zaehler(0, 0, 0, 0, 1, 0));
        assertThat(im(ZWEITER, () -> repository.zaehlen(LAUF)))
                .isEqualTo(new ImportLauf.Zaehler(0, 0, 0, 0, 0, 0));
    }

    private List<Integer> nummern(int nachNr, int anzahl, int zeichen) {
        return im(URINET, () -> repository.zeilen(LAUF, nachNr, anzahl, zeichen)).stream().map(ImportZeile::nr)
                .toList();
    }

    /** Eigener Thread: Die Testmethode hat einen Request-Kontext, {@code mit} braucht einen ohne Anmeldung. */
    private <T> T im(MandantCode code, Supplier<T> arbeit) {
        try {
            return CompletableFuture.supplyAsync(() -> kontext.mit(code, () -> QuarkusTransaction.requiringNew()
                    .call(arbeit::get)), worker).get(1, TimeUnit.MINUTES);
        } catch (ExecutionException e) {
            throw e.getCause() instanceof RuntimeException laufzeit ? laufzeit : new IllegalStateException(e.getCause());
        } catch (InterruptedException | TimeoutException e) {
            throw new IllegalStateException(e);
        }
    }

    private boolean vorhanden(MandantCode code) throws SQLException {
        try (Connection verbindung = datenbank.get().getConnection();
             PreparedStatement abfrage = verbindung.prepareStatement("select 1 from public.mandant where code = ?")) {
            abfrage.setString(1, code.wert());
            try (ResultSet zeile = abfrage.executeQuery()) {
                return zeile.next();
            }
        }
    }

    private static ImportLauf lauf() {
        return new ImportLauf(LAUF, ImportArt.KONTAKT, false, "probe.csv", "0".repeat(64), "UTF-8", ';',
                new Kopfzeile(List.of("Name")), 0, 0, 0, LaufStatus.ZUORDNUNG_NOETIG, "inhaber", Instant.now());
    }

    private static ImportZeile zeile(int nr, String wert) {
        return new ImportZeile(nr, wert == null ? null : List.of(wert), wert == null ? Ergebnis.FEHLER : Ergebnis.OFFEN,
                wert == null ? "Feld in Spalte 1 länger als 10000 Zeichen" : null);
    }
}
