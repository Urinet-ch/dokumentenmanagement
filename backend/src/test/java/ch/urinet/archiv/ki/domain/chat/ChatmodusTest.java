package ch.urinet.archiv.ki.domain.chat;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Spec 04, Requirement 21.1: nur die Modi ARCHIV und WISSEN, genau so geschrieben. */
class ChatmodusTest {

    @Test
    void kenntNurDieBeidenModi() {
        assertThat(Chatmodus.aus("ARCHIV")).contains(Chatmodus.ARCHIV);
        assertThat(Chatmodus.aus("WISSEN")).contains(Chatmodus.WISSEN);
        for (String ungueltig : new String[] {"", " ", "archiv", "Wissen", "WISSEN ", "ALLES", "null", null}) {
            assertThat(Chatmodus.aus(ungueltig)).as("«%s»", ungueltig).isEmpty();
        }
    }

    @Test
    void gespraechsKopfOhneModusIstArchiv() {
        assertThat(new GespraechKopf(java.util.UUID.randomUUID(), "t", java.time.Instant.EPOCH).modus())
                .isEqualTo(Chatmodus.ARCHIV);
    }
}
