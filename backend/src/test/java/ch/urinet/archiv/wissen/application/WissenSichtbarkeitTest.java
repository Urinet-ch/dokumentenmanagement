package ch.urinet.archiv.wissen.application;

import ch.urinet.archiv.sicherheit.domain.Benutzer;
import ch.urinet.archiv.sicherheit.domain.Rolle;
import ch.urinet.archiv.wissen.domain.WissensStufe;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class WissenSichtbarkeitTest {

    private static final UUID SEITE_NORMAL = UUID.fromString("00000000-0000-0000-0000-000000000301");
    private static final UUID SEITE_INTERN = UUID.fromString("00000000-0000-0000-0000-000000000302");
    private static final UUID DOKUMENT = UUID.fromString("00000000-0000-0000-0000-000000000303");

    @Test
    void mindeststufeInternIstFuerMitarbeitendeUnsichtbarFuerAlleAnderenSichtbar() {
        WissenSichtbarkeit sichtbar = sichtbarkeit(List.of(
                new WissensHuelleZugriff(SEITE_INTERN, WissensStufe.INTERN, Set.of(DOKUMENT)),
                new WissensHuelleZugriff(SEITE_NORMAL, WissensStufe.NORMAL, Set.of(DOKUMENT))), Set.of(DOKUMENT));

        assertThat(sichtbar.aktuelleSeiteSichtbar(benutzer(Rolle.INHABER), SEITE_INTERN)).isTrue();
        assertThat(sichtbar.aktuelleSeiteSichtbar(benutzer(Rolle.MITARBEITER), SEITE_INTERN)).isFalse();
        assertThat(sichtbar.aktuelleSeiteSichtbar(benutzer(Rolle.TREUHAENDER), SEITE_INTERN)).isTrue();
        assertThat(sichtbar.aktuelleSeiteSichtbar(benutzer(Rolle.GESCHAEFTSSTELLE), SEITE_INTERN)).isTrue();
    }

    @Test
    void unsichtbaresDokumentVerstecktDieGanzeSeiteUndWirdNichtGelistet() {
        WissenSichtbarkeit sichtbar = sichtbarkeit(List.of(
                new WissensHuelleZugriff(SEITE_NORMAL, WissensStufe.NORMAL, Set.of(DOKUMENT))), Set.of());

        assertThat(sichtbar.aktuelleSeiteSichtbar(benutzer(Rolle.INHABER), SEITE_NORMAL)).isFalse();
        assertThat(sichtbar.sichtbareSeiten(benutzer(Rolle.INHABER))).isEmpty();
    }

    @Test
    void versionssichtbarkeitVerwendetDieEigeneHuelleUndMindeststufe() {
        WissenSichtbarkeit sichtbar = versionen(
                Optional.of(new WissensHuelleZugriff(SEITE_NORMAL, WissensStufe.NORMAL, Set.of(DOKUMENT))),
                new WissensHuelleZugriff(SEITE_NORMAL, WissensStufe.INTERN, Set.of(DOKUMENT)));

        assertThat(sichtbar.versionSichtbar(benutzer(Rolle.MITARBEITER), SEITE_NORMAL, 1)).isFalse();
        assertThat(sichtbar.versionSichtbar(benutzer(Rolle.TREUHAENDER), SEITE_NORMAL, 1)).isTrue();
    }

    @Test
    void versionEinerUnsichtbarenSeiteBleibtUnsichtbarAuchWennSieSelbstNormalIst() {
        WissenSichtbarkeit sichtbar = versionen(
                Optional.of(new WissensHuelleZugriff(SEITE_INTERN, WissensStufe.INTERN, Set.of(DOKUMENT))),
                new WissensHuelleZugriff(SEITE_INTERN, WissensStufe.NORMAL, Set.of(DOKUMENT)));

        assertThat(sichtbar.versionSichtbar(benutzer(Rolle.MITARBEITER), SEITE_INTERN, 1)).isFalse();
        assertThat(sichtbar.versionSichtbar(benutzer(Rolle.INHABER), SEITE_INTERN, 1)).isTrue();
    }

    @Test
    void versionOhneAktuelleSeiteIstFuerNiemandenSichtbar() {
        WissenSichtbarkeit sichtbar = versionen(Optional.empty(),
                new WissensHuelleZugriff(SEITE_NORMAL, WissensStufe.NORMAL, Set.of(DOKUMENT)));

        for (Rolle rolle : Rolle.values()) {
            assertThat(sichtbar.versionSichtbar(benutzer(rolle), SEITE_NORMAL, 1)).as(rolle.code()).isFalse();
        }
    }

    @Test
    void versionOhneGespeicherteHuelleGiltAlsIntern() {
        WissenSichtbarkeit sichtbar = versionen(
                Optional.of(new WissensHuelleZugriff(SEITE_NORMAL, WissensStufe.NORMAL, Set.of(DOKUMENT))),
                new WissensHuelleZugriff(SEITE_NORMAL, WissensStufe.NORMAL, Set.of()));

        assertThat(sichtbar.versionSichtbar(benutzer(Rolle.MITARBEITER), SEITE_NORMAL, 1)).isFalse();
        assertThat(sichtbar.versionSichtbar(benutzer(Rolle.GESCHAEFTSSTELLE), SEITE_NORMAL, 1)).isTrue();
    }

    private static WissenSichtbarkeit versionen(Optional<WissensHuelleZugriff> seite, WissensHuelleZugriff version) {
        WissenSichtbarkeitRepository repository = new WissenSichtbarkeitRepository() {
            @Override
            public List<WissensHuelleZugriff> aktuelleSeiten() {
                return seite.stream().toList();
            }

            @Override
            public Optional<WissensHuelleZugriff> aktuelleSeite(UUID seiteId) {
                return seite.filter(s -> s.seiteId().equals(seiteId));
            }

            @Override
            public Optional<WissensHuelleZugriff> version(UUID seiteId, int nummer) {
                return Optional.of(version).filter(v -> v.seiteId().equals(seiteId));
            }
        };
        return new WissenSichtbarkeit(repository, (benutzer, dokumentId) -> true);
    }

    private static WissenSichtbarkeit sichtbarkeit(List<WissensHuelleZugriff> seiten, Set<UUID> sichtbareDokumente) {
        WissenSichtbarkeitRepository repository = new WissenSichtbarkeitRepository() {
            @Override
            public List<WissensHuelleZugriff> aktuelleSeiten() {
                return seiten;
            }

            @Override
            public Optional<WissensHuelleZugriff> aktuelleSeite(UUID seiteId) {
                return seiten.stream().filter(s -> s.seiteId().equals(seiteId)).findFirst();
            }

            @Override
            public Optional<WissensHuelleZugriff> version(UUID seiteId, int nummer) {
                return aktuelleSeite(seiteId);
            }
        };
        return new WissenSichtbarkeit(repository, (benutzer, dokumentId) -> sichtbareDokumente.contains(dokumentId));
    }

    private static Benutzer benutzer(Rolle rolle) {
        return new Benutzer("synthetisch-" + rolle.code(), rolle);
    }
}
