package ch.urinet.archiv.ki.adapter.in.rest;

import ch.urinet.archiv.gemeinsam.domain.FachFehler;
import ch.urinet.archiv.gemeinsam.domain.Fehlercode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Spec 04, Requirement 21.5: Anfragekörper von {@code POST /chat/gespraeche}. */
class ChatResourceModusTest {

    @Test
    void leererKoerperOderOhneModusIstKeinModus() {
        assertThat(ChatResource.gespraechModus(null)).isNull();
        assertThat(ChatResource.gespraechModus("")).isNull();
        assertThat(ChatResource.gespraechModus("  ")).isNull();
        assertThat(ChatResource.gespraechModus("{}")).isNull();
        assertThat(ChatResource.gespraechModus("{\"modus\":null}")).isNull();
        assertThat(ChatResource.gespraechModus("{\"modus\":\"WISSEN\",\"anderes\":1}")).isEqualTo("WISSEN");
    }

    @Test
    void keinObjektOderModusKeinTextIstUngueltig() {
        for (String ungueltig : new String[] {"[]", "\"WISSEN\"", "42", "{\"modus\":1}", "{\"modus\":{}}",
                "{\"modus\":[\"WISSEN\"]}", "{"}) {
            assertThatThrownBy(() -> ChatResource.gespraechModus(ungueltig)).as(ungueltig)
                    .isInstanceOfSatisfying(FachFehler.class, e -> assertThat(e.code()).isEqualTo(Fehlercode.ANFRAGE_UNGUELTIG));
        }
    }
}
