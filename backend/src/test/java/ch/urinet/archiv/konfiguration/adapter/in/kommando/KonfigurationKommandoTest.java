package ch.urinet.archiv.konfiguration.adapter.in.kommando;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class KonfigurationKommandoTest {

    @Test
    void einspielenVerlangtWerUndGrund() {
        assertThat(KonfigurationKommando.lesen(List.of("einspielen", "urinet", "konfiguration.json",
                "--wer", "Betreiber", "--grund", "Korrektur"))).isPresent()
                .get().satisfies(aufruf -> {
                    assertThat(aufruf.operation()).isEqualTo(KonfigurationKommando.Operation.EINSPIELEN);
                    assertThat(aufruf.wer()).isEqualTo("Betreiber");
                    assertThat(aufruf.grund()).isEqualTo("Korrektur");
                });
    }

    @Test
    void einspielenLehntFehlendeOderDoppelteAnlassOptionenAb() {
        assertThat(KonfigurationKommando.lesen(List.of("einspielen", "urinet", "konfiguration.json",
                "--wer", "Betreiber"))).isEmpty();
        assertThat(KonfigurationKommando.lesen(List.of("einspielen", "urinet", "konfiguration.json",
                "--wer", "Betreiber", "--wer", "Andere", "--grund", "Korrektur"))).isEmpty();
        assertThat(KonfigurationKommando.lesen(List.of("einspielen", "urinet", "konfiguration.json",
                "--wer", "Betreiber", "--grund", " "))).isEmpty();
    }

    @Test
    void exportKannDateiUndJsonAusgabeAngeben() {
        var aufruf = KonfigurationKommando.lesen(List.of("exportieren", "vhf-gsk", "konfiguration.json", "--json"));

        assertThat(aufruf).isPresent().get().satisfies(gelesen -> {
            assertThat(gelesen.mandant().wert()).isEqualTo("vhf-gsk");
            assertThat(gelesen.datei()).contains(java.nio.file.Path.of("konfiguration.json"));
            assertThat(gelesen.json()).isTrue();
        });
    }

    @Test
    void unbekannteOptionenUndUngueltigeMandantenSindFalscheAufrufe() {
        assertThat(KonfigurationKommando.lesen(List.of("pruefen", "urinet", "konfiguration.json", "--unbekannt")))
                .isEmpty();
        assertThat(KonfigurationKommando.lesen(List.of("pruefen", "a-urinet", "konfiguration.json"))).isEmpty();
    }
}
