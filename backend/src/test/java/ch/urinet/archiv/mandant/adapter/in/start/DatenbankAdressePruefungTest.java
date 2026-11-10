package ch.urinet.archiv.mandant.adapter.in.start;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DatenbankAdressePruefungTest {

    @Test
    void ohneAdresseBrichtProdAb() {
        assertThatThrownBy(() -> DatenbankAdressePruefung.pruefen(true, Optional.empty()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("archiv.db.url");
        assertThatThrownBy(() -> DatenbankAdressePruefung.pruefen(true, Optional.of(" ")))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void mitAdresseOderOhnePflichtStartet() {
        assertThatCode(() -> DatenbankAdressePruefung.pruefen(true, Optional.of("jdbc:postgresql://db:5432/archiv")))
                .doesNotThrowAnyException();
        assertThatCode(() -> DatenbankAdressePruefung.pruefen(false, Optional.empty())).doesNotThrowAnyException();
    }
}
