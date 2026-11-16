package ch.urinet.archiv.ki.application;

import ch.urinet.archiv.ki.domain.Feld;
import ch.urinet.archiv.mandant.domain.MandantCode;
import jakarta.transaction.Status;
import jakarta.transaction.Synchronization;
import jakarta.transaction.TransactionSynchronizationRegistry;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Die Zähler der Korrekturquote zählen nur, was wirklich gespeichert wird (Req 7.7, 16.4): ohne Transaktion sofort,
 * in einer aktiven Transaktion erst nach dem Commit, in einer zum Zurückrollen vorgemerkten nie.
 */
class KorrekturLernenZaehlenTest {

    private static final MandantCode URINET = new MandantCode("urinet");

    private int status = Status.STATUS_ACTIVE;
    private final List<Synchronization> angemeldet = new ArrayList<>();
    private final List<Set<Feld>> gezaehlt = new ArrayList<>();

    private final TransactionSynchronizationRegistry registry = (TransactionSynchronizationRegistry) Proxy
            .newProxyInstance(getClass().getClassLoader(), new Class<?>[]{TransactionSynchronizationRegistry.class},
                    (proxy, methode, args) -> switch (methode.getName()) {
                        case "getTransactionStatus" -> status;
                        case "registerInterposedSynchronization" -> angemeldet.add((Synchronization) args[0]);
                        default -> throw new UnsupportedOperationException(methode.getName());
                    });

    private final KorrekturLernen lernen = new KorrekturLernen(null, (mandant, bestaetigt, korrigiert) ->
            gezaehlt.add(bestaetigt), null, registry, Duration.ofSeconds(1));

    /** Vorschlag und Ergebnis stimmen überein: nichts zu korrigieren, also weder Beispiel noch Datenbankzugriff. */
    private void lernen() {
        KiBeispielPort.Lernstand vorher = new KiBeispielPort.Lernstand(
                Map.of(Feld.ART, "LIEFERANTENBELEG", Feld.VERTRAULICHKEIT, "INTERN"), "", Optional.empty(),
                Optional.empty());
        lernen.lernen(URINET, UUID.randomUUID(), vorher, new KorrekturLernen.Ergebnis("LIEFERANTENBELEG", null, null,
                null, null, false, null, null, null, null, null, null, "INTERN", List.of()));
    }

    @Test
    @Tag("req-7.7")
    void ohneTransaktionZaehltSofort() {
        status = Status.STATUS_NO_TRANSACTION;

        lernen();

        assertThat(gezaehlt).containsExactly(Set.of(Feld.ART, Feld.VERTRAULICHKEIT));
        assertThat(angemeldet).isEmpty();
    }

    @Test
    @Tag("req-7.7")
    void inAktiverTransaktionZaehltErstDerCommitNichtDerRollback() {
        lernen();
        assertThat(gezaehlt).isEmpty();
        angemeldet.get(0).afterCompletion(Status.STATUS_ROLLEDBACK);
        assertThat(gezaehlt).isEmpty();

        lernen();
        angemeldet.get(1).afterCompletion(Status.STATUS_COMMITTED);
        assertThat(gezaehlt).hasSize(1);
    }

    @Test
    @Tag("req-7.7")
    void zumRollbackVorgemerkteTransaktionZaehltNie() {
        status = Status.STATUS_MARKED_ROLLBACK;

        lernen();
        angemeldet.forEach(s -> s.afterCompletion(Status.STATUS_ROLLEDBACK));

        assertThat(gezaehlt).isEmpty();
    }
}
