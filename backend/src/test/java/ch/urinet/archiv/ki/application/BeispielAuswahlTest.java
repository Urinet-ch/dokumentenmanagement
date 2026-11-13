package ch.urinet.archiv.ki.application;

import ch.urinet.archiv.ki.domain.Beispiel;
import ch.urinet.archiv.ki.domain.BeispielSicht;
import ch.urinet.archiv.ki.domain.ErfassungMandant;
import ch.urinet.archiv.ki.domain.Feld;
import ch.urinet.archiv.ki.domain.Merkmale;
import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.protokoll.application.BenutzerVerzeichnis;
import ch.urinet.archiv.protokoll.domain.VerzeichnisEintrag;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class BeispielAuswahlTest {

    private static final MandantCode URINET = new MandantCode("urinet");
    private static final ErfassungMandant EIGENE = new ErfassungMandant("Urinet", null, List.of(), Set.of());
    private static final String TEXT = "IBAN CH44 3199 9123 0008 8901 2";

    private static final class Port implements KiBeispielPort {
        RuntimeException fehler;
        BeispielSicht sicht;

        @Override
        public Optional<Lernstand> lernstand(UUID dokumentId) {
            return Optional.empty();
        }

        @Override
        public Optional<String> erfasser(UUID dokumentId) {
            return Optional.of("joel");
        }

        @Override
        public void speichern(Neu beispiel) {
        }

        @Override
        public List<Beispiel> kandidaten(Merkmale merkmale, BeispielSicht sicht, int hoechstens) {
            if (fehler != null) {
                throw fehler;
            }
            this.sicht = sicht;
            return List.of(new Beispiel(1, "KORRESPONDENZ", Map.of(Feld.TITEL, "Lernprobe"), merkmale, "NORMAL",
                    null, Instant.EPOCH));
        }

        @Override
        public List<Beispiel> neueste(BeispielSicht sicht, int anzahl) {
            return List.of();
        }
    }

    private static BenutzerVerzeichnis verzeichnis(String rolle) {
        return new BenutzerVerzeichnis() {
            @Override
            public Optional<VerzeichnisEintrag> letzter(Optional<MandantCode> mandant, String benutzer) {
                return Optional.of(new VerzeichnisEintrag(1, Instant.EPOCH, benutzer, Optional.empty(),
                        Optional.of(rolle), Instant.EPOCH, Optional.empty(), VerzeichnisEintrag.Anlass.AKTION,
                        Optional.empty()));
            }

            @Override
            public void einfuegen(Optional<MandantCode> mandant, Neu neu) {
            }

            @Override
            public List<VerzeichnisEintrag> liste() {
                return List.of();
            }

            @Override
            public void sperren(Optional<MandantCode> mandant, String benutzer, Sperre sperre) {
            }
        };
    }

    private static BeispielAuswahl auswahl(Port port, String rolle, boolean aktiv) {
        return new BeispielAuswahl(port, verzeichnis(rolle), aktiv, Duration.ofMillis(100));
    }

    @Test
    void mitSchalterKommenDieBeispieleInDenPrompt() {
        Port port = new Port();

        assertThat(auswahl(port, "MITARBEITER", true).fuer(UUID.randomUUID(), URINET, TEXT, Optional.empty(), EIGENE))
                .isEqualTo("- art=KORRESPONDENZ; titel=Lernprobe");
        assertThat(port.sicht).isEqualTo(new BeispielSicht("joel", true, false));
    }

    @Test
    void ohneSchalterBleibtDerPromptDerDesGateLaufs() {
        assertThat(auswahl(new Port(), "MITARBEITER", false)
                .fuer(UUID.randomUUID(), URINET, TEXT, Optional.empty(), EIGENE)).isEmpty();
    }

    @Test
    void fehlerBeimLesenKostetNurDieBeispiele() {
        Port port = new Port();
        port.fehler = new IllegalStateException("Datenbank weg");

        assertThat(auswahl(port, "INHABER", true).fuer(UUID.randomUUID(), URINET, TEXT, Optional.empty(), EIGENE))
                .isEmpty();
    }

    @Test
    void inhaberSiehtInterneBeispiele() {
        Port port = new Port();

        auswahl(port, "INHABER", true).fuer(UUID.randomUUID(), URINET, TEXT, Optional.empty(), EIGENE);

        assertThat(port.sicht.intern()).isTrue();
    }
}
