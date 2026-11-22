package ch.urinet.archiv.ki.adapter.in.rest;

import ch.urinet.archiv.gemeinsam.domain.FachFehler;
import ch.urinet.archiv.gemeinsam.domain.Fehlercode;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChatAuswertungResourceTest {

    @Test
    void begrenztChunkedAnfrageOhneContentLengthBeimLesen() {
        UnbegrenzterStrom strom = new UnbegrenzterStrom(ChatAuswertungResource.BODY_MAX * 10);

        assertThatThrownBy(() -> ChatAuswertungResource.eingabe(strom))
                .isInstanceOf(FachFehler.class)
                .extracting(fehler -> ((FachFehler) fehler).code())
                .isEqualTo(Fehlercode.DATEI_ZU_GROSS);

        assertThat(strom.gelesen).isEqualTo(ChatAuswertungResource.BODY_MAX + 1);
    }

    private static final class UnbegrenzterStrom extends InputStream {
        private int uebrig;
        private int gelesen;

        private UnbegrenzterStrom(int laenge) {
            uebrig = laenge;
        }

        @Override
        public int read() {
            if (uebrig == 0) {
                return -1;
            }
            uebrig--;
            gelesen++;
            return ' ';
        }

        @Override
        public int read(byte[] ziel, int offset, int laenge) {
            if (uebrig == 0) {
                return -1;
            }
            int anzahl = Math.min(uebrig, laenge);
            Arrays.fill(ziel, offset, offset + anzahl, (byte) ' ');
            uebrig -= anzahl;
            gelesen += anzahl;
            return anzahl;
        }
    }
}
