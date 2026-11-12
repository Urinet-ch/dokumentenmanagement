package ch.urinet.archiv.kontakt.adapter.out.persistence;

import ch.urinet.archiv.kontakt.domain.Kontakt;
import ch.urinet.archiv.kontakt.domain.KontaktAngaben;
import ch.urinet.archiv.kontakt.domain.KontaktTyp;
import ch.urinet.archiv.mandant.application.MandantKontext;
import ch.urinet.archiv.mandant.application.MandantProvisionierung;
import ch.urinet.archiv.mandant.domain.MandantCode;
import io.agroal.api.AgroalDataSource;
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
 * Kurzangaben der Import-Oberfläche ({@link JdbcKontaktRegister#finden(java.util.Collection)}, Aufgabe 4.4): Jeder
 * Mandant findet nur Kontakte seines Schemas (tech.md, Mandantentrennung in jedem Repository-Test; Review PR #72, S4).
 */
@QuarkusTest
class JdbcKontaktRegisterTest {

    private static final MandantCode URINET = new MandantCode("urinet");
    private static final MandantCode ZWEITER = new MandantCode("vhf-gsk");

    @Inject
    JdbcKontaktRegister register;

    @Inject
    MandantKontext kontext;

    @Inject
    MandantProvisionierung provisionierung;

    @Inject
    Instance<AgroalDataSource> datenbank;

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final List<Angelegt> angelegt = new ArrayList<>();

    private record Angelegt(MandantCode mandant, UUID id) {
    }

    @AfterEach
    void aufraeumen() throws SQLException {
        worker.shutdown();
        try (Connection verbindung = datenbank.get().getConnection()) {
            for (Angelegt kontakt : angelegt) {
                try (PreparedStatement loeschen = verbindung.prepareStatement(
                        "delete from " + kontakt.mandant().schema() + ".kontakt where id = ?")) {
                    loeschen.setObject(1, kontakt.id());
                    loeschen.executeUpdate();
                }
            }
        }
    }

    @Test
    void findenLiefertNurKontakteDesEigenenMandanten() throws SQLException {
        for (MandantCode code : List.of(URINET, ZWEITER)) {
            if (!vorhanden(code)) {
                provisionierung.anlegen(code.wert(), null, null);
            }
        }
        UUID eigener = anlegen(URINET, "Muster Garage AG");
        UUID fremder = anlegen(ZWEITER, "Beispiel Verein");
        List<UUID> beide = List.of(eigener, fremder);

        assertThat(im(URINET, () -> register.finden(beide))).extracting(Kontakt::id).containsExactly(eigener);
        assertThat(im(ZWEITER, () -> register.finden(beide))).extracting(Kontakt::id).containsExactly(fremder);
        assertThat(im(ZWEITER, () -> register.finden(List.of(eigener)))).isEmpty();
    }

    private UUID anlegen(MandantCode mandant, String name) {
        KontaktAngaben angaben = new KontaktAngaben(KontaktTyp.ORGANISATION, name, null, null, "6000", "Musterort",
                "info@beispiel.invalid", null, null, null, null);
        UUID id = im(mandant, () -> register.anlegen(angaben, name.toLowerCase(), "TEST")).id();
        angelegt.add(new Angelegt(mandant, id));
        return id;
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
