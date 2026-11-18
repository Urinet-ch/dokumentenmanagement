package ch.urinet.archiv.wissen.application;

import ch.urinet.archiv.sicherheit.domain.Benutzer;
import ch.urinet.archiv.sicherheit.domain.Rolle;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class WissenSichtbarkeitTest {

    private static final UUID SEITE_NORMAL = UUID.fromString("00000000-0000-0000-0000-000000000301");
    private static final UUID SEITE_INTERN = UUID.fromString("00000000-0000-0000-0000-000000000302");

    @Test
    void mindeststufeInternIstFuerMitarbeitendeUnsichtbarFuerAlleAnderenSichtbar() {
        WissenSichtbarkeit sichtbar = sichtbarkeit();

        assertThat(sichtbar.aktuelleSeiteSichtbar(benutzer(Rolle.INHABER), SEITE_INTERN)).isTrue();
        assertThat(sichtbar.aktuelleSeiteSichtbar(benutzer(Rolle.MITARBEITER), SEITE_INTERN)).isFalse();
        assertThat(sichtbar.aktuelleSeiteSichtbar(benutzer(Rolle.TREUHAENDER), SEITE_INTERN)).isTrue();
        assertThat(sichtbar.aktuelleSeiteSichtbar(benutzer(Rolle.GESCHAEFTSSTELLE), SEITE_INTERN)).isTrue();
    }

    @Test
    void sichtbareSeiteWirdFuerMitarbeitendeGelistet() {
        WissenSichtbarkeit sichtbar = sichtbarkeit();

        assertThat(sichtbar.aktuelleSeiteSichtbar(benutzer(Rolle.MITARBEITER), SEITE_NORMAL)).isTrue();
        assertThat(sichtbar.sichtbareSeiten(benutzer(Rolle.MITARBEITER))).contains(SEITE_NORMAL);
    }

    @Test
    void versionssichtbarkeitVerwendetDieEigeneHuelleUndMindeststufe() {
        WissenSichtbarkeit sichtbar = sichtbarkeit();

        assertThat(sichtbar.versionSichtbar(benutzer(Rolle.MITARBEITER), SEITE_NORMAL, 1)).isTrue();
        assertThat(sichtbar.versionSichtbar(benutzer(Rolle.MITARBEITER), SEITE_INTERN, 1)).isFalse();
        assertThat(sichtbar.versionSichtbar(benutzer(Rolle.TREUHAENDER), SEITE_NORMAL, 1)).isTrue();
    }

    @Test
    void versionEinerUnsichtbarenSeiteBleibtUnsichtbarAuchWennSieSelbstNormalIst() {
        WissenSichtbarkeit sichtbar = sichtbarkeit();

        assertThat(sichtbar.versionSichtbar(benutzer(Rolle.MITARBEITER), SEITE_INTERN, 1)).isFalse();
        assertThat(sichtbar.versionSichtbar(benutzer(Rolle.INHABER), SEITE_INTERN, 1)).isTrue();
    }

    @Test
    void versionOhneAktuelleSeiteIstFuerNiemandenSichtbar() {
        WissenSichtbarkeitRepository repository = new WissenSichtbarkeitRepository() {
            @Override
            public List<UUID> sichtbareSeiten(ch.urinet.archiv.sicherheit.domain.Sichtregel regel) {
                return List.of();
            }

            @Override
            public boolean aktuelleSeiteSichtbar(UUID seiteId,
                    ch.urinet.archiv.sicherheit.domain.Sichtregel regel) {
                return false;
            }

            @Override
            public boolean versionSichtbar(UUID seiteId, int nummer,
                    ch.urinet.archiv.sicherheit.domain.Sichtregel regel) {
                return false;
            }
        };
        WissenSichtbarkeit sichtbar = new WissenSichtbarkeit(repository);
        for (Rolle rolle : Rolle.values()) {
            assertThat(sichtbar.versionSichtbar(benutzer(rolle), SEITE_NORMAL, 1)).as(rolle.code()).isFalse();
        }
    }

    private static WissenSichtbarkeit sichtbarkeit() {
        WissenSichtbarkeitRepository repository = new WissenSichtbarkeitRepository() {
            @Override
            public List<UUID> sichtbareSeiten(ch.urinet.archiv.sicherheit.domain.Sichtregel regel) {
                return regel.intern() ? List.of(SEITE_NORMAL, SEITE_INTERN) : List.of(SEITE_NORMAL);
            }

            @Override
            public boolean aktuelleSeiteSichtbar(UUID seiteId,
                    ch.urinet.archiv.sicherheit.domain.Sichtregel regel) {
                return seiteId.equals(SEITE_NORMAL) || (seiteId.equals(SEITE_INTERN) && regel.intern());
            }

            @Override
            public boolean versionSichtbar(UUID seiteId, int nummer,
                    ch.urinet.archiv.sicherheit.domain.Sichtregel regel) {
                return aktuelleSeiteSichtbar(seiteId, regel);
            }
        };
        return new WissenSichtbarkeit(repository);
    }

    private static Benutzer benutzer(Rolle rolle) {
        return new Benutzer("synthetisch-" + rolle.code(), rolle);
    }
}
