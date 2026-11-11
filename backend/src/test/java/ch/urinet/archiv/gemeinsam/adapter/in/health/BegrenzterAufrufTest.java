package ch.urinet.archiv.gemeinsam.adapter.in.health;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Zeitlimit der Wächter-Prüfung (Review PR #42, Runde 2, K1): Ein hängendes Keycloak hält den Wächter nicht an. */
// Ohne Zeitlimit hinge jeder Test; das Zeitlimit von JUnit macht ihn dann rot
@Timeout(value = 10, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class BegrenzterAufrufTest {

    private final BegrenzterAufruf<Boolean> aufruf = new BegrenzterAufruf<>("test-pruefung", Duration.ofMillis(200));
    private final CountDownLatch freigabe = new CountDownLatch(1);
    private final AtomicInteger gestartet = new AtomicInteger();

    @AfterEach
    void schliessen() {
        freigabe.countDown();
        aufruf.close();
    }

    private Boolean haengt() throws InterruptedException {
        gestartet.incrementAndGet();
        freigabe.await();
        return true;
    }

    @Test
    void liefertDasErgebnis() throws Exception {
        assertThat(aufruf.ausfuehren(() -> true)).contains(true);
    }

    @Test
    void haengenderAufrufIstNachDemZeitlimitLeer() throws Exception {
        assertThat(aufruf.ausfuehren(this::haengt)).isEmpty();
    }

    @Test
    void solangeErHaengtStartetKeinZweiter() throws Exception {
        assertThat(aufruf.ausfuehren(this::haengt)).isEmpty();
        assertThat(aufruf.ausfuehren(this::haengt)).isEmpty();
        assertThat(aufruf.ausfuehren(this::haengt)).isEmpty();
        assertThat(gestartet).hasValue(1);

        // nach der Antwort läuft höchstens ein neuer Aufruf, kein Rückstau aus der Wartezeit
        freigabe.countDown();
        assertThat(aufruf.ausfuehren(this::haengt)).contains(true);
        assertThat(gestartet.get()).isLessThanOrEqualTo(2);
        assertThat(aufruf.ausfuehren(() -> false)).contains(false);
    }

    @Test
    void fehlerDesAufrufsWirdWeitergereicht() {
        assertThatThrownBy(() -> aufruf.ausfuehren(() -> {
            throw new IllegalStateException("kaputt");
        })).isInstanceOf(ExecutionException.class).hasCauseInstanceOf(IllegalStateException.class);
    }
}
