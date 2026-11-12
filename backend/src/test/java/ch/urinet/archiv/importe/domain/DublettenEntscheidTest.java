package ch.urinet.archiv.importe.domain;

import ch.urinet.archiv.importe.domain.ZeilenPruefung.Ergebnis;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Dubletten-Entscheide der Übernahme (Req 13.6). */
class DublettenEntscheidTest {

    private static final UUID KANDIDAT = UUID.fromString("00000000-0000-4000-8000-00000000000a");
    private static final UUID FREMD = UUID.fromString("00000000-0000-4000-8000-00000000000b");

    private static ImportZeile zeile(Ergebnis ergebnis) {
        return new ImportZeile(2, List.of("x"), ergebnis, null, null, List.of(KANDIDAT));
    }

    @Test
    void liestDieDreiArten() {
        assertThat(DublettenEntscheid.lesen("NEU")).contains(new DublettenEntscheid(DublettenEntscheid.Art.NEU, null));
        assertThat(DublettenEntscheid.lesen("UEBERSPRINGEN"))
                .contains(new DublettenEntscheid(DublettenEntscheid.Art.UEBERSPRINGEN, null));
        assertThat(DublettenEntscheid.lesen("ZUSAMMENFUEHREN:" + KANDIDAT))
                .contains(new DublettenEntscheid(DublettenEntscheid.Art.ZUSAMMENFUEHREN, KANDIDAT));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "neu", "ZUSAMMENFUEHREN", "ZUSAMMENFUEHREN:", "ZUSAMMENFUEHREN:1-1-1-1-1",
            "LOESCHEN"})
    void lehntUnbekanntesAb(String text) {
        assertThat(DublettenEntscheid.lesen(text)).isEmpty();
    }

    @Test
    void passtNurAufDublettenUndKandidaten() {
        DublettenEntscheid neu = DublettenEntscheid.lesen("NEU").orElseThrow();
        assertThat(neu.passt(zeile(Ergebnis.DUBLETTE))).isTrue();
        assertThat(neu.passt(zeile(Ergebnis.NEU))).isFalse();
        assertThat(DublettenEntscheid.lesen("ZUSAMMENFUEHREN:" + KANDIDAT).orElseThrow()
                .passt(zeile(Ergebnis.DUBLETTE))).isTrue();
        assertThat(DublettenEntscheid.lesen("ZUSAMMENFUEHREN:" + FREMD).orElseThrow()
                .passt(zeile(Ergebnis.DUBLETTE))).isFalse();
    }
}
