package ch.urinet.archiv.protokoll.application;

import ch.urinet.archiv.gemeinsam.domain.FachFehler;
import ch.urinet.archiv.gemeinsam.domain.Fehlercode;
import ch.urinet.archiv.mandant.application.MandantKontext;
import ch.urinet.archiv.mandant.application.MandantProvisionierung;
import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.protokoll.domain.Benutzerangaben;
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
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Gleichzeitige Anfragen derselben Kennung (Review PR #88, S2; Req 10.10): Die erste Transaktion fügt ein und hält ihre
 * Sperre, bis die zweite nachweislich auf die Sperre wartet (oder ohne Sperre fertig ist). Ohne Sperre schriebe die
 * zweite eine zweite Zeile, weil sie die noch nicht festgeschriebene erste nicht sieht.
 */
@QuarkusTest
class BenutzerVerzeichnisSperreTest {

    private static final MandantCode URINET = new MandantCode("urinet");
    /** Namensräume aus {@code JdbcBenutzerVerzeichnis}. */
    private static final int EINFUEGEN = 0x4245;
    private static final int ABSCHLUSS = 0x4256;

    @Inject
    BenutzerVerzeichnisPflege pflege;

    @Inject
    ProtokollEinsicht einsicht;

    @Inject
    MandantKontext kontext;

    @Inject
    MandantProvisionierung provisionierung;

    @Inject
    Instance<AgroalDataSource> datenbank;

    private final ExecutorService erste = Executors.newSingleThreadExecutor();
    private final ExecutorService zweite = Executors.newSingleThreadExecutor();

    @BeforeEach
    void mandant() throws SQLException {
        try (Connection verbindung = datenbank.get().getConnection();
             PreparedStatement abfrage = verbindung.prepareStatement("select 1 from public.mandant where code = ?")) {
            abfrage.setString(1, URINET.wert());
            try (ResultSet zeile = abfrage.executeQuery()) {
                if (!zeile.next()) {
                    provisionierung.anlegen(URINET.wert(), null, null);
                }
            }
        }
    }

    @AfterEach
    void beenden() {
        erste.shutdownNow();
        zweite.shutdownNow();
    }

    @Test
    void zweiGleichzeitigeErsteAktionenSchreibenGenauEineZeile() throws Exception {
        String kennung = "probe-parallel-" + UUID.randomUUID();
        Benutzerangaben angaben = new Benutzerangaben(kennung, Optional.of("probe"), Optional.of("MITARBEITER"), false);

        gleichzeitig(() -> pflege.nachfuehren(angaben), () -> pflege.nachfuehren(angaben), EINFUEGEN);

        assertThat(anlaesse(kennung)).containsExactly("AKTION");
    }

    @Test
    void zweiGleichzeitigeAustritteSchreibenGenauEinenAustritt() throws Exception {
        String kennung = "probe-austritt-" + UUID.randomUUID();
        CompletableFuture.runAsync(() -> im(() -> pflege.nachfuehren(new Benutzerangaben(kennung, Optional.of("probe"),
                Optional.of("MITARBEITER"), false))), erste).get(1, TimeUnit.MINUTES);

        Ergebnis ergebnis = gleichzeitig(() -> einsicht.austritt(kennung, Optional.of("probe-inhaber")),
                () -> einsicht.austritt(kennung, Optional.of("probe-inhaber")), ABSCHLUSS);

        assertThat(ergebnis.zweiteFehler()).isInstanceOf(FachFehler.class);
        assertThat(((FachFehler) ergebnis.zweiteFehler()).code()).isEqualTo(Fehlercode.STATUS_KONFLIKT);
        assertThat(anlaesse(kennung)).containsExactly("AKTION", "AUSTRITT");
    }

    /**
     * Eine Verweigerung schreibt in einer eigenen Transaktion, während die der Anfrage ausgesetzt ist und schon eine
     * erste Zeile eingefügt hat (z. B. A8 bei {@code PATCH}). Sie darf nicht auf deren Sperre warten; die Anfrage wird
     * danach zurückgerollt, es bleibt die Zeile der Verweigerung.
     */
    @Test
    void eineVerweigerungWartetNichtAufDieAusgesetzteTransaktionDerAnfrage() throws Exception {
        String kennung = "probe-verweigert-" + UUID.randomUUID();
        CompletableFuture<Void> anfrage = CompletableFuture.runAsync(() -> {
            try {
                im(() -> {
                    pflege.nachfuehren(new Benutzerangaben(kennung, Optional.of("probe"), Optional.of("MITARBEITER"),
                            false));
                    QuarkusTransaction.requiringNew().run(() -> pflege.nachfuehren(new Benutzerangaben(kennung,
                            Optional.of("probe"), Optional.of("MITARBEITER"), true)));
                    throw new FachFehler(Fehlercode.NICHT_ERLAUBT);
                });
            } catch (FachFehler erwartet) {
                // wie die Verweigerung der Anfrage: zurückgerollt
            }
        }, erste);
        anfrage.get(30, TimeUnit.SECONDS);
        assertThat(anlaesse(kennung)).containsExactly("AKTION");
    }

    private record Ergebnis(Throwable zweiteFehler) {
    }

    /**
     * Die erste Arbeit läuft in einer Transaktion und schreibt erst fest, wenn die zweite auf die Sperre im Namensraum
     * wartet oder ohne Warten fertig ist; die zweite beginnt, nachdem die erste gearbeitet hat.
     */
    private Ergebnis gleichzeitig(Runnable erst, Runnable dann, int namensraum) throws Exception {
        CountDownLatch gearbeitet = new CountDownLatch(1);
        CountDownLatch fertig = new CountDownLatch(1);
        CompletableFuture<Void> eins = CompletableFuture.runAsync(() -> im(() -> {
            erst.run();
            gearbeitet.countDown();
            warten(fertig, namensraum);
        }), erste);
        CompletableFuture<Throwable> zwei = CompletableFuture.supplyAsync(() -> {
            try {
                gearbeitet.await(1, TimeUnit.MINUTES);
                im(dann);
                return null;
            } catch (RuntimeException e) {
                return e;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return e;
            } finally {
                fertig.countDown();
            }
        }, zweite);
        eins.get(1, TimeUnit.MINUTES);
        return new Ergebnis(zwei.get(1, TimeUnit.MINUTES));
    }

    private void warten(CountDownLatch fertig, int namensraum) {
        try {
            long ende = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            while (System.nanoTime() < ende && !fertig.await(20, TimeUnit.MILLISECONDS)) {
                if (wartetAufSperre(namensraum)) {
                    return;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private boolean wartetAufSperre(int namensraum) {
        try (Connection verbindung = datenbank.get().getConnection(); PreparedStatement abfrage =
                verbindung.prepareStatement("select count(*) from pg_locks where locktype = 'advisory' "
                        + "and not granted and classid = ?::oid")) {
            abfrage.setInt(1, namensraum);
            try (ResultSet zeile = abfrage.executeQuery()) {
                zeile.next();
                return zeile.getLong(1) > 0;
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private void im(Runnable arbeit) {
        kontext.mit(URINET, () -> QuarkusTransaction.requiringNew().run(arbeit));
    }

    private List<String> anlaesse(String kennung) throws SQLException {
        try (Connection verbindung = datenbank.get().getConnection(); PreparedStatement abfrage =
                verbindung.prepareStatement("select anlass from " + URINET.schema()
                        + ".benutzer_verzeichnis where benutzer = ? order by id")) {
            abfrage.setString(1, kennung);
            try (ResultSet zeile = abfrage.executeQuery()) {
                List<String> anlaesse = new java.util.ArrayList<>();
                while (zeile.next()) {
                    anlaesse.add(zeile.getString(1));
                }
                return anlaesse;
            }
        }
    }
}
