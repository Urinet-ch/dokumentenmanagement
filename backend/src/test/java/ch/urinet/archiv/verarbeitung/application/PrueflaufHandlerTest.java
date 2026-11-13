package ch.urinet.archiv.verarbeitung.application;

import ch.urinet.archiv.ablage.application.AblagePort;
import ch.urinet.archiv.ablage.domain.AblageGestoert;
import ch.urinet.archiv.ablage.domain.Dateipruefung;
import ch.urinet.archiv.ablage.domain.Objektversion;
import ch.urinet.archiv.ablage.domain.SperrAenderung;
import ch.urinet.archiv.ablage.domain.Sperrvorgabe;
import ch.urinet.archiv.ablage.domain.Stufe;
import ch.urinet.archiv.ablage.domain.UnsichereFortsetzung;
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
import ch.urinet.archiv.verarbeitung.domain.Nutzlast;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.InterruptedIOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
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
 * Prüflauf ohne Quarkus (Req 15.5, 15.9, 15.10, 16.24): Stufe P zählt nur, Stufe A verlängert höchstens bis jetzt + 1 Tag;
 * vorübergehende Fehler wiederholen den Teil, erst der letzte Versuch meldet {@code PRUEFFEHLER}.
 */
class PrueflaufHandlerTest {

    private static final Instant JETZT = Instant.parse("2026-12-01T03:00:00Z");
    private static final MandantCode MANDANT = new MandantCode("urinet");
    private static final UUID DOKUMENT = UUID.fromString("0b6f0a52-6d1e-4f43-9a8e-3a2d5c0e7f11");
    private static final String SCHLUESSEL = "archiv/2026/" + DOKUMENT + "/1-original.pdf";
    private static final String MANIFEST = "manifest/2026/11/30.json";
    private static final String SHA = "a".repeat(64);
    private static final Duration EIN_TAG = Duration.ofDays(1);

    private static final Instant BALD_ABGELAUFEN = JETZT.plus(Duration.ofHours(2));

    private final List<String> verlaengert = new ArrayList<>();
    private final List<UUID> mails = new ArrayList<>();
    /** Ausnahme, die {@code pruefen} für diesen Schlüssel wirft. */
    private final Map<String, RuntimeException> fehler = new HashMap<>();
    /** abweichende Sperre je Schlüssel; sonst läuft die Sperre in 2 Stunden ab */
    private final Map<String, Optional<Instant>> sperren = new HashMap<>();
    private final List<String> geprueft = new ArrayList<>();
    /** abweichende Prüfsumme im Object Storage je Schlüssel; sonst {@link #SHA} */
    private final Map<String, String> pruefsummen = new HashMap<>();
    private final List<String> abbrueche = new ArrayList<>();
    private final List<Zaehler> abbruchZaehler = new ArrayList<>();
    private final List<Abweichung> abbruchAbweichungen = new ArrayList<>();
    /** Präfix, unter dem {@code versionen} keine sichere Fortsetzung liefert */
    private Optional<String> unsicherUnter = Optional.empty();
    private final List<Optional<Versionsseite.Marke>> marken = new ArrayList<>();
    private List<Objektversion> archivVersionen = List.of(new Objektversion(SCHLUESSEL, "v1", false),
            new Objektversion(SCHLUESSEL, "v0", false));
    private List<Objektversion> manifestVersionen = List.of(new Objektversion(MANIFEST, "m1", false));
    private int dokumenteJeTeil = 100;
    private int versionenJeTeil = 500;
    private int abbruchNachFehlern = 5;

