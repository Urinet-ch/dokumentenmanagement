package ch.urinet.archiv.benutzer.adapter.out.stub;

import ch.urinet.archiv.benutzer.application.IdentitaetsDienst.Neuanlage;
import ch.urinet.archiv.gemeinsam.domain.FachFehler;
import ch.urinet.archiv.gemeinsam.domain.Fehlercode;
import ch.urinet.archiv.sicherheit.domain.Rolle;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IdentitaetsDienstStubTest {

    @Test
    void fremderBenutzerIstNichtLesbarOderAenderbar() {
        var dienst = new IdentitaetsDienstStub();
        String id = dienst.anlegen("a", new Neuanlage("beispiel", "a@example.test", "Test", "Person"),
                Rolle.MITARBEITER);

        assertThat(dienst.benutzerSuchen("b", "", 0)).isEmpty();
        assertThatThrownBy(() -> dienst.benutzerLesen("b", id)).isInstanceOf(FachFehler.class)
                .extracting(e -> ((FachFehler) e).code()).isEqualTo(Fehlercode.NICHT_GEFUNDEN);
        assertThatThrownBy(() -> dienst.rolleSetzen("b", id, Rolle.INHABER)).isInstanceOf(FachFehler.class)
                .extracting(e -> ((FachFehler) e).code()).isEqualTo(Fehlercode.NICHT_GEFUNDEN);
        assertThat(dienst.benutzerLesen("a", id).rollen()).containsExactly(Rolle.MITARBEITER);
    }

    @Test
    void benutzerSeitenSindAuf50Begrenzt() {
        var dienst = new IdentitaetsDienstStub();
        for (int i = 0; i < 51; i++) {
            dienst.anlegen("a", new Neuanlage("benutzer" + i, "test" + i + "@example.test", "Test", "Person"),
                    Rolle.MITARBEITER);
        }
        assertThat(dienst.benutzerSuchen("a", "", 0)).hasSize(50);
        assertThat(dienst.benutzerSuchen("a", "", 1)).hasSize(1);
        assertThat(dienst.benutzerSuchen("a", "", 2)).isEmpty();
    }
}
