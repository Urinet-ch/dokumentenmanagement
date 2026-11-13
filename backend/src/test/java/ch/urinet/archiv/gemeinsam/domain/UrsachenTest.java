package ch.urinet.archiv.gemeinsam.domain;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.UncheckedIOException;
import java.net.SocketTimeoutException;
import java.nio.channels.ClosedByInterruptException;

import static org.assertj.core.api.Assertions.assertThat;

/** Abfragen über die Ursachen-Kette (Review PR #97): Unterbrechung erkennen, Zeitüberschreitungen nicht. */
class UrsachenTest {

    /** Wie {@code ConnectTimeoutException} von Apache: Zeitüberschreitung, erbt aber von InterruptedIOException. */
    private static final class VerbindungTimeoutException extends InterruptedIOException {
    }

    @Test
    void unterbrechungIrgendwoInDerKette() {
        assertThat(Ursachen.unterbrechung(new InterruptedException())).isTrue();
        assertThat(Ursachen.unterbrechung(new IllegalStateException(new ClosedByInterruptException()))).isTrue();
        assertThat(Ursachen.unterbrechung(new RuntimeException(
                new UncheckedIOException(new InterruptedIOException("unterbrochen"))))).isTrue();
    }

    @Test
    void zeitueberschreitungIstKeineUnterbrechung() {
        assertThat(Ursachen.unterbrechung(new RuntimeException(new SocketTimeoutException("Read")))).isFalse();
        assertThat(Ursachen.unterbrechung(new UncheckedIOException(new VerbindungTimeoutException()))).isFalse();
        assertThat(Ursachen.unterbrechung(new IOException("anderes"))).isFalse();
    }

    @Test
    void zyklischeKetteEndet() {
        RuntimeException a = new RuntimeException("a");
        RuntimeException b = new RuntimeException("b", a);
        a.initCause(b);

        assertThat(Ursachen.unterbrechung(a)).isFalse();
        assertThat(Ursachen.enthaelt(a, IOException.class)).isFalse();
        assertThat(Ursachen.kette(a)).isEqualTo(RuntimeException.class.getName() + " <- "
                + RuntimeException.class.getName());
    }

    @Test
    void enthaeltPrueftDieGanzeKetteUndUntertypen() {
        RuntimeException fehler = new IllegalStateException(new UncheckedIOException(new SocketTimeoutException()));
        assertThat(Ursachen.enthaelt(fehler, SocketTimeoutException.class)).isTrue();
        assertThat(Ursachen.enthaelt(fehler, IOException.class)).isTrue();
        assertThat(Ursachen.enthaelt(fehler, IllegalStateException.class)).isTrue();
        assertThat(Ursachen.enthaelt(fehler, InterruptedException.class)).isFalse();
    }
}
