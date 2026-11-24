package ch.urinet.archiv.integration;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OeffentlicherChatSchritteTest {
    @Test
    void entferntNurDieZufaelligeGespraechsIdAusDerGeprueftenAntwort() {
        String sauber = """
                data:{"gespraechId":"79bccd1d-0879-429d-a65c-e9ea500fcc3c","meldung":"Nicht verfügbar"}
                """;
        assertThat(OeffentlicherChatSchritte.ohneZufaelligeKennungen(sauber)).doesNotContain("500");

        String antwort = """
                data:{"gespraechId":"79bccd1d-0879-429d-a65c-e9ea500fcc3c","meldung":"HTTP 500: simuliert"}
                """;
        assertThat(OeffentlicherChatSchritte.ohneZufaelligeKennungen(antwort))
                .contains("\"gespraechId\":\"-\"")
                .contains("HTTP 500: simuliert")
                .doesNotContain("79bccd1d-0879-429d-a65c-e9ea500fcc3c");
    }
}
