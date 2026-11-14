package ch.urinet.archiv.kontakt.adapter.in.rest;

import ch.urinet.archiv.gemeinsam.domain.FachFehler;
import ch.urinet.archiv.gemeinsam.domain.Fehlercode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Längen- und Parameterprüfung der Kontaktliste und des Grunds (Req 17.1, 17.8, 17.11), ohne Quarkus. */
class KontaktListeParameterTest {

    @Test
    void suchbegriffBisZweihundertZeichenOhneSteuerzeichen() {
        assertThat(KontaktResource.suchbegriff(null)).isNull();
        assertThat(KontaktResource.suchbegriff("   ")).isNull();
        assertThat(KontaktResource.suchbegriff("x".repeat(200))).hasSize(200);
        // Zeichen, nicht UTF-16-Einheiten: 200 Emoji sind 400 chars
        assertThat(KontaktResource.suchbegriff("😀".repeat(200))).isNotNull();
        abgelehnt(() -> KontaktResource.suchbegriff("x".repeat(201)));
        abgelehnt(() -> KontaktResource.suchbegriff("Probe\tHolz"));
        abgelehnt(() -> KontaktResource.suchbegriff("Probe\u0000"));
    }

    @Test
    void seiteUndGroesseNurImErlaubtenBereich() {
        assertThat(KontaktResource.zahl(null, 50, 1, 100)).isEqualTo(50);
        assertThat(KontaktResource.zahl("1", 50, 1, 100)).isEqualTo(1);
        assertThat(KontaktResource.zahl("100", 50, 1, 100)).isEqualTo(100);
        assertThat(KontaktResource.zahl("0", 0, 0, Integer.MAX_VALUE)).isZero();
        abgelehnt(() -> KontaktResource.zahl("0", 50, 1, 100));
        abgelehnt(() -> KontaktResource.zahl("101", 50, 1, 100));
        abgelehnt(() -> KontaktResource.zahl("-1", 0, 0, Integer.MAX_VALUE));
        abgelehnt(() -> KontaktResource.zahl("zwei", 50, 1, 100));
        abgelehnt(() -> KontaktResource.zahl("99999999999", 0, 0, Integer.MAX_VALUE));
        abgelehnt(() -> KontaktResource.zahl("", 50, 1, 100));
    }

    @Test
    void grundPflichtUndHoechstensTausendZeichen() {
        assertThat(KontaktResource.grund("  Doppelt  ", true)).isEqualTo("Doppelt");
        assertThat(KontaktResource.grund("x".repeat(1000), true)).hasSize(1000);
        assertThat(KontaktResource.grund(null, false)).isNull();
        assertThat(KontaktResource.grund(" ", false)).isNull();
        abgelehnt(() -> KontaktResource.grund(null, true));
        abgelehnt(() -> KontaktResource.grund("  ", true));
        abgelehnt(() -> KontaktResource.grund("x".repeat(1001), false));
    }

    private static void abgelehnt(Runnable aufruf) {
        assertThatThrownBy(aufruf::run).isInstanceOfSatisfying(FachFehler.class,
                f -> assertThat(f.code()).isEqualTo(Fehlercode.ANFRAGE_UNGUELTIG));
    }
}
