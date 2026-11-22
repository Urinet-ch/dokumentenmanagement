package ch.urinet.archiv.mandant.adapter.out.konfiguration;

import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.mandant.domain.MandantVerwaltungsrollen;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class KonfigurierterMandantVerwaltungsrollenSeedTest {

    @Test
    void verwendetMandantenspezifischeRollenUndSonstDenStandard() {
        MandantProvisionierungKonfiguration konfiguration = () -> Map.of("vhf-gsk", () -> List.of("geschaeftsstelle"));
        var seed = new KonfigurierterMandantVerwaltungsrollenSeed(konfiguration);

        assertThat(seed.fuer(new MandantCode("vhf-gsk")).werte()).containsExactly("geschaeftsstelle");
        assertThat(seed.fuer(new MandantCode("urinet"))).isEqualTo(MandantVerwaltungsrollen.STANDARD);
    }
}
