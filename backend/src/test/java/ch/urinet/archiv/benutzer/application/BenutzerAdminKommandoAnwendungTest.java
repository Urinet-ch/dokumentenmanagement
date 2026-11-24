package ch.urinet.archiv.benutzer.application;

import ch.urinet.archiv.benutzer.adapter.out.stub.IdentitaetsDienstStub;
import ch.urinet.archiv.mandant.application.MandantAbbauRegister;
import ch.urinet.archiv.mandant.domain.Geschaeftsjahresende;
import ch.urinet.archiv.mandant.domain.Mandant;
import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.mandant.domain.MandantVerwaltungsrollen;
import ch.urinet.archiv.protokoll.application.BenutzerVerzeichnis;
import ch.urinet.archiv.protokoll.domain.VerzeichnisEintrag;
import ch.urinet.archiv.sicherheit.domain.Rolle;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class BenutzerAdminKommandoAnwendungTest {

    private static final MandantCode URINET = new MandantCode("urinet");
    private static final Instant DEAKTIVIERT = Instant.parse("2026-10-01T00:00:00Z");

    @Test
    void abgleichImPruefmodusSchreibtNichtUndDieUebernahmeIstAppendOnlyUndIdempotent() {
        var identitaet = new IdentitaetsDienstStub();
        String id = identitaet.anlegen("urinet",
                new IdentitaetsDienst.Neuanlage("mitarbeiter", "test@example.invalid", "Test", "Person"),
                Rolle.MITARBEITER);
        var verzeichnis = new VerzeichnisAttrappe();
        var uebernahme = new BenutzerVerzeichnisUebernahme(verzeichnis);
        var dienst = new BenutzerAbgleichen(identitaet, neuerAbgleichStand(),
                register(status(true, Optional.empty())), identitaet, uebernahme, "urinet");

        var pruefung = dienst.ausfuehren(URINET, true);
        assertThat(pruefung.abweichungen()).isEmpty();
        assertThat(pruefung.uebernahmeGeplant()).isEqualTo(1);
        assertThat(pruefung.uebernommen()).isZero();
        assertThat(verzeichnis.eintraege).isEmpty();

        var lauf = dienst.ausfuehren(URINET, false);
        assertThat(lauf.uebernommen()).isEqualTo(1);
        assertThat(verzeichnis.letzter(Optional.of(URINET), id))
                .hasValueSatisfying(eintrag -> {
                    assertThat(eintrag.anlass()).isEqualTo(VerzeichnisEintrag.Anlass.UEBERNAHME);
                    assertThat(eintrag.name()).contains("mitarbeiter");
                    assertThat(eintrag.rolle()).contains("MITARBEITER");
                });
        assertThat(dienst.ausfuehren(URINET, false).uebernommen()).isZero();
        assertThat(verzeichnis.eintraege).hasSize(1);
    }

    @Test
    void mandatensperreDeaktiviertZugaengeUndLoeschtSieErstNachDreissigTagen() {
        var identitaet = new IdentitaetsDienstStub();
        String id = identitaet.anlegen("urinet",
                new IdentitaetsDienst.Neuanlage("person", "test@example.invalid", "Test", "Person"),
                Rolle.MITARBEITER);
        var register = register(status(false, Optional.of(DEAKTIVIERT)));
        var dienst = new BenutzerMandantSperren(register, identitaet);

        var gesperrt = dienst.ausfuehren(URINET, DEAKTIVIERT.plusSeconds(29L * 24 * 60 * 60));
        assertThat(gesperrt.gesperrt()).isEqualTo(1);
        assertThat(identitaet.benutzerLesen("urinet", id).aktiv()).isFalse();
        assertThat(identitaet.benutzerZaehlen("urinet")).isEqualTo(1);

        var geloescht = dienst.ausfuehren(URINET, DEAKTIVIERT.plusSeconds(30L * 24 * 60 * 60));
        assertThat(geloescht.geloescht()).isEqualTo(1);
        assertThat(identitaet.benutzerZaehlen("urinet")).isZero();
    }

    private static MandantAbbauRegister.Status status(boolean aktiv, Optional<Instant> deaktiviertAm) {
        Mandant mandant = new Mandant(URINET, "Urinet", URINET.schema(), "urinet-archiv-urinet",
                Geschaeftsjahresende.STANDARD, aktiv, MandantVerwaltungsrollen.STANDARD);
        return new MandantAbbauRegister.Status(mandant, deaktiviertAm, Optional.empty(), false);
    }

    private static MandantAbbauRegister register(MandantAbbauRegister.Status status) {
        return new MandantAbbauRegister() {
            @Override
            public Optional<Status> status(MandantCode code) {
                return code.equals(URINET) ? Optional.of(status) : Optional.empty();
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

    private static AbgleichStand neuerAbgleichStand() {
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
