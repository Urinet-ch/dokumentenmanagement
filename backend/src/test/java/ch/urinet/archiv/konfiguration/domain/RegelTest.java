package ch.urinet.archiv.konfiguration.domain;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RegelTest {

    @Test
    void parstNurDieVierDeklarativenRegeltypen() {
        assertThat(regel("PFLICHT_WENN", Map.of(
                "wenn", Map.of("feld", "datum", "gleich", "2026-01-01"),
                "dann", List.of("zusatz.auftrag"))).typisierteParameter())
                .isEqualTo(new Regel.PflichtWenn("datum", "2026-01-01", List.of("zusatz.auftrag")));
        assertThat(regel("EINDEUTIG", Map.of(
                "felder", List.of("belegnummer"), "ueberAlleArten", true)).typisierteParameter())
                .isEqualTo(new Regel.Eindeutig(List.of("belegnummer"), true));
        assertThat(regel("FOLGE", Map.of(
                "muster", true, "zeitraum", Map.of("feld", "zusatz.jahr"), "beginn", 3)).typisierteParameter())
                .isEqualTo(new Regel.Folge(null, true, Regel.Zeitraum.FELD, "zusatz.jahr", 3));
        assertThat(regel("ERWARTET", Map.of(
                "je", "MONAT", "datum", "BELEGDATUM", "ab", "2026-01-01",
                "nurMonat", "zusatz.periode")).typisierteParameter())
                .isEqualTo(new Regel.Erwartet("MONAT", "BELEGDATUM", "2026-01-01", "zusatz.periode"));
    }

    @Test
    void lehntFreieAusdrueckeUnbekannteParameterUndUngueltigeDatenAb() {
        assertThatThrownBy(() -> regel("PFLICHT_WENN", Map.of("script", "true"))
                .typisierteParameter()).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> regel("PFLICHT_WENN", Map.of(
                "wenn", Map.of("feld", "datum", "gleich", "2026-01-01"), "dann", List.of()))
                .typisierteParameter()).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> regel("EINDEUTIG", Map.of(
                "felder", List.of("titel"), "ausdruck", "true")).typisierteParameter())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> regel("FOLGE", Map.of(
                "feld", "nummer", "zeitraum", "UNGÜLTIG")).typisierteParameter())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> regel("ERWARTET", Map.of(
                "je", "MONAT", "datum", "BELEGDATUM", "ab", "morgen")).typisierteParameter())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> regel("ERWARTET", Map.of(
                "je", "MONAT", "datum", "BELEGDATUM", "nurMonat", "datum")).typisierteParameter())
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void erwartetKenntDieGruppeJePartnerOderZusatzfeldNurJeMonat() {
        assertThat(regel("ERWARTET", Map.of("je", "MONAT", "datum", "BELEGDATUM", "gruppe", "zusatz.person"))
                .typisierteParameter())
                .isEqualTo(new Regel.Erwartet("MONAT", "BELEGDATUM", null, null, "zusatz.person"));
        assertThat(regel("ERWARTET", Map.of("je", "MONAT", "datum", "zusatz.periode_bis", "gruppe", "PARTNER"))
                .typisierteParameter().felder()).containsExactly("zusatz.periode_bis");
        assertThat(regel("ERWARTET", Map.of("je", "MONAT", "datum", "BELEGDATUM", "gruppe", "zusatz.person"))
                .typisierteParameter().felder()).contains("zusatz.person");
        assertThatThrownBy(() -> regel("ERWARTET", Map.of(
                "je", "MONAT", "datum", "BELEGDATUM", "gruppe", "titel")).typisierteParameter())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> regel("ERWARTET", Map.of(
                "je", "JAHR", "datum", "BELEGDATUM", "gruppe", "PARTNER")).typisierteParameter())
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void kopiertParameterRekursivUnveraenderlich() {
        var felder = new ArrayList<>(List.of("titel"));
        var parameter = new java.util.HashMap<String, Object>();
        parameter.put("felder", felder);
        var regel = regel("EINDEUTIG", parameter);

        felder.add("datum");
        assertThat(regel.typisierteParameter()).isEqualTo(new Regel.Eindeutig(List.of("titel"), false));
        assertThatThrownBy(() -> ((List<?>) regel.parameter().get("felder")).clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    private static Regel regel(String typ, Map<String, Object> parameter) {
        return new Regel("testregel", "RECHNUNG", typ, parameter, "WARNUNG", "Regeltest", true);
    }
}
