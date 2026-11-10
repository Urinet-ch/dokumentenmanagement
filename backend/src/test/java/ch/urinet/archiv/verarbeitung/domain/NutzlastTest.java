package ch.urinet.archiv.verarbeitung.domain;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Req 16.7: Die Nutzlast trägt nur IDs und Schalter, Fehlermeldungen nennen nie den Wert. */
class NutzlastTest {

    @Test
    void nurUuidZahlUndSchalter() {
        UUID id = UUID.randomUUID();
        Nutzlast nutzlast = new Nutzlast(Map.of("dokumentId", id, "seite", 3, "erneut", true));
        assertThat(nutzlast.uuid("dokumentId")).contains(id);
        assertThat(nutzlast.zahl("seite")).hasValue(3L);
        assertThat(nutzlast.schalter("erneut")).contains(true);

        assertThatThrownBy(() -> new Nutzlast(Map.of("kunde", "FC Beispiel")))
                .isInstanceOf(IllegalArgumentException.class).message().doesNotContain("FC Beispiel");
        assertThatThrownBy(() -> new Nutzlast(Map.of("betrag", new java.math.BigDecimal("12.50"))))
                .isInstanceOf(IllegalArgumentException.class).message().doesNotContain("12.50");
    }

    @Test
    void schluesselUndAnzahlBegrenzt() {
        assertThatThrownBy(() -> new Nutzlast(Map.of("Gross", 1))).isInstanceOf(IllegalArgumentException.class);
        Map<String, Object> viele = new HashMap<>();
        for (int i = 0; i <= Nutzlast.MAX_EINTRAEGE; i++) {
            viele.put("k" + i, i);
        }
        assertThatThrownBy(() -> new Nutzlast(viele)).isInstanceOf(IllegalArgumentException.class);
    }
}
