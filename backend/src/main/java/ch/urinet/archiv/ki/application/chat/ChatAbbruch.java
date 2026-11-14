package ch.urinet.archiv.ki.application.chat;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Abbruch-Signal eines Durchgangs: gesetzt, wenn der Client den Stream schliesst (Spec 02, Aufgabe 7.3). Der Adapter des
 * {@link ChatAgentPort} prüft es vor jedem Modell- und Werkzeugaufruf und kehrt danach ohne weitere Aufrufe zurück;
 * Ereignisse nach dem Abbruch gehen nicht mehr an den Client.
 */
public final class ChatAbbruch {

    private final CountDownLatch signal = new CountDownLatch(1);

    public void setzen() {
        signal.countDown();
    }

    public boolean gesetzt() {
        return signal.getCount() == 0;
    }

    /** Wartet höchstens {@code dauer} auf den Abbruch; {@code true}, wenn er gesetzt ist. */
    public boolean warten(Duration dauer) throws InterruptedException {
        return signal.await(dauer.toMillis(), TimeUnit.MILLISECONDS);
    }
}
