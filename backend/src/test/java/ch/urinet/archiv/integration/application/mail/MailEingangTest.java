package ch.urinet.archiv.integration.application.mail;

import ch.urinet.archiv.integration.domain.mail.Postfach;
import ch.urinet.archiv.integration.domain.mail.Zulassungsliste;
import ch.urinet.archiv.mandant.application.MandantRegister;
import ch.urinet.archiv.mandant.domain.Geschaeftsjahresende;
import ch.urinet.archiv.mandant.domain.Mandant;
import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.sicherheit.application.Verschluesselung;
import ch.urinet.archiv.verarbeitung.application.InhaberBenachrichtigung;
import ch.urinet.archiv.verarbeitung.application.PrueflaufRegister;
import ch.urinet.archiv.verarbeitung.domain.Befund;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Abruf ohne Postfach oder Schlüssel, Fehlschläge in Folge und Einrichten (Spec 02, Req 1.1; design.md «Error
 * Handling»), ohne Quarkus mit Attrappen der Ports.
 */
class MailEingangTest {

    private static final MandantCode URINET = new MandantCode("urinet");

    private final Konfiguration konfiguration = new Konfiguration();
    private final Schluessel schluessel = new Schluessel();
    private final Postfaecher postfach = new Postfaecher();
    private final Post post = new Post();
    private final MailEingang eingang = new MailEingang(new Register(), null, konfiguration, schluessel, postfach,
            null, post, 50);
    private final MailEinrichten einrichten = new MailEinrichten(new Register(), konfiguration, schluessel);

    @Test
    void ohnePostfachOderInaktivRuftDasArchivNichtsAb() {
        assertThat(eingang.abrufen(URINET)).isEmpty();
        einrichten.einrichten("urinet", "imap.example.invalid", 993, "archiv@example.invalid", "pw",
                List.of("inhaber@example.invalid"));
        konfiguration.einstellung = Optional.of(new MailKonfigurationPort.Einstellung(
                konfiguration.einstellung.orElseThrow().postfach(),
                konfiguration.einstellung.get().passwortVerschluesselt(), konfiguration.einstellung.get().zulassung(),
                false));
        assertThat(eingang.abrufen(URINET)).isEmpty();
        assertThat(postfach.aufrufe).isZero();
    }

    @Test
    void ohneDatenschluesselRuftDasArchivNichtsAbUndEinrichtenLehntAb() {
        einrichten.einrichten("urinet", "imap.example.invalid", 993, "archiv@example.invalid", "pw",
                List.of("inhaber@example.invalid"));
        schluessel.verfuegbar = false;
        assertThat(eingang.abrufen(URINET)).isEmpty();
        assertThat(postfach.aufrufe).isZero();
        assertThatThrownBy(() -> einrichten.einrichten("urinet", "imap.example.invalid", 993, "a", "pw",
                List.of("inhaber@example.invalid"))).isInstanceOf(MailEinrichten.Fehler.class)
                .hasMessageContaining("archiv_data_key");
    }

    @Test
    void dasPasswortGehtNurVerschluesseltInDieKonfigurationUndEntschluesseltAnDenAbruf() {
        einrichten.einrichten("urinet", "imap.example.invalid", 993, "archiv@example.invalid", "geheim",
                List.of("inhaber@example.invalid"));
        assertThat(new String(konfiguration.einstellung.orElseThrow().passwortVerschluesselt()))
                .isEqualTo("enc[integration_mail.passwort:urinet]geheim");
        assertThat(eingang.abrufen(URINET)).contains(new PostfachPort.Abruf(0, 0));
        assertThat(postfach.passwort).isEqualTo("geheim");
        assertThat(postfach.max).isEqualTo(50);
    }

    @Test
    void nachSechsFehlschlaegenInFolgeGenauEineBenachrichtigungEinErfolgSetztZurueck() {
        einrichten.einrichten("urinet", "imap.example.invalid", 993, "archiv@example.invalid", "pw",
                List.of("inhaber@example.invalid"));
        postfach.scheitern = true;
        for (int i = 0; i < 7; i++) {
            eingang.abrufen(URINET);
        }
        assertThat(konfiguration.fehler).isEqualTo(7);
        assertThat(post.meldungen).containsExactly("urinet:6");
        postfach.scheitern = false;
        eingang.abrufen(URINET);
        assertThat(konfiguration.fehler).isZero();
    }

