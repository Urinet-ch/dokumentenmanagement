package ch.urinet.archiv.benutzer.application;

import ch.urinet.archiv.benutzer.adapter.in.kommando.InhaberEinladenKommando;
import ch.urinet.archiv.benutzer.adapter.out.stub.IdentitaetsDienstStub;
import ch.urinet.archiv.mandant.application.MandantAbbauRegister;
import ch.urinet.archiv.mandant.application.MandantKontext;
import ch.urinet.archiv.mandant.domain.Geschaeftsjahresende;
import ch.urinet.archiv.mandant.domain.Mandant;
import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.mandant.domain.MandantVerwaltungsrollen;
import ch.urinet.archiv.protokoll.application.BenutzerVerzeichnis;
import ch.urinet.archiv.protokoll.domain.VerzeichnisEintrag;
import ch.urinet.archiv.sicherheit.domain.Rolle;
import io.cucumber.java.Before;
import io.cucumber.java.de.Angenommen;
import io.cucumber.java.de.Dann;
import io.cucumber.java.de.Wenn;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

public class BenutzerAdminKommandoSchritte {
    private static final MandantCode URINET = new MandantCode("urinet");
    private static final Instant DEAKTIVIERT = Instant.parse("2026-10-01T00:00:00Z");

    private IdentitaetsDienstStub identitaet;
    private String benutzerId;
    private IdentitaetsDienst.Benutzer benutzerVorAbgleich;
    private VerzeichnisAttrappe verzeichnis;
    private MandantAbbauRegister.Status mandantenStatus;
    private BenutzerAbgleichen.Bericht abgleich;
    private BenutzerMandantSperren.Bericht sperrBericht;
    private int beendeteSitzungen;
    private MandantCode inhaberMandant;
    private MandantCode inhaberKontext;
    private String inhaberBetreiber;
    private BenutzerAnlegen.BenutzerAnlage inhaberEingabe;
    private UUID inhaberId;
    private int inhaberExitCode;
    private String inhaberTerminalAusgabe;
    private String inhaberTerminalFehler;

    @Before("@admin-kommando")
    public void vorbereiten() {
        identitaet = new TrackingIdentitaetsDienst(() -> beendeteSitzungen++);
        verzeichnis = new VerzeichnisAttrappe();
        beendeteSitzungen = 0;
    }

    @Angenommen("der Betreiber hat einen Mandanten ohne Inhaber")
    public void mandantOhneInhaber() {
        inhaberMandant = new MandantCode("probe-adminrollen");
        inhaberKontext = null;
        inhaberBetreiber = null;
        inhaberEingabe = null;
        inhaberId = UUID.fromString("08b1d49a-e60c-4c98-9eb3-6cd1a337c1b2");
        inhaberExitCode = -1;
        inhaberTerminalAusgabe = "";
        inhaberTerminalFehler = "";
    }

    @Wenn("der Betreiber das Kommando für die erste Inhabereinladung ausführt")
    public void ersteInhabereinladungAusfuehren() {
        BenutzerAnlegen anlegen = new BenutzerAnlegen(null, null, null, null, null, null, null, null, null, null,
                1, Duration.ofHours(1), Duration.ofHours(1)) {
            @Override
            public UUID erstenInhaberEinladen(MandantCode mandant, String betreiber, BenutzerAnlage eingabe) {
                inhaberMandant = mandant;
                inhaberBetreiber = betreiber;
                inhaberEingabe = eingabe;
                return inhaberId;
            }
        };
        MandantKontext kontext = new MandantKontext(null, null, null) {
            @Override
            public <T> T mit(MandantCode mandant, Supplier<T> arbeit) {
                inhaberKontext = mandant;
                return arbeit.get();
            }
        };
        var ausgabe = new ByteArrayOutputStream();
        var fehler = new ByteArrayOutputStream();
        try (var aus = new PrintStream(ausgabe, true, StandardCharsets.UTF_8);
             var fehl = new PrintStream(fehler, true, StandardCharsets.UTF_8)) {
            inhaberExitCode = new InhaberEinladenKommando(anlegen, kontext).ausfuehren(List.of(
                    "inhaber-einladen", inhaberMandant.wert(), "--benutzer", "probe-inhaber",
                    "--email", "probe-inhaber@example.invalid", "--vorname", "Probe", "--nachname", "Inhaber",
                    "--wer", "probe-betreiber"), aus, fehl);
        }
        inhaberTerminalAusgabe = ausgabe.toString(StandardCharsets.UTF_8);
        inhaberTerminalFehler = fehler.toString(StandardCharsets.UTF_8);
    }

