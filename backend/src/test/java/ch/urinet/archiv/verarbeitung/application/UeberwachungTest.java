package ch.urinet.archiv.verarbeitung.application;

import ch.urinet.archiv.mandant.application.MandantRegister;
import ch.urinet.archiv.mandant.domain.Geschaeftsjahresende;
import ch.urinet.archiv.mandant.domain.Mandant;
import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.verarbeitung.domain.Befund;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Ablauf der Überwachung (Req 16.5) ohne Datenbank und Mail: Wiederholung (auch nach gescheitertem Freigeben),
 * Betriebs-Mandant, fehlende Anbindung, Einstellungen.
 */
class UeberwachungTest {

    private static final MandantCode URINET = new MandantCode("urinet");
    private static final MandantCode VHF = new MandantCode("vhf-gsk");
    private static final Befund HAENGT = new Befund(Befund.Quelle.DOKUMENT, UUID.randomUUID().toString(), "FEHLER");
    private static final Befund SICHERUNG = new Befund(Befund.Quelle.SICHERUNG, "export", "FEHLGESCHLAGEN");

    private final Speicher speicher = new Speicher();
    private final Post post = new Post();

    @Test
    void scheitertDieMailVersuchtEsDerNaechsteLaufWieder() {
        post.zustellen = false;
        Ueberwachung ueberwachung = ueberwachung(List.of(URINET), Optional.empty());

        assertThat(ueberwachung.pruefen()).isZero();
        post.zustellen = true;
        assertThat(ueberwachung.pruefen()).isEqualTo(1);
        assertThat(ueberwachung.pruefen()).isZero();
        assertThat(post.versuche).containsExactly(HAENGT, HAENGT);
    }

    @Test
    void scheitertAuchDasFreigebenSendetDerNaechsteLauf() {
        post.zustellen = false;
        speicher.freigebenScheitert = 1;
        Ueberwachung ueberwachung = ueberwachung(List.of(URINET), Optional.empty());

        assertThat(ueberwachung.pruefen()).isZero();
        assertThat(speicher.gemeldet).as("Anspruch bleibt nach gescheitertem Freigeben").isNotEmpty();
        post.zustellen = true;
        assertThat(ueberwachung.pruefen()).isEqualTo(1);
        assertThat(ueberwachung.pruefen()).isZero();
        assertThat(post.versuche).containsExactly(HAENGT, HAENGT);
    }

    @Test
    void sicherungNurBeimBetriebsMandanten() {
        Ueberwachung ueberwachung = ueberwachung(List.of(URINET, VHF), Optional.of(List.of(SICHERUNG)));

        ueberwachung.pruefen();

        assertThat(post.zugestellt).containsEntry(SICHERUNG, URINET);
        assertThat(post.zugestellt.keySet()).containsExactlyInAnyOrder(HAENGT, SICHERUNG);
    }

    @Test
    void nichtAngebundeneQuelleErledigtKeineAlarme() {
        Ueberwachung ueberwachung = ueberwachung(List.of(URINET), Optional.empty());

        ueberwachung.pruefen();

        assertThat(speicher.abgeglichen).containsOnly(Befund.Quelle.DOKUMENT);
    }

    @Test
    void einFehlerBeiEinemMandantenHaeltDieAnderenNichtAuf() {
        Ueberwachung ueberwachung = new Ueberwachung(register(List.of(VHF, URINET)), new DokumentUeberwachung() {
            @Override
            public List<Befund> laengerAls(MandantCode mandant, Duration grenze) {
                if (mandant.equals(VHF)) {
                    throw new IllegalStateException("Schema fehlt");
                }
                return List.of(HAENGT);
            }

            @Override
            public Map<String, Long> anzahlJeStatus(MandantCode mandant) {
                return Map.of();
            }
        }, m -> Optional.empty(), Optional::empty, speicher, post, Duration.ofHours(1), "urinet");

        assertThat(ueberwachung.pruefen()).isEqualTo(1);
    }

