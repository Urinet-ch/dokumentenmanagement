package ch.urinet.archiv.ki.adapter.out.persistence;

import ch.urinet.archiv.ki.application.KiBeispielPort;
import ch.urinet.archiv.ki.domain.Beispiel;
import ch.urinet.archiv.ki.domain.BeispielSicht;
import ch.urinet.archiv.ki.domain.Feld;
import ch.urinet.archiv.ki.domain.Merkmale;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
 * {@code ki_beispiel} je Mandant (Aufgabe 3.3, Req 7.7, 7.9): Jeder Mandant liest nur die Beispiele seines Schemas
 * (tech.md, Mandantentrennung in jedem Repository-Test), und die Sicht filtert schon in der Abfrage.
 */
@QuarkusTest
class JdbcKiBeispielTest {

    private static final MandantCode URINET = new MandantCode("urinet");
    private static final MandantCode ZWEITER = new MandantCode("vhf-gsk");
    private static final BeispielSicht MITARBEITER = new BeispielSicht("joel", true, false);
    private static final BeispielSicht INHABER = new BeispielSicht("fabian", true, true);

    @Inject
    JdbcKiBeispiel beispiele;

    @Inject
    MandantKontext kontext;

    @Inject
    MandantProvisionierung provisionierung;

    @Inject
    Instance<AgroalDataSource> datenbank;

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final List<UUID> dokumente = new ArrayList<>();
    private Merkmale merkmale;

    @BeforeEach
    void mandanten() throws SQLException {
        for (MandantCode code : List.of(URINET, ZWEITER)) {
            if (!vorhanden(code)) {
                provisionierung.anlegen(code.wert(), null, null);
            }
        }
        merkmale = new Merkmale(Set.of(), Set.of(), Set.of("lauf-" + UUID.randomUUID() + "@beispiel.invalid"),
                Set.of());
    }

    @AfterEach
    void aufraeumen() throws SQLException {
        worker.shutdown();
        try (Connection verbindung = datenbank.get().getConnection()) {
            for (MandantCode code : List.of(URINET, ZWEITER)) {
                try (PreparedStatement loeschen = verbindung.prepareStatement(
                        "delete from " + code.schema() + ".ki_beispiel where dokument_id = any (?)")) {
                    loeschen.setArray(1, verbindung.createArrayOf("uuid", dokumente.toArray()));
                    loeschen.executeUpdate();
                }
            }
        }
    }

    @Test
    void jederMandantLiestNurSeineBeispiele() {
        speichern(URINET, "Titel Urinet", "NORMAL", null);
        speichern(ZWEITER, "Titel VHF", "NORMAL", null);

        assertThat(titel(im(URINET, () -> beispiele.kandidaten(merkmale, INHABER, 10)))).containsExactly("Titel Urinet");
        assertThat(titel(im(ZWEITER, () -> beispiele.kandidaten(merkmale, INHABER, 10)))).containsExactly("Titel VHF");
    }

    @Test
    void sichtUndMerkmaleFilternInDerAbfrage() {
        speichern(URINET, "normal", "NORMAL", null);
        speichern(URINET, "intern", "INTERN", null);
        speichern(URINET, "eigen", "PERSONAL", "joel");
        speichern(URINET, "fremd", "PERSONAL", "fabian");

        assertThat(titel(im(URINET, () -> beispiele.kandidaten(merkmale, MITARBEITER, 10))))
                .containsExactly("eigen", "normal");
        assertThat(titel(im(URINET, () -> beispiele.kandidaten(merkmale, INHABER, 10))))
                .containsExactly("fremd", "intern", "normal");
        Merkmale andere = new Merkmale(Set.of(), Set.of(), Set.of("andere-" + UUID.randomUUID() + "@beispiel.invalid"),
                Set.of());
        assertThat(im(URINET, () -> beispiele.kandidaten(andere, INHABER, 10))).isEmpty();
    }

    private void speichern(MandantCode mandant, String titel, String vertraulichkeit, String person) {
        UUID dokument = UUID.randomUUID();
        dokumente.add(dokument);
        im(mandant, () -> {
            beispiele.speichern(new KiBeispielPort.Neu(dokument, "KORRESPONDENZ", Map.of(Feld.TITEL, titel), merkmale,
                    vertraulichkeit, person, Optional.empty()));
            return null;
        });
    }

    private static List<String> titel(List<Beispiel> liste) {
        return liste.stream().map(b -> b.korrektur().get(Feld.TITEL)).toList();
    }

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
}
