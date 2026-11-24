package ch.urinet.archiv.benutzer;

import ch.urinet.archiv.benutzer.adapter.in.kommando.InhaberEinladenKommando;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class InhaberEinladenKommandoTest {

    private static final List<String> AUFRUF = List.of("inhaber-einladen", "urinet",
            "--benutzer", "test.person", "--email", "test@example.invalid", "--vorname", "Test",
            "--nachname", "Person", "--wer", "betreiber");

    @Test
    void vollstaendigerAufrufWirdOhnePersonendatenInDerTextdarstellungGelesen() {
        assertThat(InhaberEinladenKommando.lesen(AUFRUF)).hasValueSatisfying(aufruf -> {
            assertThat(aufruf.mandant().wert()).isEqualTo("urinet");
            assertThat(aufruf.eingabe().rolle()).isEqualTo("inhaber");
            assertThat(aufruf.toString()).doesNotContain("test.person", "test@example.invalid");
        });
    }

    @Test
    void unvollstaendigeDoppelteUndUebergrosseOptionenWerdenAbgewiesen() {
        assertThat(InhaberEinladenKommando.lesen(AUFRUF.subList(0, 10))).isEmpty();
        var doppelt = new ArrayList<>(AUFRUF);
        doppelt.set(10, "--benutzer");
        assertThat(InhaberEinladenKommando.lesen(doppelt)).isEmpty();
        var gross = new ArrayList<>(AUFRUF);
        gross.set(5, "a".repeat(255));
        assertThat(InhaberEinladenKommando.lesen(gross)).isEmpty();
        var steuerzeichen = new ArrayList<>(AUFRUF);
        steuerzeichen.set(11, "test\nbetreiber");
        assertThat(InhaberEinladenKommando.lesen(steuerzeichen)).isEmpty();
    }
}
