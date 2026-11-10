package ch.urinet.archiv.sicherheit;

import io.cucumber.java.de.Angenommen;
import io.cucumber.java.de.Dann;

import static org.assertj.core.api.Assertions.assertThat;

/** Schritte zur Vorab-Korrektur 1 (Review PR #35): Verweigerungen werden nie auf dem Event-Loop protokolliert. */
public class MelderThreadSchritte {

    @Angenommen("die Threads des Verweigerungs-Melders werden beobachtet")
    public void beobachten() {
        MelderThreadPruefung.BLOCKIEREN_ERLAUBT.clear();
    }

    @Dann("lief der Melder nur auf Threads, die blockieren dürfen")
    public void nieAufDemEventLoop() {
        assertThat(MelderThreadPruefung.BLOCKIEREN_ERLAUBT).isNotEmpty().containsOnly(true);
    }
}
