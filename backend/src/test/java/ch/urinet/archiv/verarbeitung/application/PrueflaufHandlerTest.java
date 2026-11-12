package ch.urinet.archiv.verarbeitung.application;

import ch.urinet.archiv.ablage.application.AblagePort;
import ch.urinet.archiv.ablage.domain.Dateipruefung;
import ch.urinet.archiv.ablage.domain.Objektversion;
import ch.urinet.archiv.ablage.domain.SperrAenderung;
import ch.urinet.archiv.ablage.domain.Sperrvorgabe;
import ch.urinet.archiv.ablage.domain.Stufe;
import ch.urinet.archiv.ablage.domain.Versionsseite;
import ch.urinet.archiv.dokument.application.DokumentPrueflaufPort.Bekannt;
import ch.urinet.archiv.dokument.application.DokumentPrueflaufPort.Datei;
import ch.urinet.archiv.dokument.application.DokumentPrueflaufPort.Pruefdokument;
import ch.urinet.archiv.dokument.domain.DokumentStatus;
import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.verarbeitung.application.PrueflaufRegister.Abweichung;
import ch.urinet.archiv.verarbeitung.application.PrueflaufRegister.Art;
import ch.urinet.archiv.verarbeitung.application.PrueflaufRegister.Fortsetzung;
import ch.urinet.archiv.verarbeitung.application.PrueflaufRegister.Lauf;
import ch.urinet.archiv.verarbeitung.application.PrueflaufRegister.Phase;
import ch.urinet.archiv.verarbeitung.application.PrueflaufRegister.Zaehler;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Prüflauf ohne Quarkus (Req 15.9, 16.24): Stufe P zählt nur, Stufe A verlängert höchstens bis jetzt + 1 Tag. */
class PrueflaufHandlerTest {

    private static final Instant JETZT = Instant.parse("2026-12-01T03:00:00Z");
    private static final MandantCode MANDANT = new MandantCode("urinet");
    private static final UUID DOKUMENT = UUID.fromString("0b6f0a52-6d1e-4f43-9a8e-3a2d5c0e7f11");
    private static final String SCHLUESSEL = "archiv/2026/" + DOKUMENT + "/1-original.pdf";
    private static final String MANIFEST = "manifest/2026/11/30.json";
    private static final String SHA = "a".repeat(64);
    private static final Duration EIN_TAG = Duration.ofDays(1);

    private final List<String> verlaengert = new ArrayList<>();
    private final List<UUID> mails = new ArrayList<>();
    private String unlesbar;

    @Test
    void stufePVerlaengertNieUndZaehltNur() throws Exception {
        Speicher speicher = new Speicher(Stufe.P);
        lauf(handler(speicher), speicher);

        assertThat(verlaengert).isEmpty();
        assertThat(speicher.lauf.stand().phase()).isEqualTo(Phase.ABGESCHLOSSEN);
        assertThat(speicher.lauf.zaehler()).isEqualTo(new Zaehler(1, 2, 1, 0, 2, 1));
        // die fremde Version ist eine Abweichung, eine Mail gibt es in Stufe P trotzdem nicht
        assertThat(speicher.abweichungen).extracting(Abweichung::art).containsExactly(Art.FREMDE_VERSION);
        assertThat(mails).isEmpty();
    }

    @Test
    void stufeAVerlaengertHoechstensBisJetztPlusEinTag() throws Exception {
        Speicher speicher = new Speicher(Stufe.A);
        lauf(handler(speicher), speicher);

        assertThat(verlaengert).containsExactly(SCHLUESSEL + "@" + JETZT.plus(EIN_TAG),
                MANIFEST + "@" + JETZT.plus(EIN_TAG));
        assertThat(speicher.lauf.zaehler()).isEqualTo(new Zaehler(1, 2, 1, 2, 0, 1));
        assertThat(mails).containsExactly(speicher.lauf.id());
    }

    @Test
    void massgeblichIstDieKonfigurierteStufeNichtDieDesLaufs() throws Exception {
        // Lauf aus der Sicherung der Produktion (B), wiederhergestellt in der Restore-Probe (P)
        Speicher speicher = new Speicher(Stufe.B);
        lauf(handler(speicher, Stufe.P), speicher);

        assertThat(verlaengert).isEmpty();
        assertThat(speicher.lauf.zaehler().nurGezaehlt()).isEqualTo(2);
        assertThat(mails).isEmpty();
    }

    @Test
    void fehlerBeiEinerDateiWirdGemeldetUndDerLaufGehtWeiter() throws Exception {
        Speicher speicher = new Speicher(Stufe.A);
        unlesbar = SCHLUESSEL;
        lauf(handler(speicher), speicher);

        assertThat(speicher.lauf.stand().phase()).isEqualTo(Phase.ABGESCHLOSSEN);
        assertThat(speicher.abweichungen).extracting(Abweichung::art)
                .containsExactly(Art.PRUEFFEHLER, Art.FREMDE_VERSION);
        assertThat(speicher.abweichungen.getFirst().dokumentId()).contains(DOKUMENT);
        // das Manifest nach der unlesbaren Datei wird weiter gepflegt
        assertThat(verlaengert).containsExactly(MANIFEST + "@" + JETZT.plus(EIN_TAG));
        assertThat(mails).containsExactly(speicher.lauf.id());
    }

