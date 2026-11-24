package ch.urinet.archiv.benutzer.adapter.out.stub;

import ch.urinet.archiv.benutzer.application.IdentitaetsDienst.Neuanlage;
import ch.urinet.archiv.benutzer.application.IdentitaetsDienst.KonfliktException;
import ch.urinet.archiv.benutzer.application.IdentitaetsDienst.ErforderlicheAktion;
import ch.urinet.archiv.gemeinsam.domain.FachFehler;
import ch.urinet.archiv.gemeinsam.domain.Fehlercode;
import ch.urinet.archiv.sicherheit.domain.Rolle;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IdentitaetsDienstStubTest {

    @Test
    void doppelteNamenOderAdressenMeldenEinenNeutralenRealmweitenKonflikt() {
        var dienst = new IdentitaetsDienstStub();
        dienst.anlegen("a", new Neuanlage("beispiel", "a@example.test", "Test", "Person"), Rolle.MITARBEITER);

        assertThatThrownBy(() -> dienst.anlegen("b",
                new Neuanlage("BEISPIEL", "b@example.test", "Test", "Person"), Rolle.MITARBEITER))
                .isInstanceOf(KonfliktException.class).hasMessage("Konflikt im Identitätsdienst");
        assertThatThrownBy(() -> dienst.anlegen("b",
                new Neuanlage("anderer", "A@example.test", "Test", "Person"), Rolle.MITARBEITER))
                .isInstanceOf(KonfliktException.class).hasMessage("Konflikt im Identitätsdienst");
        assertThat(dienst.benutzerSuchen("b", "", 0)).isEmpty();
    }

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
    void erforderlicheAktionenErgaenzenOhneAndereAktionenOderMandantenZuAendern() {
        var dienst = new IdentitaetsDienstStub();
        String id = dienst.anlegen("a", new Neuanlage("beispiel", "a@example.test", "Test", "Person"),
                Rolle.MITARBEITER);
        dienst.einladen("a", id, Set.of(ErforderlicheAktion.VERIFY_EMAIL), Duration.ofHours(1));

        assertThatThrownBy(() -> dienst.aktionenHinzufuegen("b", id, Set.of(ErforderlicheAktion.UPDATE_PASSWORD)))
                .isInstanceOfSatisfying(FachFehler.class,
                        fehler -> assertThat(fehler.code()).isEqualTo(Fehlercode.NICHT_GEFUNDEN));
        dienst.aktionenHinzufuegen("a", id, Set.of(ErforderlicheAktion.UPDATE_PASSWORD));
        assertThat(dienst.benutzerLesen("a", id).aktionen())
                .containsExactlyInAnyOrder(ErforderlicheAktion.VERIFY_EMAIL, ErforderlicheAktion.UPDATE_PASSWORD);
    }

    @Test
    void benutzerzahlEnthaeltNurNichtGeloeschteBenutzerDesMandanten() {
        var dienst = new IdentitaetsDienstStub();
        String aktiv = dienst.anlegen("a", new Neuanlage("eins", "eins@example.test", "Test", "Person"),
                Rolle.MITARBEITER);
        String deaktiviert = dienst.anlegen("a", new Neuanlage("zwei", "zwei@example.test", "Test", "Person"),
                Rolle.MITARBEITER);
        dienst.anlegen("b", new Neuanlage("drei", "drei@example.test", "Test", "Person"), Rolle.MITARBEITER);
        dienst.aktivieren("a", deaktiviert, false);

        assertThat(dienst.benutzerZaehlen("a")).isEqualTo(2);
        assertThat(dienst.benutzerZaehlen("b")).isEqualTo(1);
        dienst.loeschen("a", aktiv);
        assertThat(dienst.benutzerZaehlen("a")).isEqualTo(1);
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
        var erste = dienst.benutzerSuchen("a", "", 0, 7);
        var zweite = dienst.benutzerSuchen("a", "", 1, 7);
        assertThat(erste).hasSize(7).doesNotContainAnyElementsOf(zweite);
        assertThat(zweite).hasSize(7);
        assertThat(dienst.benutzerSuchen("a", "", 7, 7)).hasSize(2);
        assertThat(dienst.benutzerSuchen("a", "PERSON", 1, 7)).hasSize(7);
        assertThat(dienst.benutzerSuchen("a", "@EXAMPLE.TEST", 0, 7)).hasSize(7);
        assertThatThrownBy(() -> dienst.benutzerSuchen("a", "", 0, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> dienst.benutzerSuchen("a", "", 0, 51))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> dienst.benutzerSuchen("a", "", -1, 7))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> dienst.benutzerSuchen("a", "", Integer.MAX_VALUE, 2))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> dienst.benutzerSuchen("a", "x".repeat(41), 0, 7))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void passkeysEntfernenLoeschtNurPasskeysUndFilterZaehltNachDemFilter() {
        var dienst = new IdentitaetsDienstStub();
        String eins = dienst.anlegen("a", new Neuanlage("eins", "1@example.test", "Test", "Person"), Rolle.MITARBEITER);
        String zwei = dienst.anlegen("a", new Neuanlage("zwei", "2@example.test", "Test", "Person"), Rolle.MITARBEITER);
        String drei = dienst.anlegen("a", new Neuanlage("drei", "3@example.test", "Test", "Person"), Rolle.MITARBEITER);
        dienst.passkeysSetzen("a", eins, 2);
        dienst.passkeysSetzen("a", drei, 1);

        assertThat(dienst.benutzerSuchen("a", "", 0, 50, true)).extracting(b -> b.id()).containsExactlyInAnyOrder(eins, drei);
        assertThat(dienst.benutzerSuchen("a", "", 0, 50, false)).extracting(b -> b.id()).containsExactly(zwei);
        assertThat(dienst.benutzerSuchen("a", "", 0, 50, null)).hasSize(3);
        assertThat(dienst.benutzerSuchen("a", "", 1, 1, true)).hasSize(1);
        assertThat(dienst.benutzerSuchen("a", "", 2, 1, true)).isEmpty();

        assertThat(dienst.passkeysEntfernen("a", eins)).isEqualTo(2);
        assertThat(dienst.passkeysEntfernen("a", eins)).isZero();
        assertThat(dienst.benutzerLesen("a", eins).passkeys()).isZero();
        assertThat(dienst.benutzerLesen("a", drei).passkeys()).isEqualTo(1);
        assertThatThrownBy(() -> dienst.passkeysEntfernen("b", drei)).isInstanceOf(FachFehler.class);
    }
}