    @Test
    void einPasswortDasNichtZumSchluesselPasstZaehltAlsAusfallUndWirdGemeldet() {
        einrichten.einrichten("urinet", "imap.example.invalid", 993, "archiv@example.invalid", "pw",
                List.of("inhaber@example.invalid"));
        MailKonfigurationPort.Einstellung e = konfiguration.einstellung.orElseThrow();
        konfiguration.einstellung = Optional.of(new MailKonfigurationPort.Einstellung(e.postfach(),
                "beschaedigt".getBytes(), e.zulassung(), true));
        for (int i = 0; i < MailEingang.MELDEN_NACH; i++) {
            assertThat(eingang.abrufen(URINET)).isEmpty();
        }
        assertThat(postfach.aufrufe).isZero();
        assertThat(konfiguration.fehler).isEqualTo(MailEingang.MELDEN_NACH);
        assertThat(post.meldungen).containsExactly("urinet:6");
    }

    @Test
    void einrichtenNurFuerAktiveMandantenMitPasswortUndAbsender() {
        assertThatThrownBy(() -> einrichten.einrichten("andere", "imap.example.invalid", 993, "a", "pw",
                List.of("a@example.invalid"))).isInstanceOf(MailEinrichten.Fehler.class).hasMessageContaining("aktiv");
        assertThatThrownBy(() -> einrichten.einrichten("urinet", "imap.example.invalid", 993, "a", "",
                List.of("a@example.invalid"))).isInstanceOf(MailEinrichten.Fehler.class);
        assertThatThrownBy(() -> einrichten.einrichten("urinet", "imap.example.invalid", 993, "a", "pw",
                List.of())).isInstanceOf(MailEinrichten.Fehler.class);
        assertThat(konfiguration.einstellung).isEmpty();
    }

    private static final class Register implements MandantRegister {
        @Override
        public boolean existiert(MandantCode code) {
            return code.equals(URINET);
        }

        @Override
        public void eintragen(Mandant mandant) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<Mandant> aktive() {
            return List.of(new Mandant(URINET, "UriNet", URINET.schema(), "b", Geschaeftsjahresende.STANDARD, true));
        }

        @Override
        public Optional<Instant> erstellt(MandantCode code) {
            return Optional.empty();
        }
    }

    private static final class Konfiguration implements MailKonfigurationPort {
        Optional<Einstellung> einstellung = Optional.empty();
        int fehler;

        @Override
        public Optional<Einstellung> laden(MandantCode mandant) {
            return einstellung;
        }

        @Override
        public void speichern(MandantCode mandant, Postfach postfach, byte[] passwort, Zulassungsliste zulassung) {
            einstellung = Optional.of(new Einstellung(postfach, passwort, zulassung, true));
            fehler = 0;
        }

        @Override
        public boolean abschalten(MandantCode mandant) {
            return einstellung.isPresent();
        }

        @Override
        public int fehlschlagZaehlen(MandantCode mandant) {
            return ++fehler;
        }

        @Override
        public void erfolgreich(MandantCode mandant) {
            fehler = 0;
        }
    }

    /** Attrappe: «verschlüsselt» sichtbar mit Präfix, damit der Test den Bezug sieht. */
    private static final class Schluessel implements Verschluesselung {
        boolean verfuegbar = true;

        @Override
        public boolean verfuegbar() {
            return verfuegbar;
        }

        @Override
        public byte[] verschluesseln(byte[] klartext, String bezug) {
            return ("enc[" + bezug + "]" + new String(klartext)).getBytes();
        }

        @Override
        public byte[] entschluesseln(byte[] chiffrat, String bezug) {
            String text = new String(chiffrat);
            String praefix = "enc[" + bezug + "]";
            if (!text.startsWith(praefix)) {
                throw new IllegalArgumentException("Bezug");
            }
            return text.substring(praefix.length()).getBytes();
        }
    }

    private static final class Postfaecher implements PostfachPort {
        int aufrufe;
        boolean scheitern;
        String passwort;
        int max;

        @Override
        public Abruf abrufen(Postfach postfach, String passwort, int max, Verarbeiter verarbeiter) {
            aufrufe++;
            this.passwort = passwort;
            this.max = max;
            if (scheitern) {
                throw new PostfachNichtErreichbar("Anmeldung", new IllegalStateException());
            }
            return new Abruf(0, 0);
        }
    }

    private static final class Post implements InhaberBenachrichtigung {
        final List<String> meldungen = new ArrayList<>();

        @Override
        public void archivierungGescheitert(MandantCode mandant, UUID dokumentId, UUID fehlerId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void prueflaufAbweichungen(MandantCode mandant, UUID laufId, PrueflaufRegister.Zaehler zaehler,
                                          List<PrueflaufRegister.Abweichung> erste) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void prueflaufAbgebrochen(MandantCode mandant, UUID laufId, int teil,
                                         PrueflaufRegister.Fortsetzung stand, PrueflaufRegister.Zaehler bisher,
                                         List<PrueflaufRegister.Abweichung> erste, UUID fehlerId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean alarm(MandantCode mandant, Befund befund) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean mailAbrufGestoert(MandantCode mandant, int fehlschlaege) {
            meldungen.add(mandant.wert() + ":" + fehlschlaege);
            return true;
        }
    }
}