    @Test
    void schonFestgehaltenerTeilEndetOhneWirkung() throws Exception {
        Speicher speicher = new Speicher(Stufe.A);
        PrueflaufHandler handler = handler(speicher);
        handler.ausfuehren(auftrag(speicher.lauf.id(), 1));
        handler.ausfuehren(auftrag(speicher.lauf.id(), 1));
        assertThat(speicher.lauf.teil()).isEqualTo(1);
        assertThat(verlaengert).hasSize(1);
    }

    @Test
    void dokumentIdNurAusArchivSchluesseln() {
        assertThat(PrueflaufHandler.dokumentId(SCHLUESSEL)).contains(DOKUMENT);
        assertThat(PrueflaufHandler.dokumentId("archiv/2026/kein-uuid/1-original.pdf")).isEmpty();
        assertThat(PrueflaufHandler.dokumentId("archiv/2026/" + DOKUMENT)).isEmpty();
        assertThat(PrueflaufHandler.dokumentId("eingang/2026/" + DOKUMENT + "/1-original.pdf")).isEmpty();
    }

    private void lauf(PrueflaufHandler handler, Speicher speicher) throws Exception {
        for (int teil = 1; speicher.lauf.stand().phase() != Phase.ABGESCHLOSSEN; teil++) {
            assertThat(teil).isLessThan(10);
            handler.ausfuehren(auftrag(speicher.lauf.id(), teil));
        }
    }

    private PrueflaufHandler handler(Speicher speicher) {
        return handler(speicher, speicher.lauf.stufe());
    }

    private PrueflaufHandler handler(Speicher speicher, Stufe stufe) {
        Instant baldAbgelaufen = JETZT.plus(Duration.ofHours(2));
        AblagePort ablage = (AblagePort) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{AblagePort.class}, (proxy, methode, argumente) -> switch (methode.getName()) {
                    case "pruefen" -> {
                        if (argumente[1].equals(unlesbar)) {
                            throw new IllegalStateException("S3 503");
                        }
                        yield Optional.of(new Dateipruefung(SHA, 10, Optional.of(baldAbgelaufen)));
                    }
                    case "versionen" -> argumente[1].equals("archiv/")
                            ? new Versionsseite(List.of(new Objektversion(SCHLUESSEL, "v1", false),
                            new Objektversion(SCHLUESSEL, "v0", false)), Optional.empty())
                            : new Versionsseite(List.of(new Objektversion(MANIFEST, "m1", false)), Optional.empty());
                    case "sperreVerlaengernBis" -> {
                        verlaengert.add(argumente[1] + "@" + argumente[3]);
                        yield new SperrAenderung((String) argumente[1], (String) argumente[2],
                                SperrAenderung.Ergebnis.VERLAENGERT, Optional.of((Instant) argumente[3]));
                    }
                    default -> throw new UnsupportedOperationException(methode.getName());
                });
        InhaberBenachrichtigung benachrichtigung = new InhaberBenachrichtigung() {
            @Override
            public void archivierungGescheitert(MandantCode mandant, UUID dokumentId, UUID fehlerId) {
                throw new UnsupportedOperationException();
            }

            @Override
            public void prueflaufAbweichungen(MandantCode mandant, UUID laufId, Zaehler zaehler,
                                              List<Abweichung> erste) {
                mails.add(laufId);
            }
        };
        Optional<Duration> frist = Optional.of(EIN_TAG);
        return new PrueflaufHandler(speicher, ablage, benachrichtigung, Clock.fixed(JETZT, ZoneOffset.UTC), stufe,
                frist, frist, 100, 500);
    }

    private static Auftrag auftrag(UUID lauf, int teil) {
        return new Auftrag(teil, MANDANT, PrueflaufHandler.TYP, PrueflaufSpeichern.nutzlast(lauf, teil), 0);
    }

    /** Ein Lauf mit einem unbefristeten Dokument; v0 ist eine fremde Version unter archiv/. */
    private static final class Speicher extends PrueflaufSpeichern {

        private Lauf lauf;
        private final List<Abweichung> abweichungen = new ArrayList<>();

        Speicher(Stufe stufe) {
            super(null, null, null);
            lauf = new Lauf(UUID.randomUUID(), stufe, 0, Fortsetzung.beginn(Phase.DATEIEN), Zaehler.NULL);
        }

        @Override
        public Optional<Lauf> laden(UUID id) {
            return Optional.of(lauf);
        }

        @Override
        public List<Pruefdokument> archivierte(Optional<UUID> nach, int anzahl) {
            return List.of(new Pruefdokument(DOKUMENT, DokumentStatus.ARCHIVIERT, Sperrvorgabe.unbefristet(),
                    List.of(new Datei(SCHLUESSEL, Optional.of("v1"), Optional.of(SHA)))));
        }

        @Override
        public Set<Bekannt> bekannte(Collection<String> schluessel) {
            return Set.of(new Bekannt(SCHLUESSEL, "v1"));
        }

        @Override
        public Map<UUID, DokumentStatus> status(Collection<UUID> ids) {
            return Map.of(DOKUMENT, DokumentStatus.ARCHIVIERT);
        }

        @Override
        public List<Abweichung> abweichungen(UUID id, int anzahl) {
            return abweichungen.stream().limit(anzahl).toList();
        }

        @Override
        public boolean fortschreiben(Auftrag auftrag, UUID id, int teil, Fortsetzung weiter, Zaehler zuwachs,
                                     List<Abweichung> neue) {
            if (lauf.teil() != teil - 1) {
                return false;
            }
            lauf = new Lauf(id, lauf.stufe(), teil, weiter, lauf.zaehler().plus(zuwachs));
            abweichungen.addAll(neue);
            return true;
        }
    }
}
