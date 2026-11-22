package ch.urinet.archiv.ki.adapter.in.rest;

import ch.urinet.archiv.gemeinsam.domain.FachFehler;
import ch.urinet.archiv.gemeinsam.domain.Fehlercode;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChatResourceBewertungTest {

    @Test
    void liestHoechstensDieGrenzeAuchBeiUnbegrenztLangerEingabe() {
        var eingabe = new ByteArrayInputStream(new byte[ChatResource.BEWERTUNG_KOERPER_GRENZE * 4]);
        assertThatThrownBy(() -> ChatResource.bewertungKoerper(eingabe))
                .isInstanceOfSatisfying(FachFehler.class,
                        fehler -> assertThat(fehler.code()).isEqualTo(Fehlercode.DATEI_ZU_GROSS));
        assertThat(eingabe.available()).isEqualTo(ChatResource.BEWERTUNG_KOERPER_GRENZE * 3);
    }

    @Test
    void nimmtEingabeKnappUnterDerGrenzeAn() throws IOException {
        String text = " ".repeat(ChatResource.BEWERTUNG_KOERPER_GRENZE - 1);
        assertThat(ChatResource.bewertungKoerper(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8))))
                .isEqualTo(text);
    }
}
