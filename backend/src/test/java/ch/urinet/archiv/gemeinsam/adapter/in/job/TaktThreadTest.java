package ch.urinet.archiv.gemeinsam.adapter.in.job;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Takt-Arbeit im eigenen Thread (Befund prod 13.11.2026, Spec 02 design.md «Umsetzung (Aufgabe 6.1)»). */
class TaktThreadTest {

    private static final ThreadLocal<String> NUR_IM_TAKT = new ThreadLocal<>();

    @Test
    void arbeitetImEigenenThreadMitDemClassLoaderDesTaktsUndWartet() {
        AtomicReference<Thread> thread = new AtomicReference<>();
        AtomicReference<String> wert = new AtomicReference<>("ungesetzt");
        AtomicReference<ClassLoader> lader = new AtomicReference<>();
        ClassLoader anwendung = new ClassLoader(TaktThreadTest.class.getClassLoader()) {
        };
        ClassLoader vorher = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(anwendung);
        NUR_IM_TAKT.set("vom Takt");
        try {
            TaktThread.ausfuehren("takt-test", () -> {
                thread.set(Thread.currentThread());
                wert.set(NUR_IM_TAKT.get());
                lader.set(Thread.currentThread().getContextClassLoader());
            });
        } finally {
            NUR_IM_TAKT.remove();
            Thread.currentThread().setContextClassLoader(vorher);
        }

        assertThat(thread.get()).isNotSameAs(Thread.currentThread());
        assertThat(thread.get().getName()).isEqualTo("takt-test");
        assertThat(thread.get().isAlive()).isFalse();
        assertThat(wert.get()).as("ThreadLocal wie der Request-Kontext geht nicht mit").isNull();
        assertThat(lader.get()).as("ServiceLoader braucht den ClassLoader der Anwendung").isSameAs(anwendung);
    }

    @Test
    void ausnahmeKommtUnveraendertBeimTaktAn() {
        IllegalStateException fehler = new IllegalStateException("fest");

        assertThatThrownBy(() -> TaktThread.ausfuehren("takt-test", () -> {
            throw fehler;
        })).isSameAs(fehler);
    }

    @Test
    void unterbrochenerTaktUnterbrichtDieArbeit() throws Exception {
        CountDownLatch laeuft = new CountDownLatch(1);
        CountDownLatch unterbrochen = new CountDownLatch(1);
        AtomicReference<Boolean> flag = new AtomicReference<>();
        Thread takt = Thread.ofPlatform().start(() -> {
            TaktThread.ausfuehren("takt-test", () -> {
                laeuft.countDown();
                try {
                    Thread.sleep(60_000);
                } catch (InterruptedException e) {
                    unterbrochen.countDown();
                }
            });
            flag.set(Thread.currentThread().isInterrupted());
        });
        assertThat(laeuft.await(10, TimeUnit.SECONDS)).isTrue();

        takt.interrupt();

        assertThat(unterbrochen.await(10, TimeUnit.SECONDS)).isTrue();
        takt.join(10_000);
        assertThat(flag.get()).isTrue();
    }
}
