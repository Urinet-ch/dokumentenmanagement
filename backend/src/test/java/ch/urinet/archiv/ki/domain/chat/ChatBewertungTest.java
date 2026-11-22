package ch.urinet.archiv.ki.domain.chat;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class ChatBewertungTest {

    private static final Instant JETZT = Instant.parse("2026-11-22T12:00:00Z");

    @Test
    void entferntSteuerzeichenUndErhaeltFreitextAlsText() {
        ChatBewertung bewertung = new ChatBewertung(1, 3, ChatBewertung.Grund.ANDERES,
                "Text\u0000<b>bleibt Text</b>\n weiter", JETZT, JETZT);

        assertThat(bewertung.freitext()).isEqualTo("Text<b>bleibt Text</b> weiter");
    }

    @Test
    void begrenztDenFreitextAuf500UnicodeZeichen() {
        assertThat(new ChatBewertung(1, 5, null, "ä".repeat(500), JETZT, JETZT).freitext())
                .hasSize(500);
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ChatBewertung(1, 5, null, "ä".repeat(501), JETZT, JETZT));
    }
}
