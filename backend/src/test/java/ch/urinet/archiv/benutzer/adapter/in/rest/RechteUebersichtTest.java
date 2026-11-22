package ch.urinet.archiv.benutzer.adapter.in.rest;

import ch.urinet.archiv.sicherheit.domain.Aktion;
import ch.urinet.archiv.sicherheit.domain.Rechtematrix;
import ch.urinet.archiv.sicherheit.domain.Rolle;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class RechteUebersichtTest {
    @Test
    void jedeZelleUndFussnoteKommtAusDerRechtematrix() {
        for (Rolle rolle : Rolle.values()) {
            var rechte = RollenResource.darstellen(rolle).rechte();
            assertThat(rechte).extracting(RollenResource.RechtDto::aktion)
                    .containsExactlyElementsOf(Arrays.asList(Aktion.values()));
            for (var recht : rechte) {
                assertThat(recht.ergebnis()).isEqualTo(Rechtematrix.ergebnis(rolle, recht.aktion()));
                assertThat(recht.fussnoten()).extracting(RollenResource.FussnoteDto::zeichen)
                        .containsExactlyElementsOf(Rechtematrix.fussnoten(rolle, recht.aktion()).stream()
                                .map(fussnote -> fussnote.zeichen()).toList());
                assertThat(recht.fussnoten()).extracting(RollenResource.FussnoteDto::text)
                        .containsExactlyElementsOf(Rechtematrix.fussnoten(rolle, recht.aktion()).stream()
                                .map(fussnote -> fussnote.text()).toList());
            }
        }
    }
}
