package ch.urinet.archiv.wissen.application;

import ch.urinet.archiv.konfiguration.application.BestaetigteEinstellungen;
import ch.urinet.archiv.sicherheit.application.Berechtigung;
import ch.urinet.archiv.sicherheit.application.Verweigerung;
import ch.urinet.archiv.sicherheit.domain.Benutzer;
import ch.urinet.archiv.sicherheit.domain.Rolle;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WissenEinstellungenTest {
    @Test
    void geschaeftsstelleDarfEinstellungenWederLesenNochAendern() {
        AtomicInteger verweigerungen = new AtomicInteger();
        var berechtigung = new Berechtigung(null, (benutzer, rolle, aktion, dokument, grund) ->
                verweigerungen.incrementAndGet());
        var dienst = new WissenEinstellungen(berechtigung, null);
        var benutzer = new Benutzer("probe", Rolle.GESCHAEFTSSTELLE);
        assertThatThrownBy(() -> dienst.lesen(benutzer)).isInstanceOf(Verweigerung.class);
        assertThatThrownBy(() -> dienst.speichern(benutzer, 1, () -> null, BestaetigteEinstellungen.lesen(null)))
                .isInstanceOf(Verweigerung.class);
        assertThat(verweigerungen).hasValue(2);
    }
}