    @Dann("wird die erste Inhabereinladung über das Betreiberkommando ausgelöst")
    public void ersteInhabereinladungAkzeptiert() {
        assertThat(inhaberExitCode).isZero();
        assertThat(inhaberKontext).isEqualTo(inhaberMandant);
        assertThat(inhaberMandant).isEqualTo(new MandantCode("probe-adminrollen"));
        assertThat(inhaberEingabe).isNotNull();
        assertThat(inhaberEingabe.rolle()).isEqualTo("inhaber");
        assertThat(inhaberBetreiber).isEqualTo("probe-betreiber");
        assertThat(inhaberEingabe.benutzername()).isEqualTo("probe-inhaber");
        assertThat(inhaberEingabe.email()).isEqualTo("probe-inhaber@example.invalid");
    }

    @Dann("enthält die Terminalausgabe nur die Benutzerkennung")
    public void terminalausgabeEnthaeltNurBenutzerkennung() {
        assertThat(inhaberTerminalAusgabe).isEqualTo(inhaberId + System.lineSeparator());
        assertThat(inhaberTerminalFehler).isEmpty();
    }

    @Angenommen("ein aktiver Mandant hat einen bestehenden Keycloak-Benutzer ohne Verzeichniszeile")
    public void aktiverMandantMitNichtErfasstemBenutzer() {
        benutzerAnlegen();
        benutzerVorAbgleich = identitaet.benutzerLesen(URINET.wert(), benutzerId);
        mandantenStatus = status(true, Optional.empty());
    }

    @Wenn("der Betreiber den Benutzerabgleich ausführt")
    public void benutzerAbgleichen() {
        BenutzerVerzeichnisUebernahme uebernahme = new BenutzerVerzeichnisUebernahme(verzeichnis);
        BenutzerAbgleichen dienst = new BenutzerAbgleichen(identitaet, abgleichStand(), register(),
                identitaet, uebernahme, URINET.wert());
        abgleich = dienst.ausfuehren(URINET, false);
    }

    @Dann("wird die fehlende Zeile als Übernahme eingetragen")
    public void zeileAlsUebernahme() {
        assertThat(abgleich.uebernommen()).isEqualTo(1);
        assertThat(verzeichnis.eintraege).singleElement()
                .extracting(VerzeichnisEintrag::anlass).isEqualTo(VerzeichnisEintrag.Anlass.UEBERNAHME);
    }

    @Dann("bleiben Keycloak-Benutzer und Rolle unverändert")
    public void keycloakBenutzerUnveraendert() {
        assertThat(identitaet.benutzerLesen(URINET.wert(), benutzerId)).isEqualTo(benutzerVorAbgleich);
    }

    @Angenommen("ein deaktivierter Mandant hat einen aktiven Keycloak-Benutzer")
    public void deaktivierterMandantMitBenutzer() {
        benutzerAnlegen();
        mandantenStatus = status(false, Optional.of(DEAKTIVIERT));
    }

    @Wenn("der Betreiber den Sperrbefehl am 29. Tag ausführt")
    public void sperrbefehlAm29Tag() {
        sperrBericht = new BenutzerMandantSperren(register(), identitaet)
                .ausfuehren(URINET, DEAKTIVIERT.plus(Duration.ofDays(29)));
    }

