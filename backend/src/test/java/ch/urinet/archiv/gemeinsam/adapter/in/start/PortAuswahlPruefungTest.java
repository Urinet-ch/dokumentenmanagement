package ch.urinet.archiv.gemeinsam.adapter.in.start;

import ch.urinet.archiv.gemeinsam.domain.PortAuswahl;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class PortAuswahlPruefungTest {

    @Test
    @Tag("req-16.24")
    void kiStubIstNurInStufePErlaubt() {
        var ki = new PortAuswahl("KiPort", true, true, Optional.empty());

        assertThat(PortAuswahlPruefung.pruefe(List.of(ki), "P").abbrueche()).isEmpty();
        assertThat(PortAuswahlPruefung.pruefe(List.of(ki), "B").abbrueche())
                .containsExactly("KiPort: Dev-Stub ist in prod nicht zulässig");
    }

    @Test
    void andereStubsBleibenAuchInStufePUnzulaessig() {
        var ablage = new PortAuswahl("AblagePort", true, true, Optional.empty());

        assertThat(PortAuswahlPruefung.pruefe(List.of(ablage), "P").abbrueche())
                .containsExactly("AblagePort: Dev-Stub ist in prod nicht zulässig");
    }
}
