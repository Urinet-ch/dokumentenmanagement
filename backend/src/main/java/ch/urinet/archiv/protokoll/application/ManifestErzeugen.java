package ch.urinet.archiv.protokoll.application;

import ch.urinet.archiv.ablage.application.AblagePort;
import ch.urinet.archiv.ablage.domain.AbgelegteDatei;
import ch.urinet.archiv.ablage.domain.ManifestDatei;
import ch.urinet.archiv.ablage.domain.ManifestTeil;
import ch.urinet.archiv.mandant.application.MandantRegister;
import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.protokoll.application.ManifestDaten.Blatt;
import ch.urinet.archiv.protokoll.application.ManifestDaten.Fristen;
import ch.urinet.archiv.protokoll.application.ManifestDaten.ManifestStand;
import ch.urinet.archiv.protokoll.application.ManifestDaten.NeuesManifest;
import ch.urinet.archiv.protokoll.application.ManifestDaten.Pruefdatei;
import ch.urinet.archiv.protokoll.application.ManifestDaten.Zeitstempel;
import ch.urinet.archiv.protokoll.domain.ManifestFenster;
import ch.urinet.archiv.protokoll.domain.ManifestInhalt;
import ch.urinet.archiv.protokoll.domain.ManifestSignatur;
import ch.urinet.archiv.protokoll.domain.ProtokollHash;
import ch.urinet.archiv.protokoll.domain.Pruefdaten;
import ch.urinet.archiv.protokoll.domain.Stempel;
import ch.urinet.archiv.protokoll.domain.Vorher;
import ch.urinet.archiv.protokoll.domain.ZeitstempelVerweis;
import ch.urinet.archiv.verarbeitung.application.Auftrag;
import ch.urinet.archiv.verarbeitung.application.AuftragHandler;
import ch.urinet.archiv.verarbeitung.application.InhaberBenachrichtigung;
import ch.urinet.archiv.verarbeitung.domain.AuftragTyp;
import ch.urinet.archiv.verarbeitung.domain.Nutzlast;
import ch.urinet.archiv.verarbeitung.domain.Wiederholung;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Handler für {@code MANIFEST} (Aufgabe 5.2, Req 15.1–15.4, 15.6–15.8, 15.11; design.md «Manifest»): erzeugt für jedes
 * fehlende Datum bis {@code bisTag} (höchstens bis zum letzten Tag, dessen Ende {@link #NACHLAUF} zurückliegt) in
 * Datumsreihenfolge ein Manifest, signiert es, stempelt den Hash bei allen Diensten parallel und legt alles gesperrt
 * unter {@code manifest/} ab.
 * <p>
 * Idempotent und ohne Lücke: Ein Datum wird erst festgehalten, wenn mindestens ein Stempel vorliegt; die Kette endet
 * beim ersten Datum ohne Manifest, der nächste Lauf setzt dort an. Ein schon abgelegtes {@code .tsr} wird
 * wiederverwendet (ein Stempel lässt sich nicht wiederholen, ohne dass ein anderes Token entsteht). Liegt unter einem
 * Pfad schon ein anderer Inhalt, bricht der Lauf ab (fail closed).
 */
@ApplicationScoped
public class ManifestErzeugen implements AuftragHandler {

    public static final AuftragTyp TYP = new AuftragTyp("MANIFEST");
    public static final String BIS_TAG = "bisTag";
    /** Abstand zum Ende eines Tages, damit spät festgeschriebene Zeilen nicht aus dem Fenster fallen. */
    public static final Duration NACHLAUF = Duration.ofMinutes(15);
    /**
     * Höchstens so viele Tage je Lauf; die übrigen holt ein Folgeauftrag. Je Tag schlimmstenfalls zwei Netzaufrufe zu
     * je {@code archiv.tsa.zeitgrenze} (30 s, Stempel und Prüfdaten, die Dienste parallel): 7 Tage bleiben unter der
     * Zeitgrenze von {@code MANIFEST} (15 min).
     */
    static final int MAX_TAGE_JE_LAUF = 7;

    private static final Logger LOG = Logger.getLogger(ManifestErzeugen.class);
    private static final HexFormat HEX = HexFormat.of();

    private final ManifestSpeichern speichern;
    private final ManifestDaten daten;
    private final AblagePort ablage;
    private final ZeitstempelPort zeitstempel;
    private final ManifestSchluessel schluessel;
    private final MandantRegister register;
    private final InhaberBenachrichtigung benachrichtigung;

    public ManifestErzeugen(ManifestSpeichern speichern, ManifestDaten daten, AblagePort ablage,
                            ZeitstempelPort zeitstempel, ManifestSchluessel schluessel, MandantRegister register,
                            InhaberBenachrichtigung benachrichtigung) {
        this.speichern = speichern;
        this.daten = daten;
        this.ablage = ablage;
        this.zeitstempel = zeitstempel;
        this.schluessel = schluessel;
        this.register = register;
        this.benachrichtigung = benachrichtigung;
    }

    @Override
    public AuftragTyp typ() {
        return TYP;
    }

    @Override
    public void ausfuehren(Auftrag auftrag) {
        Optional<ManifestDaten.Sperre> sperre = daten.sperren();
        if (sperre.isEmpty()) {
            LOG.infof("Auftrag %d: ein anderer Lauf erzeugt die Manifeste dieses Mandanten", auftrag.id());
            return;
        }
        try (ManifestDaten.Sperre gehalten = sperre.get()) {
            LocalDate ziel = zielTag(auftrag.nutzlast(), Instant.now());
            Optional<ManifestStand> stand = speichern.letztes();
            int erzeugt = 0;
            for (LocalDate datum = naechstes(auftrag.mandant(), stand); !datum.isAfter(ziel); datum = datum.plusDays(1)) {
                if (erzeugt == MAX_TAGE_JE_LAUF) {
                    long folge = speichern.folgeauftrag(auftrag, ziel);
                    LOG.infof("Auftrag %d: %d Manifeste erzeugt, die übrigen bis %s holt Auftrag %d", auftrag.id(),
                            erzeugt, ziel, folge);
                    return;
                }
                stand = Optional.of(erzeugen(auftrag, datum, stand));
                erzeugt++;
            }
        }
    }

    /** Req 15.8: Nach dem letzten Versuch fehlt das erste noch offene Manifest. */
    @Override
    public void nachLetztemFehlversuch(Auftrag auftrag, UUID fehlerId) {
        LocalDate ziel = zielTag(auftrag.nutzlast(), Instant.now());
        LocalDate fehlt = naechstes(auftrag.mandant(), speichern.letztes());
        if (!fehlt.isAfter(ziel)) {
            benachrichtigung.manifestFehlt(auftrag.mandant(), fehlt, fehlerId);
        }
    }

    /** Letzter Tag, der erzeugt wird: {@code bisTag}, höchstens der letzte Tag, dessen Ende {@link #NACHLAUF} zurückliegt. */
    static LocalDate zielTag(Nutzlast nutzlast, Instant jetzt) {
        LocalDate reif = ManifestFenster.tag(jetzt.minus(NACHLAUF)).minusDays(1);
        if (nutzlast.zahl(BIS_TAG).isEmpty()) {
            return reif;
        }
        LocalDate bisTag = LocalDate.ofEpochDay(nutzlast.zahl(BIS_TAG).getAsLong());
        return bisTag.isBefore(reif) ? bisTag : reif;
    }

    private LocalDate naechstes(MandantCode mandant, Optional<ManifestStand> stand) {
        if (stand.isPresent()) {
            return stand.get().datum().plusDays(1);
        }
        return ManifestFenster.tag(register.erstellt(mandant)
                .orElseThrow(() -> new IllegalStateException("Mandant fehlt im Register")));
    }

    /**
     * Sperre des Manifests (Req 15.4): spätestes {@code aufbewahren_bis}; mit einem unbefristeten Dokument mindestens
     * 10 Jahre (Req 6.9); ohne Dokument oder wenn alle Fristen vorbei sind, leer (10 Jahre).
     */
    static Optional<LocalDate> sperre(Fristen fristen, LocalDate heute) {
        if (fristen.dokumente() == 0) {
            return Optional.empty();
        }
        Optional<LocalDate> spaetestens = fristen.spaetestens().filter(d -> !d.isBefore(heute));
        if (fristen.unbefristet()) {
            LocalDate zehn = heute.plusYears(10);
            return Optional.of(spaetestens.filter(d -> d.isAfter(zehn)).orElse(zehn));
        }
        return spaetestens;
    }

    private ManifestStand erzeugen(Auftrag auftrag, LocalDate datum, Optional<ManifestStand> vorgaenger) {
        MandantCode mandant = auftrag.mandant();
        ManifestFenster fenster = ManifestFenster.fuer(datum, vorgaenger.map(ManifestStand::bis).orElse(null));
        ManifestSpeichern.Sammlung sammlung = speichern.sammeln(fenster);
        Map<String, ProtokollHash> protokolle = new TreeMap<>();
        sammlung.blaetter().forEach((tabelle, blaetter) -> protokolle.put(tabelle, ProtokollHash.aus(bytes(blaetter))));
        ManifestInhalt inhalt = new ManifestInhalt(mandant.wert(), datum, fenster.von(), fenster.bis(),
                vorgaenger.map(ManifestStand::sha256).orElse(null), vorgaenger.map(ManifestStand::verweis).orElse(null),
                sammlung.eintraege(), new TreeMap<>(protokolle), List.of());
        Optional<LocalDate> sperre = sperre(sammlung.fristen(), ManifestFenster.tag(Instant.now()));

        byte[] json = inhalt.kanonisch();
        AbgelegteDatei jsonAblage = ablage.ablegenManifest(mandant, datum, ManifestTeil.JSON, json, sperre);
        byte[] sig = ManifestSignatur.signieren(schluessel.privat(), json);
        AbgelegteDatei sigAblage = ablage.ablegenManifest(mandant, datum, ManifestTeil.SIG, sig, sperre);
        byte[] imprint = ManifestSignatur.imprint(json, sig);

        List<String> dienste = zeitstempel.dienste();
        if (dienste.isEmpty()) {
            throw new IllegalStateException("Keine Zeitstempeldienste konfiguriert (archiv.tsa.dienste)");
        }
        Map<String, Zeitstempel> gestempelt = new LinkedHashMap<>();
        List<String> fehlend = new ArrayList<>();
        try (ExecutorService ausfuehrung = Executors.newVirtualThreadPerTaskExecutor()) {
            Map<String, Future<Zeitstempel>> laeufe = new LinkedHashMap<>();
            for (String dienst : dienste) {
                laeufe.put(dienst, ausfuehrung.submit(() -> stempeln(mandant, datum, dienst, imprint, sperre)));
            }
            for (Map.Entry<String, Future<Zeitstempel>> lauf : laeufe.entrySet()) {
                try {
                    gestempelt.put(lauf.getKey(), lauf.getValue().get());
                } catch (ExecutionException e) {
                    fehlend.add(lauf.getKey());
                    LOG.warnf("Manifest %s, Dienst %s: kein Zeitstempel (%s)", datum, lauf.getKey(),
                            e.getCause().getClass().getSimpleName());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Manifest " + datum + ": unterbrochen", e);
                }
            }
        }
        if (gestempelt.isEmpty()) {
            throw new IllegalStateException("Manifest " + datum + ": kein Zeitstempel von " + fehlend);
        }
        if (!fehlend.isEmpty()) {
            if (auftrag.versuche() < Wiederholung.MAX_VERSUCHE - 1) {
                throw new IllegalStateException("Manifest " + datum + ": Zeitstempel von " + fehlend
                        + " fehlt, neuer Versuch");
            }
            LOG.warnf("Manifest %s gilt ohne Zeitstempel von %s (Req 15.11)", datum, fehlend);
        }

        speichern.festhalten(auftrag, new NeuesManifest(inhalt, jsonAblage, sigAblage,
                List.copyOf(gestempelt.values()), sammlung.blaetter()));
        LOG.infof("Manifest %s festgehalten (%d Einträge, %d Zeitstempel)", datum, inhalt.eintraege().size(),
                gestempelt.size());
        return new ManifestStand(datum, fenster.bis(), jsonAblage.sha256(),
                verweis(sigAblage.sha256(), List.copyOf(gestempelt.values())));
    }

    /** Verweis für {@code vorher} im Folgetag; Dienste nach Name, damit Lauf und Datenbank dasselbe ergeben. */
    public static Vorher verweis(String sigSha256, List<Zeitstempel> zeitstempel) {
        List<ZeitstempelVerweis> verweise = zeitstempel.stream()
                .sorted(java.util.Comparator.comparing(Zeitstempel::dienst))
                .map(z -> new ZeitstempelVerweis(z.dienst(), z.tsr().sha256(),
                        z.pruefdaten().stream().map(Pruefdatei::sha256).toList()))
                .toList();
        return new Vorher(sigSha256, verweise);
    }

    /** Je Dienst: {@code .tsr} zuerst (wiederverwendet, falls schon abgelegt), dann Kette und OCSP- bzw. CRL-Antwort. */
    private Zeitstempel stempeln(MandantCode mandant, LocalDate datum, String dienst, byte[] imprint,
                                 Optional<LocalDate> sperre) {
        Optional<ManifestDatei> vorhanden = ablage.lesenManifest(mandant, datum, ManifestTeil.tsr(dienst));
        Stempel stempel;
        AbgelegteDatei tsr;
        if (vorhanden.isPresent()) {
            stempel = zeitstempel.pruefen(dienst, vorhanden.get().inhalt(), imprint);
            tsr = vorhanden.get().datei();
        } else {
            stempel = zeitstempel.stempeln(dienst, imprint);
            tsr = ablage.ablegenManifest(mandant, datum, ManifestTeil.tsr(dienst), stempel.tsr(), sperre);
        }
        AbgelegteDatei kette = ablage.ablegenManifest(mandant, datum, ManifestTeil.kette(dienst),
                stempel.kettePem().getBytes(StandardCharsets.UTF_8), sperre);
        Pruefdatei pruefdatei = pruefdaten(mandant, datum, dienst, stempel, sperre);
        return new Zeitstempel(dienst, stempel.genTime(), tsr,
                List.of(new Pruefdatei("kette", kette.sha256(), kette.versionId()), pruefdatei));
    }

    private Pruefdatei pruefdaten(MandantCode mandant, LocalDate datum, String dienst, Stempel stempel,
                                  Optional<LocalDate> sperre) {
        for (Pruefdaten.Art art : Pruefdaten.Art.values()) {
            Optional<ManifestDatei> vorhanden = ablage.lesenManifest(mandant, datum, teil(dienst, art));
            if (vorhanden.isPresent()) {
                AbgelegteDatei d = vorhanden.get().datei();
                return new Pruefdatei(art.name().toLowerCase(java.util.Locale.ROOT), d.sha256(), d.versionId());
            }
        }
        Pruefdaten neu = zeitstempel.pruefdaten(dienst, stempel);
        AbgelegteDatei d = ablage.ablegenManifest(mandant, datum, teil(dienst, neu.art()), neu.inhalt(), sperre);
        return new Pruefdatei(neu.art().name().toLowerCase(java.util.Locale.ROOT), d.sha256(), d.versionId());
    }

    static ManifestTeil teil(String dienst, Pruefdaten.Art art) {
        return art == Pruefdaten.Art.OCSP ? ManifestTeil.ocsp(dienst) : ManifestTeil.crl(dienst);
    }

    private static byte[] bytes(List<Blatt> blaetter) {
        ByteArrayOutputStream aus = new ByteArrayOutputStream(blaetter.size() * 32);
        blaetter.forEach(b -> aus.writeBytes(HEX.parseHex(b.sha256())));
        return aus.toByteArray();
    }
}