    @Test
    void grenzeMussPositivSein() {
        assertThatThrownBy(() -> new Ueberwachung(register(List.of()), null, null, null, speicher, post,
                Duration.ZERO, "urinet")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Ueberwachung.pruefeEinstellungen(Duration.ofSeconds(-1), "urinet"))
                .hasMessageContaining("archiv.ueberwachung.grenze");
    }

    @Test
    void betriebsMandantMussGueltigSein() {
        assertThatThrownBy(() -> Ueberwachung.pruefeEinstellungen(Duration.ofHours(1), "Urinet AG"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("archiv.ueberwachung.betrieb-mandant");
        assertThat(Ueberwachung.pruefeEinstellungen(Duration.ofHours(1), "vhf-gsk")).isEqualTo(VHF);
    }

    private Ueberwachung ueberwachung(List<MandantCode> mandanten, Optional<List<Befund>> sicherung) {
        DokumentUeberwachung dokumente = new DokumentUeberwachung() {
            @Override
            public List<Befund> laengerAls(MandantCode mandant, Duration grenze) {
                return mandant.equals(URINET) ? List.of(HAENGT) : List.of();
            }

            @Override
            public Map<String, Long> anzahlJeStatus(MandantCode mandant) {
                return Map.of();
            }
        };
        return new Ueberwachung(register(mandanten), dokumente, m -> Optional.empty(), () -> sicherung, speicher, post,
                Duration.ofHours(1), "urinet");
    }

    private static MandantRegister register(List<MandantCode> mandanten) {
        return new MandantRegister() {
            @Override
            public boolean existiert(MandantCode code) {
                return mandanten.contains(code);
            }

            @Override
            public void eintragen(Mandant mandant) {
                throw new UnsupportedOperationException();
            }

            @Override
            public List<Mandant> aktive() {
                return mandanten.stream().map(c -> new Mandant(c, c.wert(), c.schema(), "b-" + c.wert(),
                        Geschaeftsjahresende.STANDARD, true)).toList();
            }

            @Override
            public Optional<Instant> erstellt(MandantCode code) {
                throw new UnsupportedOperationException();
            }
        };
    }

    /** Alarme im Speicher, gleiche Regeln wie die Tabelle {@code alarm}. */
    private static final class Speicher implements AlarmSpeicher {

        private final Set<String> offen = new HashSet<>();
        private final Map<String, Instant> gemeldet = new HashMap<>();
        private int freigebenScheitert;
        private long uhr;
        private final List<Befund.Quelle> abgeglichen = new ArrayList<>();

        @Override
        public void abgleichen(MandantCode mandant, Befund.Quelle quelle, Collection<Befund> befunde) {
            abgeglichen.add(quelle);
            befunde.forEach(b -> offen.add(mandant.wert() + "/" + b.schluessel()));
        }

        @Override
        public Optional<Instant> beanspruchen(MandantCode mandant, Befund befund) {
            String schluessel = mandant.wert() + "/" + befund.schluessel();
            if (!offen.contains(schluessel) || gemeldet.containsKey(schluessel)) {
                return Optional.empty();
            }
            Instant anspruch = Instant.EPOCH.plusMillis(++uhr);
            gemeldet.put(schluessel, anspruch);
            return Optional.of(anspruch);
        }

        @Override
        public void freigeben(MandantCode mandant, Befund befund, Instant anspruch) {
            if (freigebenScheitert > 0) {
                freigebenScheitert--;
                throw new IllegalStateException("Verbindung nicht erhalten");
            }
            gemeldet.remove(mandant.wert() + "/" + befund.schluessel(), anspruch);
        }
    }

    private static final class Post implements InhaberBenachrichtigung {

        private boolean zustellen = true;
        private final List<Befund> versuche = new ArrayList<>();
        private final Map<Befund, MandantCode> zugestellt = new HashMap<>();

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
        public void manifestFehlt(MandantCode mandant, LocalDate datum, UUID fehlerId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean alarm(MandantCode mandant, Befund befund) {
            versuche.add(befund);
            if (zustellen) {
                zugestellt.put(befund, mandant);
            }
            return zustellen;
        }
    }
}
