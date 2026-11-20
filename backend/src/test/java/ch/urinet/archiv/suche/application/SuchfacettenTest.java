package ch.urinet.archiv.suche.application;

import ch.urinet.archiv.gemeinsam.domain.FachFehler;
import ch.urinet.archiv.konfiguration.domain.Klassifikation;
import ch.urinet.archiv.konfiguration.domain.KlassifikationWert;
import ch.urinet.archiv.konfiguration.domain.Konfiguration;
import ch.urinet.archiv.suche.domain.Suchanfrage;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SuchfacettenTest {

    private static Suchanfrage anfrage(String code, String... werte) {
        return new Suchanfrage(null, null, null, null, null, null, null, null, null, null, null, null, null,
                0, 20, null, null, Map.of(code, List.of(werte)));
    }

    private static Suchfacetten facetten(Klassifikation klassifikation) {
        Konfiguration abbild = new Konfiguration(1, null, List.of(), List.of(klassifikation));
        return new Suchfacetten(null, () -> abbild);
    }

    @Test
    void filterMuessenAktiveSuchbareKlassifikationenUndWerteTreffen() {
        Klassifikation konfiguration = new Klassifikation("fachgebiet", "Fachgebiet", null, List.of(
                new KlassifikationWert("bau", "Bau", null, 0, true),
                new KlassifikationWert("alt", "Alt", null, 1, false)),
                true, false, true, false, true);

        assertThatCode(() -> facetten(konfiguration).pruefen(anfrage("fachgebiet", "bau")))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> facetten(konfiguration).pruefen(anfrage("fachgebiet", "alt")))
                .isInstanceOf(FachFehler.class);
        assertThatThrownBy(() -> facetten(konfiguration).pruefen(anfrage("unbekannt", "bau")))
                .isInstanceOf(FachFehler.class);

        Klassifikation ohneSuchfilter = new Klassifikation("fachgebiet", "Fachgebiet", null,
                konfiguration.werte(), true, false, false, false, true);
        assertThatThrownBy(() -> facetten(ohneSuchfilter).pruefen(anfrage("fachgebiet", "bau")))
                .isInstanceOf(FachFehler.class);
    }
}
