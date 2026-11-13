package ch.urinet.archiv.ki.application;

import ch.urinet.archiv.ki.domain.Feld;
import ch.urinet.archiv.mandant.application.MandantKontext;
import ch.urinet.archiv.mandant.domain.MandantCode;
import io.agroal.api.AgroalDataSource;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Transaktion des Lernens (Req 7.7; design.md «Umsetzung (Aufgabe 3.3)»): Das Beispiel entsteht in der Transaktion der
 * Bestätigung und entfällt mit ihrem Rollback; die Zähler laufen erst nach dem Commit. Dazu der Standard der Währung.
 */
@QuarkusTest
class KorrekturLernenTest {

    private static final MandantCode URINET = new MandantCode("urinet");
    private static final KiBeispielPort.Lernstand VORHER = new KiBeispielPort.Lernstand(Map.of(Feld.ART,
            "LIEFERANTENBELEG", Feld.TITEL, "Rechnung", Feld.VERTRAULICHKEIT, "INTERN"), "Synthetischer Beleg",
            Optional.empty(), Optional.empty());

    @Inject
    KorrekturLernen lernen;

    @Inject
    MandantKontext kontext;

    @Inject
    MeterRegistry kennzahlen;

    @Inject
    Instance<AgroalDataSource> datenbank;

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final List<UUID> dokumente = new ArrayList<>();

    @AfterEach
    void aufraeumen() throws SQLException {
        worker.shutdown();
        try (Connection verbindung = datenbank.get().getConnection();
             PreparedStatement loeschen = verbindung.prepareStatement("delete from " + URINET.schema()
                     + ".ki_beispiel where dokument_id = any (?)")) {
            loeschen.setArray(1, verbindung.createArrayOf("uuid", dokumente.toArray()));
            loeschen.executeUpdate();
        }
    }

    @Test
    void rollbackLaesstWederBeispielNochZaehler() throws SQLException {
        UUID id = dokument();
        double vorher = korrigiert("titel");

        assertThatThrownBy(() -> inTransaktion(() -> {
            lernen.lernen(URINET, id, VORHER, ergebnis("Lernprobe", null, null));
            throw new IllegalStateException("Bestätigung bricht ab");
        })).hasMessage("Bestätigung bricht ab");

        assertThat(beispiele(id)).isZero();
        assertThat(korrigiert("titel")).isEqualTo(vorher);
    }

    @Test
    void commitSchreibtBeispielUndZaehlt() throws SQLException {
        UUID id = dokument();
        double vorher = korrigiert("titel");

        inTransaktion(() -> lernen.lernen(URINET, id, VORHER, ergebnis("Lernprobe", null, null)));

        assertThat(beispiele(id)).isOne();
        assertThat(korrigiert("titel")).isEqualTo(vorher + 1);
    }

    /** Ohne Währung im Vorschlag und in der Eingabe ist der gespeicherte Standard CHF keine Korrektur. */
    @Test
    void standardWaehrungZaehltNurGegenEineVorgeschlageneWaehrung() throws SQLException {
        UUID ohne = dokument();
        double vorher = korrigiert("waehrung");

        inTransaktion(() -> lernen.lernen(URINET, ohne, VORHER, ergebnis("Rechnung", null, null)));

        assertThat(beispiele(ohne)).isZero();
        assertThat(korrigiert("waehrung")).isEqualTo(vorher);

        UUID euro = dokument();
        Map<Feld, String> mitEuro = new java.util.EnumMap<>(VORHER.vorschlag());
        mitEuro.put(Feld.WAEHRUNG, "EUR");
        KiBeispielPort.Lernstand vorschlagEuro = new KiBeispielPort.Lernstand(mitEuro, VORHER.volltext(),
                Optional.empty(), Optional.empty());

        inTransaktion(() -> lernen.lernen(URINET, euro, vorschlagEuro, ergebnis("Rechnung", null, null)));

        assertThat(beispiele(euro)).isOne();
        assertThat(korrigiert("waehrung")).isEqualTo(vorher + 1);
    }

    /** Tags zählen als Menge: andere Reihenfolge und Duplikate sind keine Korrektur. */
    @Test
    void tagsZaehlenAlsMenge() throws SQLException {
        Map<Feld, String> mitTags = new java.util.EnumMap<>(VORHER.vorschlag());
        mitTags.put(Feld.TAGS, "Büro, Steuern");
        KiBeispielPort.Lernstand vorschlag = new KiBeispielPort.Lernstand(mitTags, VORHER.volltext(),
                Optional.empty(), Optional.empty());
        UUID gleich = dokument();
        UUID anders = dokument();
        double vorher = korrigiert("tags");

        inTransaktion(() -> lernen.lernen(URINET, gleich, vorschlag,
                ergebnis("Rechnung", null, List.of("Steuern", " Büro", "Steuern"))));
        inTransaktion(() -> lernen.lernen(URINET, anders, vorschlag, ergebnis("Rechnung", null, List.of("Büro"))));

        assertThat(beispiele(gleich)).isZero();
        assertThat(beispiele(anders)).isOne();
        assertThat(korrigiert("tags")).isEqualTo(vorher + 1);
    }

    private static KorrekturLernen.Ergebnis ergebnis(String titel, String waehrung, List<String> tags) {
        return new KorrekturLernen.Ergebnis("LIEFERANTENBELEG", titel, null, null, null, true, null, null, null,
                waehrung, null, null, "INTERN", tags);
    }

    private UUID dokument() {
        UUID id = UUID.randomUUID();
        dokumente.add(id);
        return id;
    }

    private double korrigiert(String feld) {
        Counter zaehler = kennzahlen.find("archiv.ki.feld.korrigiert").tags("feld", feld, "mandant", URINET.wert())
                .counter();
        return zaehler == null ? 0 : zaehler.count();
    }

    private long beispiele(UUID dokument) throws SQLException {
        try (Connection verbindung = datenbank.get().getConnection();
             PreparedStatement abfrage = verbindung.prepareStatement("select count(*) from " + URINET.schema()
                     + ".ki_beispiel where dokument_id = ?")) {
            abfrage.setObject(1, dokument);
            try (ResultSet zeile = abfrage.executeQuery()) {
                zeile.next();
                return zeile.getLong(1);
            }
        }
    }

    private void inTransaktion(Runnable arbeit) {
        try {
            CompletableFuture.runAsync(() -> kontext.mit(URINET, () -> QuarkusTransaction.requiringNew().call(() -> {
                arbeit.run();
                return null;
            })), worker).get(1, TimeUnit.MINUTES);
        } catch (ExecutionException e) {
            throw e.getCause() instanceof RuntimeException laufzeit ? laufzeit : new IllegalStateException(e.getCause());
        } catch (InterruptedException | TimeoutException e) {
            throw new IllegalStateException(e);
        }
    }
}
