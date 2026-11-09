package ch.urinet.archiv.spike.golden;

import ch.urinet.archiv.spike.golden.GoldenPipeline.BelegErgebnis;
import ch.urinet.archiv.spike.golden.MetadatenVorschlag.Sicherheit;
import ch.urinet.archiv.spike.golden.Vergleich.Feld;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Req 0.9: Fehler ab Schwelle (>=) und kleinste Schwelle mit höchstens einem Fehler. */
class BerichtTest {

    static BelegErgebnis ergebnis(String id, Map<Feld, Double> sicherheit, Feld... falsch) {
        Map<Feld, Boolean> richtig = new EnumMap<>(Feld.class);
        for (Feld f : Feld.values()) {
            richtig.put(f, !List.of(falsch).contains(f));
        }
        Nachbearbeitung.Ergebnis v = new Nachbearbeitung.Ergebnis("QUITTUNG", null, null, null, null, null, null, null,
                null, null, false, sicherheit);
        Erwartung e = new Erwartung(id, null, false, "abstimmen", "QUITTUNG", null, null, null, null, null, List.of());
        return new BelegErgebnis(e, v, richtig, false, 1, 100, 50, false, 10, 20, null);
    }

    static Map<Feld, Double> s(double wert) {
        Map<Feld, Double> m = new EnumMap<>(Feld.class);
        for (Feld f : Feld.values()) {
            m.put(f, wert);
        }
        return m;
    }

    @Test
    void schwelleUndKennzahlen() {
        List<BelegErgebnis> r = List.of(
                ergebnis("a", s(0.95), Feld.DATUM),
                ergebnis("b", s(0.80), Feld.PARTNER),
                ergebnis("c", s(0.60), Feld.BETRAG),
                ergebnis("d", s(0.99)));
        assertThat(Bericht.fehlerUeberSchwelle(r, 0.8)).isEqualTo(2);
        assertThat(Bericht.fehlerUeberSchwelle(r, 0.81)).isEqualTo(1);
        assertThat(Bericht.kleinsteSchwelle(r)).isEqualByComparingTo(new BigDecimal("0.81"));
        Bericht.Kennzahlen k = Bericht.berechne(r, 0.8, 0.20, 0.75);
        assertThat(k.gateRichtig()).isEqualTo(1);
        assertThat(k.richtig().get(Feld.ART)).isEqualTo(4);
        assertThat(k.fehlschlaege()).containsExactly("a: DATUM", "b: PARTNER", "c: BETRAG");
        assertThat(k.tokensEin()).isEqualTo(400);
    }

    @Test
    void keineSchwelleWennZweiFehlerMitSicherheitEins() {
        List<BelegErgebnis> r = List.of(ergebnis("a", s(1.0), Feld.DATUM), ergebnis("b", s(1.0), Feld.ART));
        assertThat(Bericht.kleinsteSchwelle(r)).isNull();
        assertThat(Bericht.kleinsteSchwelle(List.of(ergebnis("a", s(1.0), Feld.DATUM)))).isEqualByComparingTo("0");
    }

    @Test
    void sicherheitDefaultsNichtNoetig() {
        assertThat(new Sicherheit(null, null, null, null, null, null, null, null, null, null).art()).isNull();
    }
}
