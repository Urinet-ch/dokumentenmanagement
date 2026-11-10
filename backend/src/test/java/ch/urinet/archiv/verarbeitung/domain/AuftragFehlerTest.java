package ch.urinet.archiv.verarbeitung.domain;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** {@code auftrag.fehler} enthält nur Art und Fehler-ID (Req 16.7). */
class AuftragFehlerTest {

    @Test
    void textHinUndZurueck() {
        AuftragFehler fehler = AuftragFehler.neu("zeitgrenze");
        assertThat(fehler.text()).matches("zeitgrenze fehler-id=[0-9a-f-]{36}");
        assertThat(AuftragFehler.lesen(fehler.text())).contains(fehler);
    }

    @Test
    void andereTexteSindNichtLesbar() {
        UUID id = UUID.randomUUID();
        assertThat(AuftragFehler.lesen(null)).isEmpty();
        assertThat(AuftragFehler.lesen("Beleg FC Beispiel fehler-id=" + id)).isEmpty();
        assertThat(AuftragFehler.lesen("fehlgeschlagen fehler-id=" + id + " FC Beispiel")).isEmpty();
        assertThatThrownBy(() -> AuftragFehler.neu("FC Beispiel")).isInstanceOf(IllegalArgumentException.class)
                .message().doesNotContain("FC Beispiel");
    }
}
