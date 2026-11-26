package ch.urinet.archiv.gemeinsam;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EinstellungsInventarTest {
    @Test
    void alleKonfigurationsschluesselSindImInventar() throws IOException {
        assertThat(EinstellungsInventar.luecken()).isEmpty();
    }

    @Test
    void alleInventareintraegeEntsprechenAktuellenKonfigurationsschluesseln() throws IOException {
        assertThat(EinstellungsInventar.veralteteInventareintraege()).isEmpty();
    }

    @Test
    void dokumentierteSchluesselSindImCodeOderBegruendeteAusnahme() throws IOException {
        assertThat(EinstellungsInventar.undokumentierteReferenzen()).isEmpty();
        assertThat(EinstellungsInventar.verwendeteAusnahmen())
                .containsExactlyInAnyOrder(
                        "archiv.apps.urinet.ch",
                        "archiv.dump",
                        "archiv.einfuehrung",
                        "archiv.example.invalid",
                        "archiv.ki.token-datei",
                        "archiv.php",
                        "archiv.tsa.url");
    }

    @Test
    void unbekannteUndMehrdeutigeSchluesselWerdenNichtStillKlassifiziert() {
        var inventar = EinstellungsInventar.inventar(List.of(
                "### Klasse (a)", "| Wert | `archiv.chat.aufbewahrung` | Grenzen |",
                "### Klasse (b)", "| `archiv.chat.*` | Betrieb | Grund |"));
        assertThat(EinstellungsInventar.klasse("archiv.chat.aufbewahrung", inventar)).isEqualTo('a');
        assertThat(EinstellungsInventar.klasse("archiv.chat.rate", inventar)).isEqualTo('b');
        assertThat(EinstellungsInventar.klasse("archiv.unbekannt", inventar)).isNull();
        var doppelt = EinstellungsInventar.inventar(List.of(
                "### Klasse (a)", "| Wert | `archiv.chat.rate` | Grenzen |",
                "### Klasse (b)", "| `archiv.chat.rate` | Betrieb | Grund |"));
        assertThatThrownBy(() -> EinstellungsInventar.klasse("archiv.chat.rate", doppelt))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void testprofilWirdUebersprungenAberAndereProfileWerdenGeprueft() {
        assertThat(EinstellungsInventar.schluessel("%test.archiv.neu")).isNull();
        assertThat(EinstellungsInventar.schluessel("%dev.archiv.neu")).isEqualTo("archiv.neu");
        assertThat(EinstellungsInventar.schluessel("%test,dev.archiv.neu")).isEqualTo("archiv.neu");
        assertThat(EinstellungsInventar.schluessel("quarkus.neu")).isNull();
    }
}
