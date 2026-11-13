package ch.urinet.archiv.verarbeitung.adapter.out.persistence;

import ch.urinet.archiv.ablage.domain.Stufe;
import ch.urinet.archiv.ablage.domain.Versionsseite;
import ch.urinet.archiv.mandant.application.MandantKontext;
import ch.urinet.archiv.mandant.application.MandantProvisionierung;
import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.verarbeitung.application.PrueflaufRegister.Fortsetzung;
import ch.urinet.archiv.verarbeitung.application.PrueflaufRegister.Lauf;
import ch.urinet.archiv.verarbeitung.application.PrueflaufRegister.Phase;
import ch.urinet.archiv.verarbeitung.application.PrueflaufRegister.Zaehler;
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
import java.util.Optional;
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
 * Marke des Prüflaufs im Register (Review PR #97): Eine Marke ohne Versions-ID (ältere Zeile, {@code marke_version}
 * leer) kommt ohne Versions-ID zurück, eine mit Versions-ID unverändert; jeder Mandant sieht nur seine Läufe.
 */
@QuarkusTest
class JdbcPrueflaufRegisterTest {

    private static final MandantCode URINET = new MandantCode("urinet");
    private static final MandantCode ZWEITER = new MandantCode("vhf-gsk");
    private static final String SCHLUESSEL = "archiv/2026/" + UUID.randomUUID() + "/1-original.pdf";

    @Inject
    JdbcPrueflaufRegister register;

    @Inject
    MandantKontext kontext;

    @Inject
    MandantProvisionierung provisionierung;

    @Inject
    Instance<AgroalDataSource> datenbank;

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final List<UUID> laeufe = new ArrayList<>();

    @AfterEach
    void aufraeumen() throws SQLException {
        worker.shutdown();
        try (Connection verbindung = datenbank.get().getConnection();
             PreparedStatement loeschen = verbindung.prepareStatement("delete from " + URINET.schema()
                     + ".prueflauf where id = ?")) {
            for (UUID lauf : laeufe) {
                loeschen.setObject(1, lauf);
                loeschen.executeUpdate();
            }
        }
    }

    @Test
    void markeOhneUndMitVersionsIdHinUndZurueck() throws SQLException {
        for (MandantCode code : List.of(URINET, ZWEITER)) {
            if (!vorhanden(code)) {
                provisionierung.anlegen(code.wert(), null, null);
            }
        }
        UUID id = im(URINET, () -> register.anlegen(Optional.empty(), Stufe.A)).orElseThrow();
        laeufe.add(id);

        Versionsseite.Marke ohne = new Versionsseite.Marke(SCHLUESSEL, Optional.empty());
        assertThat(im(URINET, () -> register.fortschreiben(id, 1, new Fortsetzung(Phase.ARCHIV_VERSIONEN,
                Optional.empty(), Optional.of(ohne)), Zaehler.NULL, List.of()))).isTrue();
        assertThat(im(URINET, () -> register.laden(id)).map(Lauf::stand).flatMap(Fortsetzung::marke)).contains(ohne);

        Versionsseite.Marke mit = new Versionsseite.Marke(SCHLUESSEL, "v1");
        assertThat(im(URINET, () -> register.fortschreiben(id, 2, new Fortsetzung(Phase.ARCHIV_VERSIONEN,
                Optional.empty(), Optional.of(mit)), Zaehler.NULL, List.of()))).isTrue();
        assertThat(im(URINET, () -> register.laden(id)).map(Lauf::stand).flatMap(Fortsetzung::marke)).contains(mit);

        assertThat(im(ZWEITER, () -> register.laden(id))).isEmpty();
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