    @AfterEach
    void interruptFlagZuruecksetzen() {
        Thread.interrupted();
    }

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
    void dauerhafterFehlerBeiEinerDateiWirdGemeldetUndDerLaufGehtWeiter() throws Exception {
        Speicher speicher = new Speicher(Stufe.A);
        fehler.put(SCHLUESSEL, new IllegalStateException("Sperre nicht verlängert"));
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
    void voruebergehenderFehlerWiederholtDenTeilUndMeldetErstImLetztenVersuch() throws Exception {
        Speicher speicher = new Speicher(Stufe.A);
        fehler.put(SCHLUESSEL, new AblageGestoert(SCHLUESSEL, new RuntimeException("503")));
        PrueflaufHandler handler = handler(speicher);

        for (int versuche = 0; versuche < 2; versuche++) {
            Auftrag auftrag = auftrag(speicher.lauf.id(), 1, versuche);
            assertThatThrownBy(() -> handler.ausfuehren(auftrag)).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Teil wird wiederholt").hasRootCauseMessage("503")
                    .message().doesNotContain(SCHLUESSEL);
            assertThat(speicher.lauf.teil()).isZero();
            assertThat(speicher.abweichungen).isEmpty();
        }
        assertThat(Thread.currentThread().isInterrupted()).isFalse();

        handler.ausfuehren(auftrag(speicher.lauf.id(), 1, 2));
        assertThat(speicher.lauf.teil()).isEqualTo(1);
        assertThat(speicher.abweichungen).extracting(Abweichung::art).containsExactly(Art.PRUEFFEHLER);
    }

    @Test
    void voruebergehendeFehlerInFolgeBrechenDenTeilAbImLetztenVersuchOhneWeitereAufrufe() throws Exception {
        abbruchNachFehlern = 2;
        Speicher speicher = new Speicher(Stufe.A, 4);
        speicher.dokumente.forEach(d -> fehler.put(d.dateien().getFirst().schluessel(),
                new AblageGestoert(d.dateien().getFirst().schluessel(), new RuntimeException("503"))));
        PrueflaufHandler handler = handler(speicher);

        assertThatThrownBy(() -> handler.ausfuehren(auftrag(speicher.lauf.id(), 1, 1)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("vorübergehend gestört");
        assertThat(geprueft).hasSize(1);
        assertThat(speicher.lauf.teil()).isZero();

        geprueft.clear();
        handler.ausfuehren(auftrag(speicher.lauf.id(), 1, 2));
        // nach der Grenze ohne weiteren Aufruf als PRUEFFEHLER gemeldet
        assertThat(geprueft).hasSize(2);
        assertThat(speicher.lauf.zaehler().dateien()).isEqualTo(4);
        assertThat(speicher.abweichungen).hasSize(4).extracting(Abweichung::art).containsOnly(Art.PRUEFFEHLER);
        assertThat(speicher.abweichungen).extracting(Abweichung::dokumentId)
                .containsExactlyElementsOf(speicher.dokumente.stream().map(d -> Optional.of(d.id())).toList());
    }

    /**
     * Dauerhafte Fehler in Folge brechen ausser im letzten Versuch den Teil ab; im letzten Versuch prüft der Lauf
     * weiter, damit eine Folge unlesbarer Dateien keine falsche Prüfsumme danach verdeckt (Review PR #97).
     */
    @Test
    void dauerhafteFehlerInFolgeVerdeckenImLetztenVersuchKeineFalschePruefsumme() throws Exception {
        abbruchNachFehlern = 2;
        Speicher speicher = new Speicher(Stufe.A, 4);
        for (int i = 0; i < 3; i++) {
            fehler.put(speicher.dokumente.get(i).dateien().getFirst().schluessel(),
                    new IllegalStateException("verweigert"));
        }
        String verfaelscht = speicher.dokumente.get(3).dateien().getFirst().schluessel();
        pruefsummen.put(verfaelscht, "b".repeat(64));
        PrueflaufHandler handler = handler(speicher);

        assertThatThrownBy(() -> handler.ausfuehren(auftrag(speicher.lauf.id(), 1, 1)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("2 Prüffehler in Folge");
        assertThat(geprueft).hasSize(2);
        assertThat(speicher.lauf.teil()).isZero();

        geprueft.clear();
        handler.ausfuehren(auftrag(speicher.lauf.id(), 1, 2));
        assertThat(geprueft).hasSize(4);
        assertThat(speicher.abweichungen).extracting(Abweichung::art)
                .containsExactly(Art.PRUEFFEHLER, Art.PRUEFFEHLER, Art.PRUEFFEHLER, Art.PRUEFSUMME);
        assertThat(speicher.abweichungen.getLast().schluessel()).isEqualTo(verfaelscht);
    }

    /** Ein dauerhafter Fehler unterbricht die Folge vorübergehender Fehler. */
    @Test
    void dauerhafterFehlerSetztDieFolgeVoruebergehenderFehlerZurueck() throws Exception {
        abbruchNachFehlern = 2;
        Speicher speicher = new Speicher(Stufe.A, 4);
        List<String> schluessel = speicher.dokumente.stream().map(d -> d.dateien().getFirst().schluessel()).toList();
        fehler.put(schluessel.get(0), new AblageGestoert(schluessel.get(0), new RuntimeException("503")));
        fehler.put(schluessel.get(1), new IllegalStateException("verweigert"));
        fehler.put(schluessel.get(2), new AblageGestoert(schluessel.get(2), new RuntimeException("503")));
        pruefsummen.put(schluessel.get(3), "b".repeat(64));

        handler(speicher).ausfuehren(auftrag(speicher.lauf.id(), 1, 2));

        assertThat(geprueft).hasSize(4);
        assertThat(speicher.abweichungen).extracting(Abweichung::art)
                .containsExactly(Art.PRUEFFEHLER, Art.PRUEFFEHLER, Art.PRUEFFEHLER, Art.PRUEFSUMME);
    }

    @Test
    void erfolgreichePruefungSetztDieFolgeZurueck() throws Exception {
        abbruchNachFehlern = 2;
        Speicher speicher = new Speicher(Stufe.A, 3);
        fehler.put(speicher.dokumente.get(0).dateien().getFirst().schluessel(), new IllegalStateException("x"));
        fehler.put(speicher.dokumente.get(2).dateien().getFirst().schluessel(), new IllegalStateException("x"));

        handler(speicher).ausfuehren(auftrag(speicher.lauf.id(), 1, 0));

        assertThat(geprueft).hasSize(3);
        assertThat(speicher.abweichungen).extracting(Abweichung::art)
                .containsExactly(Art.PRUEFFEHLER, Art.PRUEFFEHLER);
    }

    /** Das SDK löscht das Interrupt-Flag bei einer Unterbrechung; erkannt wird sie an der Ursache, auch im letzten Versuch. */
    @Test
    void abbruchOhneInterruptFlagIstKeinPrueffehler() {
        Speicher speicher = new Speicher(Stufe.A);
        fehler.put(SCHLUESSEL, new UncheckedIOException(new InterruptedIOException("unterbrochen")));
        PrueflaufHandler handler = handler(speicher);

        assertThatThrownBy(() -> handler.ausfuehren(auftrag(speicher.lauf.id(), 1, 2)))
                .isInstanceOf(UncheckedIOException.class);
        assertThat(Thread.currentThread().isInterrupted()).as("Flag neu gesetzt").isTrue();
        assertThat(speicher.lauf.teil()).isZero();
        assertThat(speicher.abweichungen).isEmpty();
    }

    @Test
    void loeschmarkeUnterManifestIstEineAbweichung() throws Exception {
        manifestVersionen = List.of(new Objektversion(MANIFEST, "m2", true), new Objektversion(MANIFEST, "m1", false));
        Speicher speicher = new Speicher(Stufe.A);
        lauf(handler(speicher), speicher);

        assertThat(speicher.abweichungen).extracting(Abweichung::art)
                .containsExactly(Art.FREMDE_VERSION, Art.LOESCHMARKE);
        Abweichung marke = speicher.abweichungen.getLast();
        assertThat(marke.schluessel()).isEqualTo(MANIFEST);
        assertThat(marke.versionId()).contains("m2");
        assertThat(marke.dokumentId()).isEmpty();
        // die Löschmarke wird nicht gelesen; das Manifest dahinter schon
        assertThat(geprueft).containsExactly(SCHLUESSEL + "@v1", MANIFEST + "@m1");
        assertThat(speicher.lauf.zaehler().manifeste()).isEqualTo(1);
    }

    @Test
    void fehlendeSperreIstNurInStufeBEineAbweichung() throws Exception {
        sperren.put(SCHLUESSEL, Optional.empty());
        sperren.put(MANIFEST, Optional.of(JETZT.minusSeconds(1)));
        Speicher speicherB = new Speicher(Stufe.B);
        lauf(handler(speicherB), speicherB);

        assertThat(speicherB.abweichungen).extracting(Abweichung::art)
                .containsExactly(Art.SPERRE_FEHLT, Art.FREMDE_VERSION, Art.SPERRE_FEHLT);
        assertThat(speicherB.abweichungen.getFirst().dokumentId()).contains(DOKUMENT);
        assertThat(speicherB.abweichungen.getLast().schluessel()).isEqualTo(MANIFEST);
        // ohne aktive Sperre setzt der Prüflauf keine neue
        assertThat(verlaengert).isEmpty();

        Speicher speicherA = new Speicher(Stufe.A);
        lauf(handler(speicherA), speicherA);
        assertThat(speicherA.abweichungen).extracting(Abweichung::art).containsExactly(Art.FREMDE_VERSION);
    }

    @Test
    void fortsetzungUeberMehrereSeiten() throws Exception {
        dokumenteJeTeil = 1;
        versionenJeTeil = 1;
        archivVersionen = List.of(new Objektversion(SCHLUESSEL, "v1", false),
                new Objektversion(SCHLUESSEL, "v0", false), new Objektversion(SCHLUESSEL, "v9", true));
        manifestVersionen = List.of(new Objektversion(MANIFEST, "m1", false),
                new Objektversion("manifest/2026/12/01.json", "m2", false));
        Speicher speicher = new Speicher(Stufe.A, 2);
        lauf(handler(speicher), speicher);

        assertThat(speicher.lauf.zaehler()).isEqualTo(new Zaehler(2, 3, 2, 4, 0, 2));
        assertThat(marken).containsExactly(Optional.empty(), Optional.of(new Versionsseite.Marke(SCHLUESSEL, "v1")),
                Optional.of(new Versionsseite.Marke(SCHLUESSEL, "v0")),
                Optional.empty(), Optional.of(new Versionsseite.Marke(MANIFEST, "m1")));
        assertThat(speicher.abweichungen).extracting(Abweichung::art)
                .containsExactly(Art.FREMDE_VERSION, Art.LOESCHMARKE);
        // jede Datei und jedes Manifest genau einmal
        assertThat(geprueft).doesNotHaveDuplicates().hasSize(4);
    }

    /**
     * ARCHIVIEREN hält Status und Versions-IDs zwischen den beiden Lesungen fest. Gelesen wird zuerst der Status
     * ({@code ARCHIVIERUNG}, übersprungen), dann die Versionen; umgekehrt wäre die neue Version fremd.
     */
    @Test
    void archivierenZwischenDenLesungenErgibtKeineFremdeVersion() throws Exception {
        UUID neu = UUID.fromString("3c1d2e4f-5a6b-4c7d-8e9f-0a1b2c3d4e5f");
        String neuerSchluessel = "archiv/2026/" + neu + "/1-original.pdf";
        archivVersionen = List.of(new Objektversion(SCHLUESSEL, "v1", false),
                new Objektversion(neuerSchluessel, "n1", false));
        Speicher speicher = new Speicher(Stufe.A);
        speicher.status.put(neu, DokumentStatus.ARCHIVIERUNG);
        speicher.nachErsterLesung = () -> {
            speicher.status.put(neu, DokumentStatus.ARCHIVIERT);
            speicher.bekannt.add(new Bekannt(neuerSchluessel, "n1"));
        };
        lauf(handler(speicher), speicher);

        assertThat(speicher.abweichungen).isEmpty();
        assertThat(speicher.lesungen).containsExactly("status", "bekannte");
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

    /** Endgültig gescheiterter Teil (Req 15.5): Mail an den Inhaber nur mit IDs (Req 16.7), ohne Abweichungen keine. */
    @Test
    void nachDemLetztenFehlversuchErhaeltDerInhaberEineMeldung() throws Exception {
        Speicher speicher = new Speicher(Stufe.A);
        versionenJeTeil = 1;
        PrueflaufHandler handler = handler(speicher);
        handler.ausfuehren(auftrag(speicher.lauf.id(), 1));
        handler.ausfuehren(auftrag(speicher.lauf.id(), 2));
        assertThat(speicher.lauf.stand().marke()).isPresent();

        UUID fehlerId = UUID.randomUUID();
        handler.nachLetztemFehlversuch(auftrag(speicher.lauf.id(), 3, 3), fehlerId);

        assertThat(abbrueche).containsExactly(speicher.lauf.id() + " Teil 3 " + speicher.lauf.stand() + " "
                + fehlerId);
        assertThat(abbruchZaehler).containsExactly(speicher.lauf.zaehler());
        assertThat(abbruchAbweichungen).isEmpty();
        assertThat(mails).isEmpty();
    }

    /**
     * Abweichung in {@code DATEIEN}, danach bricht {@code ARCHIV_VERSIONEN} in jedem Versuch ab: Der Lauf schliesst nie
     * ab, die Abbruch-Mail nennt darum die schon festgehaltene Abweichung und die Zähler (Req 15.5, Review PR #98).
     */
    @Test
    void abbruchNachAbweichungMeldetDieAbweichung() throws Exception {
        pruefsummen.put(SCHLUESSEL, "b".repeat(64));
        unsicherUnter = Optional.of(PrueflaufHandler.ARCHIV);
        Speicher speicher = new Speicher(Stufe.A);
        PrueflaufHandler handler = handler(speicher);
        handler.ausfuehren(auftrag(speicher.lauf.id(), 1));
        assertThat(speicher.lauf.stand().phase()).isEqualTo(Phase.ARCHIV_VERSIONEN);

        for (int versuche = 0; versuche < 3; versuche++) {
            Auftrag auftrag = auftrag(speicher.lauf.id(), 2, versuche);
            assertThatThrownBy(() -> handler.ausfuehren(auftrag)).isInstanceOf(UnsichereFortsetzung.class);
        }
        handler.nachLetztemFehlversuch(auftrag(speicher.lauf.id(), 2, 3), UUID.randomUUID());

        assertThat(mails).as("abgeschlossen wird der Lauf nie").isEmpty();
        assertThat(abbrueche).hasSize(1);
        assertThat(abbruchZaehler).containsExactly(new Zaehler(1, 0, 0, 1, 0, 1));
        assertThat(abbruchAbweichungen).containsExactly(
                new Abweichung(Art.PRUEFSUMME, Optional.of(DOKUMENT), SCHLUESSEL, Optional.of("v1")));
    }

    @Test
    void nachDemLetztenFehlversuchKeineMeldungInStufePOderWennDerTeilFestgehaltenIst() throws Exception {
        Speicher speicher = new Speicher(Stufe.A);
        handler(speicher, Stufe.P).nachLetztemFehlversuch(auftrag(speicher.lauf.id(), 1, 3), UUID.randomUUID());

        PrueflaufHandler handler = handler(speicher);
        handler.ausfuehren(auftrag(speicher.lauf.id(), 1));
        handler.nachLetztemFehlversuch(auftrag(speicher.lauf.id(), 1, 3), UUID.randomUUID());
        handler.nachLetztemFehlversuch(new Auftrag(9, MANDANT, PrueflaufHandler.TYP, Nutzlast.leer(), 3),
                UUID.randomUUID());
        lauf(handler, speicher);
        handler.nachLetztemFehlversuch(auftrag(speicher.lauf.id(), speicher.lauf.teil() + 1, 3), UUID.randomUUID());

        assertThat(abbrueche).isEmpty();
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
            assertThat(teil).isLessThan(20);
            handler.ausfuehren(auftrag(speicher.lauf.id(), teil));
        }
    }

    private PrueflaufHandler handler(Speicher speicher) {
        return handler(speicher, speicher.lauf.stufe());
    }

    private PrueflaufHandler handler(Speicher speicher, Stufe stufe) {
        AblagePort ablage = (AblagePort) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{AblagePort.class}, (proxy, methode, argumente) -> switch (methode.getName()) {
                    case "pruefen" -> {
                        String schluessel = (String) argumente[1];
                        geprueft.add(schluessel + "@" + argumente[2]);
                        if (fehler.containsKey(schluessel)) {
                            throw fehler.get(schluessel);
                        }
                        yield Optional.of(new Dateipruefung(pruefsummen.getOrDefault(schluessel, SHA), 10,
                                sperren.getOrDefault(schluessel, Optional.of(BALD_ABGELAUFEN))));
                    }
                    case "versionen" -> {
                        @SuppressWarnings("unchecked")
                        Optional<Versionsseite.Marke> ab = (Optional<Versionsseite.Marke>) argumente[2];
                        marken.add(ab);
                        if (unsicherUnter.filter(argumente[1]::equals).isPresent()) {
                            throw new UnsichereFortsetzung((String) argumente[1], Optional.empty());
                        }
                        yield seite(argumente[1].equals("archiv/") ? archivVersionen : manifestVersionen, ab,
                                (int) argumente[3]);
                    }
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

            @Override
            public void prueflaufAbgebrochen(MandantCode mandant, UUID laufId, int teil, Fortsetzung stand,
                                             Zaehler bisher, List<Abweichung> erste, UUID fehlerId) {
                abbrueche.add(laufId + " Teil " + teil + " " + stand + " " + fehlerId);
                abbruchZaehler.add(bisher);
                abbruchAbweichungen.addAll(erste);
            }
        };
        Optional<Duration> frist = Optional.of(EIN_TAG);
        return new PrueflaufHandler(speicher, ablage, benachrichtigung, Clock.fixed(JETZT, ZoneOffset.UTC), stufe,
                frist, frist, dokumenteJeTeil, versionenJeTeil, abbruchNachFehlern);
    }

    /** Seite wie S3: nach der Marke (Schlüssel und Versions-ID) höchstens {@code anzahl}, Marke = letzte Version. */
    private static Versionsseite seite(List<Objektversion> alle, Optional<Versionsseite.Marke> ab, int anzahl) {
        int start = ab.map(m -> {
            for (int i = 0; i < alle.size(); i++) {
                if (alle.get(i).schluessel().equals(m.schluessel())
                        && m.versionId().orElseThrow().equals(alle.get(i).versionId())) {
                    return i + 1;
                }
            }
            throw new IllegalArgumentException("Marke unbekannt");
        }).orElse(0);
        int ende = Math.min(alle.size(), start + anzahl);
        List<Objektversion> teil = alle.subList(start, ende);
        return new Versionsseite(teil, ende < alle.size()
                ? Optional.of(new Versionsseite.Marke(teil.getLast().schluessel(), teil.getLast().versionId()))
                : Optional.empty());
    }

    private static Auftrag auftrag(UUID lauf, int teil) {
        return auftrag(lauf, teil, 0);
    }

    private static Auftrag auftrag(UUID lauf, int teil, int versuche) {
        return new Auftrag(teil, MANDANT, PrueflaufHandler.TYP, PrueflaufSpeichern.nutzlast(lauf, teil), versuche);
    }

    /**
     * Ein Lauf mit unbefristeten Dokumenten (das erste ist {@link #DOKUMENT} mit {@link #SCHLUESSEL}); v0 ist eine
     * fremde Version unter archiv/.
     */
    private static final class Speicher extends PrueflaufSpeichern {

        private Lauf lauf;
        private final List<Abweichung> abweichungen = new ArrayList<>();
        private final List<Pruefdokument> dokumente = new ArrayList<>();
        private final Map<UUID, DokumentStatus> status = new HashMap<>();
        private final Set<Bekannt> bekannt = new HashSet<>();
        private final List<String> lesungen = new ArrayList<>();
        private Runnable nachErsterLesung = () -> { };

        Speicher(Stufe stufe) {
            this(stufe, 1);
        }

        Speicher(Stufe stufe, int anzahl) {
            super(null, null, null);
            lauf = new Lauf(UUID.randomUUID(), stufe, 0, Fortsetzung.beginn(Phase.DATEIEN), Zaehler.NULL);
            for (int i = 0; i < anzahl; i++) {
                UUID id = i == 0 ? DOKUMENT : new UUID(DOKUMENT.getMostSignificantBits(), i);
                String schluessel = "archiv/2026/" + id + "/1-original.pdf";
                dokumente.add(new Pruefdokument(id, DokumentStatus.ARCHIVIERT, Sperrvorgabe.unbefristet(),
                        List.of(new Datei(schluessel, Optional.of("v1"), Optional.of(SHA)))));
                status.put(id, DokumentStatus.ARCHIVIERT);
                bekannt.add(new Bekannt(schluessel, "v1"));
            }
            dokumente.sort(Comparator.comparing(Pruefdokument::id));
        }

        @Override
        public Optional<Lauf> laden(UUID id) {
            return Optional.of(lauf);
        }

        @Override
        public List<Pruefdokument> archivierte(Optional<UUID> nach, int anzahl) {
            return dokumente.stream().filter(d -> nach.map(n -> d.id().compareTo(n) > 0).orElse(true))
                    .limit(anzahl).toList();
        }

        @Override
        public Set<Bekannt> bekannte(Collection<String> schluessel) {
            Set<Bekannt> ergebnis = bekannt.stream().filter(b -> schluessel.contains(b.schluessel()))
                    .collect(java.util.stream.Collectors.toSet());
            gelesen("bekannte");
            return ergebnis;
        }

        @Override
        public Map<UUID, DokumentStatus> status(Collection<UUID> ids) {
            Map<UUID, DokumentStatus> ergebnis = new HashMap<>();
            ids.forEach(id -> ergebnis.put(id, status.get(id)));
            gelesen("status");
            return ergebnis;
        }

        private void gelesen(String was) {
            lesungen.add(was);
            if (lesungen.size() == 1) {
                nachErsterLesung.run();
            }
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
