package ch.urinet.archiv.wissen.application;

import ch.urinet.archiv.gemeinsam.domain.FachFehler;
import ch.urinet.archiv.sicherheit.application.Berechtigung;
import ch.urinet.archiv.sicherheit.application.Verweigerung;
import ch.urinet.archiv.sicherheit.domain.Benutzer;
import ch.urinet.archiv.sicherheit.domain.Rolle;
import ch.urinet.archiv.wissen.adapter.out.zeitplan.QuartzWissenZeitplan;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WissenEinstellungenTest {
    private final WissenEinstellungen einstellungen = new WissenEinstellungen(null, null, new QuartzWissenZeitplan());

    @Test
    void startwerteUndQuartzZeitplanSindZulaessig() {
        assertThatCode(() -> einstellungen.pruefen(new WissenEinstellungen.Werte(null, null, null, null)))
                .doesNotThrowAnyException();
        assertThatCode(() -> einstellungen.pruefen(
                new WissenEinstellungen.Werte(600000, 100000, "0 0 4 ? * SUN", 1000000)))
                .doesNotThrowAnyException();
    }

    @Test
    void ungueltigesBudgetUndUngueltigerZeitplanWerdenAbgelehnt() {
        assertThatThrownBy(() -> einstellungen.pruefen(new WissenEinstellungen.Werte(null, null, null, 0)))
                .isInstanceOf(FachFehler.class);
        assertThatThrownBy(() -> einstellungen.pruefen(
                new WissenEinstellungen.Werte(1, 1, "kein Zeitplan", 1))).isInstanceOf(FachFehler.class);
    }

    @Test
    void geschaeftsstelleDarfEinstellungenWederLesenNochAendern() {
        AtomicInteger verweigerungen = new AtomicInteger();
        var berechtigung = new Berechtigung(null, (benutzer, rolle, aktion, dokument, grund) ->
                verweigerungen.incrementAndGet());
        var repository = new WissenEinstellungenRepository() {
            @Override
            public WissenEinstellungen.Werte lesen() {
                throw new AssertionError("Einstellungen dürfen nicht gelesen werden");
            }

            @Override
            public void speichern(WissenEinstellungen.Werte werte) {
                throw new AssertionError("Einstellungen dürfen nicht geändert werden");
            }
        };
        var dienst = new WissenEinstellungen(repository, berechtigung, new QuartzWissenZeitplan());
        var benutzer = new Benutzer("probe", Rolle.GESCHAEFTSSTELLE);
        assertThatThrownBy(() -> dienst.lesen(benutzer)).isInstanceOf(Verweigerung.class);
        assertThatThrownBy(() -> dienst.speichern(benutzer, new WissenEinstellungen.Werte(1, 1, null, 1)))
                .isInstanceOf(Verweigerung.class);
        assertThat(verweigerungen).hasValue(2);
    }
}
