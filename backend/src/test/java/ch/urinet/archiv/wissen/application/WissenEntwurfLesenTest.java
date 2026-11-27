package ch.urinet.archiv.wissen.application;

import ch.urinet.archiv.sicherheit.application.Berechtigung;
import ch.urinet.archiv.sicherheit.application.Verweigerung;
import ch.urinet.archiv.sicherheit.domain.Benutzer;
import ch.urinet.archiv.sicherheit.domain.Rolle;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WissenEntwurfLesenTest {
    private static final UUID ID = UUID.randomUUID();
    private static final Benutzer MITARBEITER = new Benutzer("mitarbeiter", Rolle.MITARBEITER);
    private static final Benutzer INHABER = new Benutzer("inhaber", Rolle.INHABER);
    private static final Benutzer TREUHAENDER = new Benutzer("treuhaender", Rolle.TREUHAENDER);
    private final Speicher repository = new Speicher();
    private final WissenEntwurfLesen lesen = new WissenEntwurfLesen(repository,
            new Berechtigung((benutzer, id) -> true, (id, rolle, aktion, dokumentId, grund) -> { }));

    @Test
    void eigenerAnalyseVorschlagEnthaeltNurStatusUndEntscheid() {
        assertThat(lesen.lesen(MITARBEITER, ID)).isEqualTo(new WissenEntwurfLesen.StatusAnsicht(ID, "OFFEN",
                List.of(new WissenEntwurfLesen.Entscheidung(1, "VERWORFEN", "Grund"))));
        assertThat(repository.inhaltGeladen).isFalse();
        assertThat(lesen.liste(MITARBEITER, null)).containsExactly(new WissenEntwurfLesen.StatusListenEintrag(ID, "OFFEN"));
        assertThat(repository.eigenerAnalyseVorschlag).isEqualTo(MITARBEITER.id());
    }

    @Test
    void fremdeOderNichtAnalyseEntwuerfeSindFuerMitarbeitendeVerboten() {
        repository.kopf = new WissenEntwurfLesenRepository.Kopf(ID, "INGEST", "OFFEN", MITARBEITER.id(),
                Instant.EPOCH, null, false, null);
        assertThatThrownBy(() -> lesen.lesen(MITARBEITER, ID)).isInstanceOf(Verweigerung.class);
        repository.kopf = new WissenEntwurfLesenRepository.Kopf(ID, "ANALYSE", "OFFEN", "andere",
                Instant.EPOCH, null, false, null);
        assertThatThrownBy(() -> lesen.lesen(MITARBEITER, ID)).isInstanceOf(Verweigerung.class);
        assertThatThrownBy(() -> lesen.lesen(TREUHAENDER, ID)).isInstanceOf(Verweigerung.class);
        assertThat(repository.inhaltGeladen).isFalse();
    }

    @Test
    void a17SiehtFassungenUndZuordnungDerKernaussagen() {
        WissenEntwurfLesen.Vollansicht ansicht = (WissenEntwurfLesen.Vollansicht) lesen.lesen(INHABER, ID);
        assertThat(ansicht.aenderungen()).singleElement().satisfies(a -> {
            assertThat(a.inhalt()).isEqualTo("Fassung [^q-1] und \\[^q-2]");
            assertThat(a.diff()).containsEntry("inhalt", "Diff");
        });
        assertThat(ansicht.kernaussagen()).extracting(WissenEntwurfLesen.Aussage::zugeordnet)
                .containsExactly(true, false);
        assertThat(repository.inhaltGeladen).isTrue();
    }

    @Test
    void fussnoteOhneQuelleneintragOrdnetKeineKernaussageZu() {
        repository.ohneQuellen = true;
        WissenEntwurfLesen.Vollansicht ansicht = (WissenEntwurfLesen.Vollansicht) lesen.lesen(INHABER, ID);
        assertThat(ansicht.kernaussagen()).extracting(WissenEntwurfLesen.Aussage::zugeordnet)
                .containsExactly(false, false);
    }

    private static final class Speicher implements WissenEntwurfLesenRepository {
        private Kopf kopf = new Kopf(ID, "ANALYSE", "OFFEN", MITARBEITER.id(), Instant.EPOCH, null, false, "Hinweis");
        private boolean inhaltGeladen;
        private boolean ohneQuellen;
        private String eigenerAnalyseVorschlag;

        @Override
        public List<Kopf> liste(String status, String eigenerAnalyseVorschlag) {
            this.eigenerAnalyseVorschlag = eigenerAnalyseVorschlag;
            return List.of(kopf);
        }

        @Override
        public Optional<Kopf> kopf(UUID id) {
            return ID.equals(id) ? Optional.of(kopf) : Optional.empty();
        }

        @Override
        public List<WissenIngestPort.Kernaussage> kernaussagen(UUID id) {
            return List.of(new WissenIngestPort.Kernaussage(1, "q-1", "Belegt", List.of(1), "Auszug", true),
                    new WissenIngestPort.Kernaussage(2, "q-2", "Nicht zugeordnet", List.of(2), "Auszug", true));
        }

        @Override
        public Optional<WissenIngestPort.Fortschritt> ingestFortschritt(UUID id) {
            return Optional.empty();
        }

        @Override
        public List<Aenderung> aenderungen(UUID id) {
            inhaltGeladen = true;
            return List.of(new Aenderung(1, 1, "NEU", null, "analyse/test", null,
                    ohneQuellen ? Map.of()
                            : Map.of("quellen", List.of(Map.of("schluessel", "q-1"), Map.of("schluessel", "q-2"))),
                    "Fassung [^q-1] und \\[^q-2]", Map.of("inhalt", "Diff"),
                    "Begründung", List.of(), List.of(), List.of(), List.of(), "NORMAL", "OFFEN",
                    null, null, null, null, 0));
        }

        @Override
        public List<WissenEntwurfLesen.Entscheidung> entscheidungen(UUID id) {
            return List.of(new WissenEntwurfLesen.Entscheidung(1, "VERWORFEN", "Grund"));
        }
    }
}
