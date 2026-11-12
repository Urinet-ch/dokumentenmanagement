package ch.urinet.archiv.suche.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Textstelle aus {@code ts_headline} ohne HTML (Req 14.4). */
class AuszugTest {

    @Test
    void zerlegtTrefferUndFragmente() {
        String markiert = "Lieferung von \u0002Trikots\u0003 für\u001fdie \u0002Trikots\u0003";
        assertThat(Auszug.zerlegen(markiert)).containsExactly(
                new AuszugTeil("Lieferung von ", false),
                new AuszugTeil("Trikots", true),
                new AuszugTeil(" für … die ", false),
                new AuszugTeil("Trikots", true));
    }

    @Test
    void htmlImDokumenttextBleibtText() {
        assertThat(Auszug.zerlegen("<b>\u0002x\u0003</b>")).containsExactly(
                new AuszugTeil("<b>", false), new AuszugTeil("x", true), new AuszugTeil("</b>", false));
    }

    @Test
    void ohneTextLeer() {
        assertThat(Auszug.zerlegen(null)).isEmpty();
        assertThat(Auszug.zerlegen("")).isEmpty();
    }
}