    @Dann("ist der Benutzer deaktiviert und seine Sitzung beendet")
    public void benutzerDeaktiviert() {
        assertThat(sperrBericht.gesperrt()).isEqualTo(1);
        assertThat(identitaet.benutzerLesen(URINET.wert(), benutzerId).aktiv()).isFalse();
        assertThat(beendeteSitzungen).isEqualTo(1);
    }

    @Wenn("der Betreiber den Sperrbefehl am 30. Tag ausführt")
    public void sperrbefehlAm30Tag() {
        sperrBericht = new BenutzerMandantSperren(register(), identitaet)
                .ausfuehren(URINET, DEAKTIVIERT.plus(Duration.ofDays(30)));
    }

    @Dann("ist der Keycloak-Benutzer gelöscht")
    public void keycloakBenutzerGeloescht() {
        assertThat(sperrBericht.geloescht()).isEqualTo(1);
        assertThat(identitaet.benutzerZaehlen(URINET.wert())).isZero();
    }

    private void benutzerAnlegen() {
        benutzerId = identitaet.anlegen(URINET.wert(),
                new IdentitaetsDienst.Neuanlage("mitarbeiter", "test@example.invalid", "Test", "Person"),
                Rolle.MITARBEITER);
    }

    private MandantAbbauRegister.Status status(boolean aktiv, Optional<Instant> deaktiviertAm) {
        Mandant mandant = new Mandant(URINET, "Urinet", URINET.schema(), "urinet-archiv-urinet",
                Geschaeftsjahresende.STANDARD, aktiv, MandantVerwaltungsrollen.STANDARD);
        return new MandantAbbauRegister.Status(mandant, deaktiviertAm, Optional.empty(), false);
    }

    private MandantAbbauRegister register() {
        return new MandantAbbauRegister() {
            @Override
            public Optional<Status> status(MandantCode code) {
                return code.equals(URINET) ? Optional.ofNullable(mandantenStatus) : Optional.empty();
            }

            @Override
            public boolean deaktivieren(MandantCode code, UUID vollexportId, Instant jetzt, String wer, String grund) {
                throw new UnsupportedOperationException();
            }

            @Override
            public boolean abschliessen(MandantCode code) {
                throw new UnsupportedOperationException();
            }
        };
    }

    private static AbgleichStand abgleichStand() {
        return new AbgleichStand() {
            @Override
            public Stand lesen(MandantCode mandant) {
                return new Stand(Set.of(), Set.of(), Set.of());
            }

            @Override
            public boolean unklar(MandantCode mandant, UUID vorgang) {
                throw new UnsupportedOperationException();
            }
        };
    }

    private static final class TrackingIdentitaetsDienst extends IdentitaetsDienstStub {
        private final Runnable sitzungBeendet;

        private TrackingIdentitaetsDienst(Runnable sitzungBeendet) {
            this.sitzungBeendet = sitzungBeendet;
        }

        @Override
        public synchronized void sitzungenBeenden(String mandant, String id) {
            super.sitzungenBeenden(mandant, id);
            sitzungBeendet.run();
        }
    }

    private static final class VerzeichnisAttrappe implements BenutzerVerzeichnis {
        private final List<VerzeichnisEintrag> eintraege = new ArrayList<>();

        @Override
        public Optional<VerzeichnisEintrag> letzter(Optional<MandantCode> mandant, String benutzer) {
            return eintraege.stream().filter(eintrag -> eintrag.benutzer().equals(benutzer)).reduce((a, b) -> b);
        }

        @Override
        public void einfuegen(Optional<MandantCode> mandant, Neu neu) {
            Instant zeit = Instant.parse("2026-11-23T00:00:00Z");
            eintraege.add(new VerzeichnisEintrag(eintraege.size() + 1L, zeit, neu.benutzer(), neu.name(), neu.rolle(),
                    neu.gueltigVon().orElse(zeit), Optional.empty(), neu.anlass(), neu.erfasstVon()));
        }

        @Override
        public List<VerzeichnisEintrag> liste() {
            return List.copyOf(eintraege);
        }

        @Override
        public void sperren(Optional<MandantCode> mandant, String benutzer, Sperre sperre) {
        }
    }

}
