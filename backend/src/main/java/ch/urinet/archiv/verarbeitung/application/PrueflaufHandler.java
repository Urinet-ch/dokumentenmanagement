package ch.urinet.archiv.verarbeitung.application;

import ch.urinet.archiv.ablage.application.AblagePort;
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
import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.verarbeitung.application.PrueflaufRegister.Abweichung;
import ch.urinet.archiv.verarbeitung.application.PrueflaufRegister.Art;
import ch.urinet.archiv.verarbeitung.application.PrueflaufRegister.Fortsetzung;
import ch.urinet.archiv.verarbeitung.application.PrueflaufRegister.Lauf;
import ch.urinet.archiv.verarbeitung.application.PrueflaufRegister.Phase;
import ch.urinet.archiv.verarbeitung.application.PrueflaufRegister.Zaehler;
import ch.urinet.archiv.verarbeitung.domain.AuftragTyp;
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
 * In Stufe P wird keine Sperre verlängert, nur gezählt (Req 16.24), und keine E-Mail versandt. Eine fehlende verlangte
 * Sperre ist nur in Stufe B eine Abweichung; in den Stufen A und P laufen Sperren nach einem Tag ab (Req 6.11). Am Ende
 * Bericht im Log und in {@code prueflauf}; bei Abweichungen E-Mail an den Inhaber, nur mit IDs (Req 16.7).
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
    private final Optional<Duration> archivHoechstfrist;
    private final Optional<Duration> manifestHoechstfrist;
    private final int dokumenteJeTeil;
    private final int versionenJeTeil;

    @Inject
    public PrueflaufHandler(PrueflaufSpeichern speichern, AblagePort ablage, InhaberBenachrichtigung benachrichtigung,
                            @ConfigProperty(name = "archiv.ablage.max-sperrfrist") Optional<Duration> archivHoechstfrist,
                            @ConfigProperty(name = "archiv.ablage.manifest-max-sperrfrist")
                            Optional<Duration> manifestHoechstfrist,
                            @ConfigProperty(name = "archiv.prueflauf.dokumente-je-teil", defaultValue = "100")
                            int dokumenteJeTeil,
                            @ConfigProperty(name = "archiv.prueflauf.versionen-je-teil", defaultValue = "500")
                            int versionenJeTeil) {
        this(speichern, ablage, benachrichtigung, Clock.systemUTC(), archivHoechstfrist, manifestHoechstfrist,
                dokumenteJeTeil, versionenJeTeil);
    }

    PrueflaufHandler(PrueflaufSpeichern speichern, AblagePort ablage, InhaberBenachrichtigung benachrichtigung,
                     Clock uhr, Optional<Duration> archivHoechstfrist, Optional<Duration> manifestHoechstfrist,
                     int dokumenteJeTeil, int versionenJeTeil) {
        if (dokumenteJeTeil < 1 || versionenJeTeil < 1 || versionenJeTeil > 1000) {
            throw new IllegalStateException("archiv.prueflauf.dokumente-je-teil muss ≥ 1, "
                    + "archiv.prueflauf.versionen-je-teil zwischen 1 und 1000 liegen");
        }
        this.speichern = speichern;
        this.ablage = ablage;
        this.benachrichtigung = benachrichtigung;
        this.uhr = uhr;
        this.archivHoechstfrist = archivHoechstfrist;
        this.manifestHoechstfrist = manifestHoechstfrist;
        this.dokumenteJeTeil = dokumenteJeTeil;
        this.versionenJeTeil = versionenJeTeil;
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
        Ergebnis ergebnis = switch (lauf.stand().phase()) {
            case DATEIEN -> dateien(auftrag.mandant(), lauf);
            case ARCHIV_VERSIONEN -> archivVersionen(auftrag.mandant(), lauf);
            case MANIFEST_VERSIONEN -> manifeste(auftrag.mandant(), lauf);
            case ABGESCHLOSSEN -> throw new IllegalStateException("unerreichbar");
        };
        if (!speichern.fortschreiben(auftrag, id, teil, ergebnis.weiter(), ergebnis.zaehler(),
                ergebnis.abweichungen())) {
            LOG.infof("Prüflauf %s Teil %d schon festgehalten", id, teil);
            return;
        }
        if (ergebnis.weiter().phase() == Phase.ABGESCHLOSSEN) {
            abschliessen(auftrag.mandant(), id, lauf.stufe(), lauf.zaehler().plus(ergebnis.zaehler()));
        }
    }

    private Ergebnis dateien(MandantCode mandant, Lauf lauf) throws InterruptedException {
        List<Pruefdokument> seite = speichern.archivierte(lauf.stand().nachDokument(), dokumenteJeTeil);
        Sammler s = new Sammler();
        for (Pruefdokument dokument : seite) {
            for (Datei datei : dokument.dateien()) {
                unterbrochen();
                s.dateien++;
                pruefeDatei(mandant, lauf.stufe(), dokument, datei, s);
            }
        }
        Fortsetzung weiter = seite.size() < dokumenteJeTeil
                ? Fortsetzung.beginn(Phase.ARCHIV_VERSIONEN)
                : new Fortsetzung(Phase.DATEIEN, Optional.of(seite.getLast().id()), Optional.empty());
        return s.ergebnis(weiter);
    }

    private void pruefeDatei(MandantCode mandant, Stufe stufe, Pruefdokument dokument, Datei datei, Sammler s) {
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
            pflegen(mandant, stufe, datei.schluessel(), version, bisher, archivHoechstfrist, s);
        }
    }

    private Ergebnis archivVersionen(MandantCode mandant, Lauf lauf) {
        Versionsseite seite = ablage.versionen(mandant, ARCHIV, lauf.stand().marke(), versionenJeTeil);
        Sammler s = new Sammler();
        Set<String> schluessel = new HashSet<>();
        seite.versionen().forEach(v -> schluessel.add(v.schluessel()));
        Set<Bekannt> bekannt = speichern.bekannte(schluessel);
        List<Objektversion> fremd = new ArrayList<>();
        for (Objektversion v : seite.versionen()) {
            s.versionen++;
            if (v.loeschmarke()) {
                s.abweichung(Art.LOESCHMARKE, dokumentId(v.schluessel()), v.schluessel(), Optional.of(v.versionId()));
            } else if (!bekannt.contains(new Bekannt(v.schluessel(), v.versionId()))) {
                fremd.add(v);
            }
        }
        Set<UUID> ids = new HashSet<>();
        fremd.forEach(v -> dokumentId(v.schluessel()).ifPresent(ids::add));
        Map<UUID, DokumentStatus> status = speichern.status(ids);
        for (Objektversion v : fremd) {
            Optional<UUID> dokument = dokumentId(v.schluessel());
            // während ARCHIVIERUNG stehen die Versions-IDs noch nicht in der Datenbank (design.md «Ablage»)
            if (dokument.map(status::get).filter(st -> st == DokumentStatus.ARCHIVIERUNG).isEmpty()) {
                s.abweichung(Art.FREMDE_VERSION, dokument, v.schluessel(), Optional.of(v.versionId()));
            }
        }
        Fortsetzung weiter = seite.weiter().isEmpty()
                ? Fortsetzung.beginn(Phase.MANIFEST_VERSIONEN)
                : new Fortsetzung(Phase.ARCHIV_VERSIONEN, Optional.empty(), seite.weiter());
        return s.ergebnis(weiter);
    }

    private Ergebnis manifeste(MandantCode mandant, Lauf lauf) throws InterruptedException {
        Versionsseite seite = ablage.versionen(mandant, MANIFEST, lauf.stand().marke(), versionenJeTeil);
        Sammler s = new Sammler();
        Instant jetzt = uhr.instant();
        for (Objektversion v : seite.versionen()) {
            unterbrochen();
            if (v.loeschmarke()) {
                s.abweichung(Art.LOESCHMARKE, Optional.empty(), v.schluessel(), Optional.of(v.versionId()));
                continue;
            }
            s.manifeste++;
            Optional<Dateipruefung> pruefung = ablage.pruefen(mandant, v.schluessel(), v.versionId());
            if (pruefung.isEmpty()) {
                s.abweichung(Art.VERSION_FEHLT, Optional.empty(), v.schluessel(), Optional.of(v.versionId()));
                continue;
            }
            Optional<Instant> bisher = pruefung.get().sperreBis();
            if (lauf.stufe() == Stufe.B && bisher.filter(bis -> bis.isAfter(jetzt)).isEmpty()) {
                s.abweichung(Art.SPERRE_FEHLT, Optional.empty(), v.schluessel(), Optional.of(v.versionId()));
            }
            pflegen(mandant, lauf.stufe(), v.schluessel(), v.versionId(), bisher, manifestHoechstfrist, s);
        }
        Fortsetzung weiter = seite.weiter().isEmpty()
                ? Fortsetzung.beginn(Phase.ABGESCHLOSSEN)
                : new Fortsetzung(Phase.MANIFEST_VERSIONEN, Optional.empty(), seite.weiter());
        return s.ergebnis(weiter);
    }

    private void pflegen(MandantCode mandant, Stufe stufe, String schluessel, String version,
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

    private void abschliessen(MandantCode mandant, UUID id, Stufe stufe, Zaehler zaehler) {
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
        private int dateien;
        private int versionen;
        private int manifeste;
        private int verlaengert;
        private int nurGezaehlt;
        private final List<Abweichung> abweichungen = new ArrayList<>();

        void abweichung(Art art, Optional<UUID> dokument, String schluessel, Optional<String> version) {
            abweichungen.add(new Abweichung(art, dokument, schluessel, version));
        }

        Ergebnis ergebnis(Fortsetzung weiter) {
            return new Ergebnis(weiter, new Zaehler(dateien, versionen, manifeste, verlaengert, nurGezaehlt,
                    abweichungen.size()), List.copyOf(abweichungen));
        }
    }
}
