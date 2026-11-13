package ch.urinet.archiv.verarbeitung.application;

import ch.urinet.archiv.ablage.application.AblagePort;
import ch.urinet.archiv.ablage.domain.AblageGestoert;
import ch.urinet.archiv.ablage.domain.Dateipruefung;
import ch.urinet.archiv.ablage.domain.SperrAenderung;
import ch.urinet.archiv.ablage.domain.Sperrfrist;
import ch.urinet.archiv.ablage.domain.Sperrpflege;
import ch.urinet.archiv.ablage.domain.Sperrvorgabe;
import ch.urinet.archiv.ablage.domain.Stufe;
import ch.urinet.archiv.ablage.domain.Objektversion;
import ch.urinet.archiv.ablage.domain.Versionsseite;
import ch.urinet.archiv.dokument.application.DokumentPrueflaufPort.Bekannt;
import ch.urinet.archiv.dokument.application.DokumentPrueflaufPort.Datei;
import ch.urinet.archiv.dokument.application.DokumentPrueflaufPort.Pruefdokument;
import ch.urinet.archiv.dokument.domain.DokumentStatus;
import ch.urinet.archiv.gemeinsam.domain.Ursachen;
import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.verarbeitung.application.PrueflaufRegister.Abweichung;
import ch.urinet.archiv.verarbeitung.application.PrueflaufRegister.Art;
import ch.urinet.archiv.verarbeitung.application.PrueflaufRegister.Fortsetzung;
import ch.urinet.archiv.verarbeitung.application.PrueflaufRegister.Lauf;
import ch.urinet.archiv.verarbeitung.application.PrueflaufRegister.Phase;
import ch.urinet.archiv.verarbeitung.application.PrueflaufRegister.Zaehler;
import ch.urinet.archiv.verarbeitung.domain.AuftragTyp;
import ch.urinet.archiv.verarbeitung.domain.Wiederholung;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Handler für {@code PRUEFLAUF} (Aufgabe 5.3; Req 6.9, 15.5, 15.9, 15.10, 16.24; design.md «Prüflauf»). Ein Lauf
 * besteht aus Teilaufträgen, jeder prüft eine Seite und reiht den nächsten ein:
 * <ol>
 *     <li>{@code DATEIEN}: jede Datei unter {@code archiv/} über die gespeicherte Versions-ID stromweise lesen und die
 *     SHA-256 mit der Datenbank vergleichen; aktive Sperren unbefristeter Dokumente mit weniger als 12 Monaten Rest
 *     verlängern.</li>
 *     <li>{@code ARCHIV_VERSIONEN}: alle Versionen unter {@code archiv/} auflisten; Löschmarken und Versionen ohne Eintrag
 *     in der Datenbank sind Abweichungen.</li>
 *     <li>{@code MANIFEST_VERSIONEN}: Manifeste lesen und ihre Sperren wie oben pflegen; Löschmarken melden.</li>
 * </ol>
 * Massgeblich ist die Stufe der laufenden Konfiguration ({@code archiv.stufe}), nicht die beim Anlegen im Lauf
 * gespeicherte: Ein Lauf aus der Sicherung der Produktion (B) verlängert in der Restore-Probe (P) nichts.
 * In Stufe P wird keine Sperre verlängert, nur gezählt (Req 16.24), und keine E-Mail versandt. Eine fehlende verlangte
 * Sperre ist nur in Stufe B eine Abweichung; in den Stufen A und P laufen Sperren nach einem Tag ab (Req 6.11). Am Ende
 * Bericht im Log und in {@code prueflauf}; bei Abweichungen E-Mail an den Inhaber, nur mit IDs (Req 16.7).
 * <p>
 * Fehler beim Prüfen einer einzelnen Datei oder Manifest-Version (S3, Lesen, Verlängern): Ist der Object Storage
 * vorübergehend gestört ({@link AblageGestoert}) oder scheitern {@code archiv.prueflauf.abbruch-nach-fehlern} Prüfungen
 * in Folge, bricht der Teil ohne Wirkung ab, und die Auftragsschlange wiederholt ihn. Erst im letzten Versuch
 * ({@link Wiederholung#MAX_VERSUCHE}) hält der Lauf solche Fehler als {@code PRUEFFEHLER} fest und prüft weiter; nach
 * der Grenze in Folge meldet er den Rest des Teils ohne weiteren Aufruf als {@code PRUEFFEHLER}, damit der Teil nicht
 * an der Zeitgrenze scheitert. Ein einzelner dauerhafter Fehler wird sofort als {@code PRUEFFEHLER} festgehalten, damit
 * eine dauerhaft unlesbare Datei nicht den ganzen Lauf blockiert. Eine Unterbrechung (Zeitgrenze, Herunterfahren) gilt
 * nie als Prüffehler, auch wenn das SDK das Interrupt-Flag gelöscht hat: Sie wird an der Ursache erkannt.
 * <p>
 * Idempotent: Ein Teil, den der Lauf schon festgehalten hat, endet ohne Wirkung; Verlängerungen sind ohnehin
 * wiederholbar (nie verkürzt).
 */
@ApplicationScoped
public class PrueflaufHandler implements AuftragHandler {

    public static final AuftragTyp TYP = new AuftragTyp("PRUEFLAUF");
    public static final String LAUF_ID = "laufId";
    public static final String TEIL = "teil";
    static final String ARCHIV = "archiv/";
    static final String MANIFEST = "manifest/";
    static final int ABWEICHUNGEN_IN_MAIL = 50;

    private static final Logger LOG = Logger.getLogger(PrueflaufHandler.class);

    private final PrueflaufSpeichern speichern;
    private final AblagePort ablage;
    private final InhaberBenachrichtigung benachrichtigung;
    private final Clock uhr;
    private final Stufe stufe;
    private final Optional<Duration> archivHoechstfrist;
    private final Optional<Duration> manifestHoechstfrist;
    private final int dokumenteJeTeil;
    private final int versionenJeTeil;
    private final int abbruchNachFehlern;

    @Inject
    public PrueflaufHandler(PrueflaufSpeichern speichern, AblagePort ablage, InhaberBenachrichtigung benachrichtigung,
                            @ConfigProperty(name = "archiv.stufe") Optional<String> stufe,
                            @ConfigProperty(name = "archiv.ablage.max-sperrfrist") Optional<Duration> archivHoechstfrist,
                            @ConfigProperty(name = "archiv.ablage.manifest-max-sperrfrist")
                            Optional<Duration> manifestHoechstfrist,
                            @ConfigProperty(name = "archiv.prueflauf.dokumente-je-teil", defaultValue = "100")
                            int dokumenteJeTeil,
                            @ConfigProperty(name = "archiv.prueflauf.versionen-je-teil", defaultValue = "500")
                            int versionenJeTeil,
                            @ConfigProperty(name = "archiv.prueflauf.abbruch-nach-fehlern", defaultValue = "5")
                            int abbruchNachFehlern) {
        this(speichern, ablage, benachrichtigung, Clock.systemUTC(), Stufe.aus(stufe), archivHoechstfrist,
                manifestHoechstfrist, dokumenteJeTeil, versionenJeTeil, abbruchNachFehlern);
    }

    PrueflaufHandler(PrueflaufSpeichern speichern, AblagePort ablage, InhaberBenachrichtigung benachrichtigung,
                     Clock uhr, Stufe stufe, Optional<Duration> archivHoechstfrist,
                     Optional<Duration> manifestHoechstfrist,
                     int dokumenteJeTeil, int versionenJeTeil, int abbruchNachFehlern) {
        if (dokumenteJeTeil < 1 || versionenJeTeil < 1 || versionenJeTeil > 1000 || abbruchNachFehlern < 1) {
            throw new IllegalStateException("archiv.prueflauf.dokumente-je-teil und "
                    + "archiv.prueflauf.abbruch-nach-fehlern müssen ≥ 1, "
                    + "archiv.prueflauf.versionen-je-teil zwischen 1 und 1000 liegen");
        }
        this.speichern = speichern;
        this.ablage = ablage;
        this.benachrichtigung = benachrichtigung;
        this.uhr = uhr;
        this.stufe = stufe;
        this.archivHoechstfrist = archivHoechstfrist;
        this.manifestHoechstfrist = manifestHoechstfrist;
        this.dokumenteJeTeil = dokumenteJeTeil;
        this.versionenJeTeil = versionenJeTeil;
        this.abbruchNachFehlern = abbruchNachFehlern;
    }

    @Override
    public AuftragTyp typ() {
        return TYP;
    }

    @Override
    public void ausfuehren(Auftrag auftrag) throws Exception {
        UUID id = auftrag.nutzlast().uuid(LAUF_ID)
                .orElseThrow(() -> new IllegalArgumentException("Nutzlast ohne " + LAUF_ID));
        int teil = Math.toIntExact(auftrag.nutzlast().zahl(TEIL)
                .orElseThrow(() -> new IllegalArgumentException("Nutzlast ohne " + TEIL)));
        Lauf lauf = speichern.laden(id).orElseThrow(() -> new IllegalStateException("Prüflauf fehlt: " + id));
        if (lauf.teil() >= teil || lauf.stand().phase() == Phase.ABGESCHLOSSEN) {
            LOG.infof("Prüflauf %s Teil %d schon festgehalten", id, teil);
            return;
        }
        if (lauf.teil() != teil - 1) {
            throw new IllegalStateException("Prüflauf " + id + " steht auf Teil " + lauf.teil() + ", nicht " + (teil - 1));
        }
        if (lauf.stufe() != stufe) {
            LOG.warnf("Prüflauf %s angelegt in Stufe %s, läuft in Stufe %s: massgeblich ist %s", id, lauf.stufe(),
                    stufe, stufe);
        }
        Sammler s = new Sammler(id, teil, auftrag.versuche() + 1 >= Wiederholung.MAX_VERSUCHE);
        Ergebnis ergebnis = switch (lauf.stand().phase()) {
            case DATEIEN -> dateien(auftrag.mandant(), lauf, s);
            case ARCHIV_VERSIONEN -> archivVersionen(auftrag.mandant(), lauf, s);
            case MANIFEST_VERSIONEN -> manifeste(auftrag.mandant(), lauf, s);
            case ABGESCHLOSSEN -> throw new IllegalStateException("unerreichbar");
        };
        if (!speichern.fortschreiben(auftrag, id, teil, ergebnis.weiter(), ergebnis.zaehler(),
                ergebnis.abweichungen())) {
            LOG.infof("Prüflauf %s Teil %d schon festgehalten", id, teil);
            return;
        }
        if (ergebnis.weiter().phase() == Phase.ABGESCHLOSSEN) {
            abschliessen(auftrag.mandant(), id, lauf.zaehler().plus(ergebnis.zaehler()));
        }
    }

    private Ergebnis dateien(MandantCode mandant, Lauf lauf, Sammler s) throws InterruptedException {
        List<Pruefdokument> seite = speichern.archivierte(lauf.stand().nachDokument(), dokumenteJeTeil);
        for (Pruefdokument dokument : seite) {
            for (Datei datei : dokument.dateien()) {
                unterbrochen();
                s.dateien++;
                if (s.gestoert) {
                    s.abweichung(Art.PRUEFFEHLER, Optional.of(dokument.id()), datei.schluessel(), datei.versionId());
                    continue;
                }
                try {
                    pruefeDatei(mandant, dokument, datei, s);
                    s.fehlerInFolge = 0;
                } catch (RuntimeException e) {
                    prueffehler(e, Optional.of(dokument.id()), datei.schluessel(), datei.versionId(), s);
                }
            }
        }
        Fortsetzung weiter = seite.size() < dokumenteJeTeil
                ? Fortsetzung.beginn(Phase.ARCHIV_VERSIONEN)
                : new Fortsetzung(Phase.DATEIEN, Optional.of(seite.getLast().id()), Optional.empty());
        return s.ergebnis(weiter);
    }

    private void pruefeDatei(MandantCode mandant, Pruefdokument dokument, Datei datei, Sammler s) {
        if (datei.versionId().isEmpty()) {
            s.abweichung(Art.VERSION_FEHLT, Optional.of(dokument.id()), datei.schluessel(), Optional.empty());
            return;
        }
        String version = datei.versionId().get();
        Optional<Dateipruefung> pruefung = ablage.pruefen(mandant, datei.schluessel(), version);
        if (pruefung.isEmpty()) {
            s.abweichung(Art.VERSION_FEHLT, Optional.of(dokument.id()), datei.schluessel(), datei.versionId());
            return;
        }
        if (datei.sha256().filter(sha -> sha.equalsIgnoreCase(pruefung.get().sha256())).isEmpty()) {
            s.abweichung(Art.PRUEFSUMME, Optional.of(dokument.id()), datei.schluessel(), datei.versionId());
        }
        Instant jetzt = uhr.instant();
        Optional<Instant> bisher = pruefung.get().sperreBis();
        Sperrvorgabe vorgabe = dokument.vorgabe();
        if (stufe == Stufe.B && Sperrfrist.retainUntil(vorgabe, jetzt, Optional.empty()).isPresent()
                && bisher.filter(bis -> bis.isAfter(jetzt)).isEmpty()) {
            s.abweichung(Art.SPERRE_FEHLT, Optional.of(dokument.id()), datei.schluessel(), datei.versionId());
        }
        if (unbefristet(vorgabe)) {
            pflegen(mandant, datei.schluessel(), version, bisher, archivHoechstfrist, s);
        }
    }

    /**
     * Reihenfolge gegen den Wettlauf mit {@code ARCHIVIEREN}: Das Archivieren setzt {@code ARCHIVIERUNG}, legt die
     * Versionen ab und hält danach Status {@code ARCHIVIERT} und Versions-IDs in einer Transaktion fest. Gelistet ist vor
     * beiden Lesungen; darum zuerst den Status, dann die bekannten Versionen lesen: Wer {@code ARCHIVIERUNG} sieht,
     * überspringt, wer {@code ARCHIVIERT} sieht, sieht danach auch die Versions-IDs (READ COMMITTED).
     */
    private Ergebnis archivVersionen(MandantCode mandant, Lauf lauf, Sammler s) {
        Versionsseite seite = ablage.versionen(mandant, ARCHIV, lauf.stand().marke(), versionenJeTeil);
        Set<String> schluessel = new HashSet<>();
        Set<UUID> ids = new HashSet<>();
        for (Objektversion v : seite.versionen()) {
            if (!v.loeschmarke()) {
                schluessel.add(v.schluessel());
                dokumentId(v.schluessel()).ifPresent(ids::add);
            }
        }
        Map<UUID, DokumentStatus> status = speichern.status(ids);
        Set<Bekannt> bekannt = speichern.bekannte(schluessel);
        for (Objektversion v : seite.versionen()) {
            s.versionen++;
            Optional<UUID> dokument = dokumentId(v.schluessel());
            if (v.loeschmarke()) {
                s.abweichung(Art.LOESCHMARKE, dokument, v.schluessel(), Optional.of(v.versionId()));
            } else if (!bekannt.contains(new Bekannt(v.schluessel(), v.versionId()))
                    // während ARCHIVIERUNG stehen die Versions-IDs noch nicht in der Datenbank (design.md «Ablage»)
                    && dokument.map(status::get).filter(st -> st == DokumentStatus.ARCHIVIERUNG).isEmpty()) {
                s.abweichung(Art.FREMDE_VERSION, dokument, v.schluessel(), Optional.of(v.versionId()));
            }
        }
        Fortsetzung weiter = seite.weiter().isEmpty()
                ? Fortsetzung.beginn(Phase.MANIFEST_VERSIONEN)
                : new Fortsetzung(Phase.ARCHIV_VERSIONEN, Optional.empty(), seite.weiter());
        return s.ergebnis(weiter);
    }

    private Ergebnis manifeste(MandantCode mandant, Lauf lauf, Sammler s) throws InterruptedException {
        Versionsseite seite = ablage.versionen(mandant, MANIFEST, lauf.stand().marke(), versionenJeTeil);
        Instant jetzt = uhr.instant();
        for (Objektversion v : seite.versionen()) {
            unterbrochen();
            if (v.loeschmarke()) {
                s.abweichung(Art.LOESCHMARKE, Optional.empty(), v.schluessel(), Optional.of(v.versionId()));
                continue;
            }
            s.manifeste++;
            if (s.gestoert) {
                s.abweichung(Art.PRUEFFEHLER, Optional.empty(), v.schluessel(), Optional.of(v.versionId()));
                continue;
            }
            try {
                pruefeManifest(mandant, v, jetzt, s);
                s.fehlerInFolge = 0;
            } catch (RuntimeException e) {
                prueffehler(e, Optional.empty(), v.schluessel(), Optional.of(v.versionId()), s);
            }
        }
        Fortsetzung weiter = seite.weiter().isEmpty()
                ? Fortsetzung.beginn(Phase.ABGESCHLOSSEN)
                : new Fortsetzung(Phase.MANIFEST_VERSIONEN, Optional.empty(), seite.weiter());
        return s.ergebnis(weiter);
    }

    private void pruefeManifest(MandantCode mandant, Objektversion v, Instant jetzt, Sammler s) {
        Optional<Dateipruefung> pruefung = ablage.pruefen(mandant, v.schluessel(), v.versionId());
        if (pruefung.isEmpty()) {
            s.abweichung(Art.VERSION_FEHLT, Optional.empty(), v.schluessel(), Optional.of(v.versionId()));
            return;
        }
        Optional<Instant> bisher = pruefung.get().sperreBis();
        if (stufe == Stufe.B && bisher.filter(bis -> bis.isAfter(jetzt)).isEmpty()) {
            s.abweichung(Art.SPERRE_FEHLT, Optional.empty(), v.schluessel(), Optional.of(v.versionId()));
        }
        pflegen(mandant, v.schluessel(), v.versionId(), bisher, manifestHoechstfrist, s);
    }

    /**
     * Fehler bei einer Datei. Eine Unterbrechung reicht er weiter, auch wenn das SDK das Interrupt-Flag gelöscht hat (an
     * der Ursache erkannt; das Flag wird neu gesetzt). Ausser im letzten Versuch bricht ein vorübergehender Fehler oder
     * die Grenze in Folge den Teil ab, damit die Auftragsschlange ihn wiederholt; sonst festhalten und weiterprüfen.
     */
    private void prueffehler(RuntimeException e, Optional<UUID> dokument, String schluessel,
                             Optional<String> version, Sammler s) {
        if (Thread.currentThread().isInterrupted() || Ursachen.unterbrechung(e)) {
            Thread.currentThread().interrupt();
            throw e;
        }
        s.fehlerInFolge++;
        boolean voruebergehend = Ursachen.enthaelt(e, AblageGestoert.class);
        boolean grenze = s.fehlerInFolge >= abbruchNachFehlern;
        if (!s.letzterVersuch && (voruebergehend || grenze)) {
            throw new IllegalStateException("Prüflauf " + s.lauf + " Teil " + s.teil + ": "
                    + (voruebergehend ? "Object Storage vorübergehend gestört" : s.fehlerInFolge + " Prüffehler in Folge")
                    + ", Teil wird wiederholt", e);
        }
        // nur Schlüssel und Klassennamen, keine Inhalte (Req 16.7)
        LOG.warnf("Prüflauf %s Teil %d: %s nicht geprüft, ursache=%s", s.lauf, s.teil, schluessel, Ursachen.kette(e));
        s.abweichung(Art.PRUEFFEHLER, dokument, schluessel, version);
        if (grenze && !s.gestoert) {
            s.gestoert = true;
            LOG.warnf("Prüflauf %s Teil %d: %d Prüffehler in Folge im letzten Versuch, Rest des Teils als PRUEFFEHLER "
                    + "ohne weitere Prüfung", s.lauf, s.teil, s.fehlerInFolge);
        }
    }

    private void pflegen(MandantCode mandant, String schluessel, String version,
                         Optional<Instant> bisher, Optional<Duration> hoechstfrist, Sammler s) {
        Sperrpflege pflege = Sperrpflege.entscheiden(stufe, bisher, uhr.instant(), hoechstfrist);
        switch (pflege.art()) {
            case NUR_ZAEHLEN -> s.nurGezaehlt++;
            case VERLAENGERN -> {
                SperrAenderung aenderung = ablage.sperreVerlaengernBis(mandant, schluessel, version,
                        pflege.bis().orElseThrow());
                if (aenderung.ergebnis() == SperrAenderung.Ergebnis.VERLAENGERT) {
                    s.verlaengert++;
                }
            }
            case OHNE_SPERRE, AUSREICHEND, GEKAPPT -> {
                // nichts zu tun; eine fehlende Sperre meldet der Aufrufer in Stufe B
            }
        }
    }

    private void abschliessen(MandantCode mandant, UUID id, Zaehler zaehler) {
        LOG.infof("Prüflauf %s abgeschlossen (Stufe %s): dateien=%d versionen=%d manifeste=%d verlaengert=%d "
                        + "nur_gezaehlt=%d abweichungen=%d", id, stufe, zaehler.dateien(), zaehler.versionen(),
                zaehler.manifeste(), zaehler.verlaengert(), zaehler.nurGezaehlt(), zaehler.abweichungen());
        if (zaehler.abweichungen() > 0 && stufe != Stufe.P) {
            benachrichtigung.prueflaufAbweichungen(mandant, id, zaehler,
                    speichern.abweichungen(id, ABWEICHUNGEN_IN_MAIL));
        }
    }

    private static boolean unbefristet(Sperrvorgabe vorgabe) {
        return vorgabe.gesetzlichePflicht() && vorgabe.aufbewahrenBis() == null && !vorgabe.fristAbgelaufen();
    }

    /** Dokument-ID aus {@code archiv/<jahr>/<dokument>/…}; leer, wenn der Schlüssel nicht so aufgebaut ist. */
    static Optional<UUID> dokumentId(String schluessel) {
        String[] teile = schluessel.split("/", 4);
        if (teile.length < 4 || !teile[0].equals("archiv")) {
            return Optional.empty();
        }
        try {
            UUID id = UUID.fromString(teile[2]);
            return id.toString().equals(teile[2]) ? Optional.of(id) : Optional.empty();
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private static void unterbrochen() throws InterruptedException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedException("Prüflauf unterbrochen");
        }
    }

    private record Ergebnis(Fortsetzung weiter, Zaehler zaehler, List<Abweichung> abweichungen) {
    }

    private static final class Sammler {
        private final UUID lauf;
        private final int teil;
        private final boolean letzterVersuch;
        private int fehlerInFolge;
        private boolean gestoert;
        private int dateien;
        private int versionen;
        private int manifeste;
        private int verlaengert;
        private int nurGezaehlt;
        private final List<Abweichung> abweichungen = new ArrayList<>();

        Sammler(UUID lauf, int teil, boolean letzterVersuch) {
            this.lauf = lauf;
            this.teil = teil;
            this.letzterVersuch = letzterVersuch;
        }

        void abweichung(Art art, Optional<UUID> dokument, String schluessel, Optional<String> version) {
            abweichungen.add(new Abweichung(art, dokument, schluessel, version));
        }

        Ergebnis ergebnis(Fortsetzung weiter) {
            return new Ergebnis(weiter, new Zaehler(dateien, versionen, manifeste, verlaengert, nurGezaehlt,
                    abweichungen.size()), List.copyOf(abweichungen));
        }
    }
}
