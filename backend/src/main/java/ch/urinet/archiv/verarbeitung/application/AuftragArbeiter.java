package ch.urinet.archiv.verarbeitung.application;

import ch.urinet.archiv.gemeinsam.domain.Ursachen;
import ch.urinet.archiv.mandant.application.MandantKontext;
import ch.urinet.archiv.verarbeitung.domain.AuftragFehler;
import ch.urinet.archiv.verarbeitung.domain.Nachbearbeitung;
import ch.urinet.archiv.verarbeitung.domain.Wiederholung;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Der Worker (Req 1.6, 5.4, 5.6, 5.7; design.md «Verarbeitung»): holt fällige Aufträge für die freien Plätze
 * ({@code archiv.worker.parallel}) und führt jeden in einem frischen Thread ohne Request-Kontext und ohne Transaktion
 * im Kontext seines Mandanten aus ({@link MandantKontext#mit}). Ein Überwachungs-Thread je Auftrag schreibt alle
 * {@code archiv.auftrag.lebenszeichen} ein Lebenszeichen und unterbricht den Handler beim Ablauf der Zeitgrenze.
 * <p>
 * Solange der Handler-Thread läuft, bleibt der Auftrag {@code LAEUFT} mit Lebenszeichen und sein Platz belegt; erst
 * wenn er wirklich endet, speichert der Worker Abschluss oder Fehlversuch und gibt den Platz frei. Es laufen nie mehr
 * Handler und Hooks als Plätze (Speicherbudget Stufe A). Endet ein abgebrochener Handler auch nach
 * {@value #HAENGEND_NACH_ZEITGRENZEN} Zeitgrenzen nicht, gilt er als hängend: Warnung mit Fehler-ID, keine Lebenszeichen
 * mehr, der Platz bleibt belegt, und der Auftrag bleibt {@code LAEUFT}, bis der Aufräumer ihn freigibt; der Worker
 * selbst speichert keinen Versuch.
 * <p>
 * Keine ID läuft in dieser JVM doppelt: Der Worker führt die IDs, deren Handler- oder Hook-Thread noch lebt. Holt er
 * eine solche ID erneut (der Aufräumer hat einen hängenden Auftrag freigegeben), startet er sie nicht, sondern belässt
 * sie ohne Lebenszeichen auf {@code LAEUFT} («geparkt», ohne Platz), bis der Thread endet, und gibt sie dann ohne
 * Versuch frei. Den Hook nach dem letzten Fehlversuch startet er für eine solche ID nicht. Über JVMs hinweg kann ein
 * hängender Handler frühestens nach rund 3 × Zeitgrenze + Wartezeit (bis ein Aufräumintervall später) neben dem
 * nächsten Versuch laufen; darum schreiben Handler nur unter {@link AuftragService#anspruchSichern}.
 * <p>
 * Fehler landen ohne Werte im Auftrag und im Log: nur Art, Fehler-ID und die Klassennamen der Ursachen (Req 16.7).
 */
@ApplicationScoped
public class AuftragArbeiter {

    static final String FEHLGESCHLAGEN = "fehlgeschlagen";
    static final String ZEITGRENZE = "zeitgrenze";
    static final String VERWAIST = "verwaist";
    static final int HAENGEND_NACH_ZEITGRENZEN = 2;

    private static final Logger LOG = Logger.getLogger(AuftragArbeiter.class);

    private final AuftragSchlange schlange;
    private final AuftragHandlerVerzeichnis verzeichnis;
    private final MandantKontext kontext;
    private final Duration lebenszeichen;
    private final Duration stoppFrist;
    private final Semaphore plaetze;
    private final AtomicBoolean herunterfahren = new AtomicBoolean();
    private final ExecutorService ueberwachung = Executors.newThreadPerTaskExecutor(
            Thread.ofPlatform().name("auftrag-", 0).daemon().factory());
    private final ExecutorService ausfuehrung = Executors.newThreadPerTaskExecutor(
            Thread.ofPlatform().name("auftrag-handler-", 0).daemon().factory());
    private final Object belegung = new Object();
    /** IDs, deren Handler- oder Hook-Thread in dieser JVM noch lebt (geschützt durch {@link #belegung}). */
    private final Set<Long> aktiv = new HashSet<>();
    /** Wieder geholte Ansprüche auf aktive IDs, die erst nach dem Ende des Threads freigegeben werden. */
    private final Map<Long, Auftrag> geparkt = new HashMap<>();

    @Inject
    public AuftragArbeiter(AuftragSchlange schlange, AuftragHandlerVerzeichnis verzeichnis, MandantKontext kontext,
                           @ConfigProperty(name = "archiv.worker.parallel") int parallel,
                           @ConfigProperty(name = "archiv.auftrag.lebenszeichen") Duration lebenszeichen,
                           @ConfigProperty(name = "archiv.worker.stopp-frist") Duration stoppFrist) {
        if (parallel < 1) {
            throw new IllegalArgumentException("archiv.worker.parallel muss mindestens 1 sein");
        }
        if (lebenszeichen.isNegative() || lebenszeichen.isZero()) {
            throw new IllegalArgumentException("archiv.auftrag.lebenszeichen muss positiv sein");
        }
        if (stoppFrist.isNegative()) {
            throw new IllegalArgumentException("archiv.worker.stopp-frist darf nicht negativ sein");
        }
        this.schlange = schlange;
        this.verzeichnis = verzeichnis;
        this.kontext = kontext;
        this.lebenszeichen = lebenszeichen;
        this.stoppFrist = stoppFrist;
        this.plaetze = new Semaphore(parallel);
    }

    /**
     * Holt so viele Aufträge, wie Plätze frei sind, und startet sie; jedes Ergebnis ist erfüllt, sobald der Auftrag
     * abgeschlossen ist und sein Platz wieder frei.
     */
    public List<CompletableFuture<Void>> abholen() {
        if (herunterfahren.get()) {
            return List.of();
        }
        int frei = plaetze.drainPermits();
        if (frei == 0) {
            return List.of();
        }
        List<Auftrag> geholt = List.of();
        try {
            if (!verzeichnis.typen().isEmpty()) {
                geholt = schlange.holen(verzeichnis.typen(), frei);
            }
        } finally {
            plaetze.release(frei - geholt.size());
        }
        List<CompletableFuture<Void>> laeufe = new ArrayList<>(geholt.size());
        for (Auftrag auftrag : geholt) {
            if (!belegenOderParken(auftrag)) {
                plaetze.release();
                LOG.warnf("Auftrag %d (%s): in dieser JVM läuft noch ein Thread für den Auftrag; nicht gestartet, "
                        + "bleibt LAEUFT, bis der Thread endet", auftrag.id(), auftrag.typ());
                continue;
            }
            try {
                laeufe.add(CompletableFuture.runAsync(() -> bearbeiten(auftrag), ueberwachung));
            } catch (RejectedExecutionException e) {
                // Herunterfahren zwischen Holen und Start: nie gelaufen, also ohne Versuch zurück
                idFreigeben(auftrag.id());
                plaetze.release();
                freigebenOhneVersuch(auftrag, "beim Herunterfahren");
            }
        }
        return laeufe;
    }

    /**
     * Geordneter Stopp (Herunterfahren): holt nichts mehr, unterbricht die laufenden Handler und wartet höchstens
     * {@code archiv.worker.stopp-frist} auf sie. Ein Auftrag, dessen Handler rechtzeitig endet, geht ohne Erhöhung von
     * {@code versuche} zurück auf {@code OFFEN} (ein Deploy kostet keinen Versuch); einer, dessen Handler nicht endet,
     * bleibt {@code LAEUFT}, bis der Aufräumer ihn freigibt. Geparkte Ansprüche gibt er erst frei, wenn der vorige
     * Thread endet; sonst bleiben sie {@code LAEUFT} bis zum Aufräumen.
     */
    public void herunterfahren() {
        if (!herunterfahren.compareAndSet(false, true)) {
            return;
        }
        ueberwachung.shutdownNow();
        try {
            if (!ueberwachung.awaitTermination(stoppFrist.toNanos() + lebenszeichen.toNanos(), TimeUnit.NANOSECONDS)) {
                LOG.warn("Herunterfahren: nicht alle Aufträge sind beendet, sie bleiben LAEUFT bis zum Aufräumen");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            ausfuehrung.shutdownNow();
        }
    }

    @PreDestroy
    void beenden() {
        herunterfahren();
    }

    private void bearbeiten(Auftrag auftrag) {
        Lauf lauf;
        try {
            lauf = starten(auftrag, () -> verzeichnis.handler(auftrag.typ()).ausfuehren(auftrag));
        } catch (RejectedExecutionException e) {
            idFreigeben(auftrag.id());
            plaetze.release();
            freigebenOhneVersuch(auftrag, "beim Herunterfahren");
            return;
        }
        try {
            ueberwachen(auftrag, lauf);
        } catch (RuntimeException e) {
            // z. B. Abschluss oder Fehlversuch nicht gespeichert: der Auftrag bleibt LAEUFT, der Aufräumer gibt frei
            LOG.errorf("Auftrag %d (%s): Fehler %s, Ergebnis nicht gespeichert, ursache=%s", auftrag.id(),
                    auftrag.typ(), UUID.randomUUID(), Ursachen.kette(e));
        } finally {
            // ID und Platz werden erst frei, wenn der Handler-Thread wirklich beendet ist
            bisBeendet(lauf);
            idFreigeben(auftrag.id());
            plaetze.release();
        }
    }

    /** Startet die Arbeit in einem eigenen Thread ohne Request-Kontext, im Kontext des Mandanten. */
    private Lauf starten(Auftrag auftrag, Arbeit arbeit) {
        Lauf lauf = new Lauf();
        lauf.future = ausfuehrung.submit(() -> {
            try {
                kontext.mit(auftrag.mandant(), () -> {
                    try {
                        arbeit.tun();
                    } catch (RuntimeException e) {
                        throw e;
                    } catch (Exception e) {
                        throw new HandlerFehler(e);
                    }
                });
            } catch (HandlerFehler e) {
                lauf.fehler = e.getCause();
            } catch (RuntimeException | Error e) {
                lauf.fehler = e;
            } finally {
                lauf.beendet.countDown();
            }
        });
        return lauf;
    }

    /** Belegt die ID für einen Handler; läuft in dieser JVM noch ein Thread für sie, wird der Anspruch geparkt. */
    private boolean belegenOderParken(Auftrag auftrag) {
        synchronized (belegung) {
            if (aktiv.add(auftrag.id())) {
                return true;
            }
            geparkt.put(auftrag.id(), auftrag);
            return false;
        }
    }

    /** Gibt die ID frei, wenn ihr Thread beendet ist, und danach einen geparkten Anspruch ohne Versuch. */
    private void idFreigeben(long id) {
        Auftrag wartend;
        synchronized (belegung) {
            aktiv.remove(id);
            wartend = geparkt.remove(id);
        }
        if (wartend != null) {
            freigebenOhneVersuch(wartend, "nach dem Ende des vorigen Threads");
        }
    }

    private void ueberwachen(Auftrag auftrag, Lauf lauf) {
        long zeitgrenze = verzeichnis.zeitgrenze(auftrag.typ()).toNanos();
        long start = System.nanoTime();
        long frist = start + zeitgrenze;
        long haengend = start + HAENGEND_NACH_ZEITGRENZEN * zeitgrenze;
        Abbruch abbruch = Abbruch.KEINER;
        try {
            while (true) {
                long bis = abbruch == Abbruch.KEINER ? frist : haengend;
                long warten = Math.min(Math.max(bis - System.nanoTime(), 0), lebenszeichen.toNanos());
                if (lauf.beendet.await(warten, TimeUnit.NANOSECONDS)) {
                    break;
                }
                long jetzt = System.nanoTime();
                if (abbruch == Abbruch.KEINER && jetzt - frist >= 0) {
                    abbruch = Abbruch.ZEITGRENZE;
                    lauf.future.cancel(true);
                } else if (abbruch != Abbruch.KEINER && jetzt - haengend >= 0) {
                    LOG.errorf("Auftrag %d (%s, Mandant %s): Fehler %s, Handler endet seit dem Abbruch nicht und gilt "
                                    + "als hängend; Platz bleibt belegt, Auftrag bleibt LAEUFT bis zum Aufräumen",
                            auftrag.id(), auftrag.typ(), auftrag.mandant().wert(), UUID.randomUUID());
                    return;
                }
                if (abbruch != Abbruch.ANSPRUCH_VERLOREN && !lebenszeichenSchreiben(auftrag)) {
                    LOG.warnf("Auftrag %d (%s): Anspruch verloren (freigegeben), Ausführung abgebrochen; Platz bleibt "
                            + "belegt, bis der Handler endet", auftrag.id(), auftrag.typ());
                    abbruch = Abbruch.ANSPRUCH_VERLOREN;
                    lauf.future.cancel(true);
                }
            }
        } catch (InterruptedException e) {
            beimStopp(auftrag, lauf, abbruch);
            Thread.currentThread().interrupt();
            return;
        }
        abgeschlossen(auftrag, lauf, abbruch);
    }

    /** Der Handler-Thread ist beendet: Ergebnis speichern. */
    private void abgeschlossen(Auftrag auftrag, Lauf lauf, Abbruch abbruch) {
        switch (abbruch) {
            case ANSPRUCH_VERLOREN -> {
                // ein anderer hat den Auftrag; nichts mehr schreiben
            }
            case ZEITGRENZE -> fehlversuch(auftrag, ZEITGRENZE, null);
            case KEINER -> {
                if (lauf.fehler == null) {
                    abschliessen(auftrag);
                } else {
                    fehlversuch(auftrag, FEHLGESCHLAGEN, lauf.fehler);
                }
            }
        }
    }

    /** Herunterfahren: Handler unterbrechen und kurz abwarten; ein Abbruch durch den Stopp kostet keinen Versuch. */
    private void beimStopp(Auftrag auftrag, Lauf lauf, Abbruch abbruch) {
        lauf.future.cancel(true);
        if (!warten(lauf, stoppFrist)) {
            LOG.warnf("Auftrag %d (%s): Handler endet beim Herunterfahren nicht, Auftrag bleibt LAEUFT bis zum "
                    + "Aufräumen", auftrag.id(), auftrag.typ());
            return;
        }
        if (abbruch != Abbruch.KEINER || lauf.fehler == null) {
            abgeschlossen(auftrag, lauf, abbruch);
            return;
        }
        freigebenOhneVersuch(auftrag, "beim Herunterfahren");
    }

    private void freigebenOhneVersuch(Auftrag auftrag, String wann) {
        try {
            if (schlange.freigeben(auftrag)) {
                LOG.infof("Auftrag %d (%s): %s ohne Versuch freigegeben", auftrag.id(), auftrag.typ(), wann);
            }
        } catch (RuntimeException e) {
            LOG.warnf("Auftrag %d (%s): %s nicht freigegeben, bleibt LAEUFT bis zum Aufräumen, ursache=%s",
                    auftrag.id(), auftrag.typ(), wann, Ursachen.kette(e));
        }
    }

    /**
     * Speichert den Abschluss; scheitert das, einmal nach einem Lebenszeichen-Takt erneut. Bleibt es dabei, bleibt der
     * Auftrag {@code LAEUFT} und läuft nach dem Aufräumen nochmals (Handler sind idempotent).
     */
    private void abschliessen(Auftrag auftrag) {
        RuntimeException erster;
        try {
            abschlussSpeichern(auftrag);
            return;
        } catch (RuntimeException e) {
            erster = e;
        }
        try {
            Thread.sleep(lebenszeichen);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        try {
            abschlussSpeichern(auftrag);
        } catch (RuntimeException e) {
            LOG.errorf("Auftrag %d (%s, Mandant %s): Fehler %s, Abschluss auch im zweiten Versuch nicht gespeichert; "
                            + "der Auftrag bleibt LAEUFT und läuft nach dem Aufräumen erneut, ursache=%s / %s",
                    auftrag.id(), auftrag.typ(), auftrag.mandant().wert(), UUID.randomUUID(), Ursachen.kette(erster),
                    Ursachen.kette(e));
        }
    }

    private void abschlussSpeichern(Auftrag auftrag) {
        if (!schlange.abschliessen(auftrag)) {
            LOG.warnf("Auftrag %d (%s): Anspruch verloren, Abschluss nicht gespeichert", auftrag.id(), auftrag.typ());
        }
    }

    private boolean lebenszeichenSchreiben(Auftrag auftrag) {
        try {
            return schlange.lebenszeichen(auftrag);
        } catch (RuntimeException e) {
            // Datenbank kurz weg: weiterarbeiten; bleibt das Lebenszeichen zu lange aus, gibt der Aufräumer frei
            LOG.warnf("Auftrag %d (%s): Lebenszeichen nicht gespeichert, ursache=%s", auftrag.id(), auftrag.typ(),
                    Ursachen.kette(e));
            return true;
        }
    }

    private void fehlversuch(Auftrag auftrag, String art, Throwable ursache) {
        AuftragFehler fehler = AuftragFehler.neu(art);
        int versuch = auftrag.versuche() + 1;
        Optional<Duration> wartezeit = Wiederholung.nachFehlversuch(versuch);
        String ursachen = ursache == null ? "-" : Ursachen.kette(ursache);
        if (!schlange.fehlversuch(auftrag, fehler.text(), wartezeit)) {
            LOG.warnf("Auftrag %d (%s): Fehler %s (%s) nicht gespeichert, Anspruch verloren, ursache=%s",
                    auftrag.id(), auftrag.typ(), fehler.fehlerId(), art, ursachen);
            return;
        }
        if (wartezeit.isPresent()) {
            LOG.warnf("Auftrag %d (%s, Mandant %s): Versuch %d von %d %s, Fehler %s, nächster Versuch in %s, "
                            + "ursache=%s", auftrag.id(), auftrag.typ(), auftrag.mandant().wert(), versuch,
                    Wiederholung.MAX_VERSUCHE, art, fehler.fehlerId(), wartezeit.get(), ursachen);
            return;
        }
        LOG.errorf("Auftrag %d (%s, Mandant %s): Versuch %d von %d %s, Fehler %s, Status FEHLER, ursache=%s",
                auftrag.id(), auftrag.typ(), auftrag.mandant().wert(), versuch, Wiederholung.MAX_VERSUCHE, art,
                fehler.fehlerId(), ursachen);
        nachbearbeitenImPlatz(auftrag, fehler.fehlerId());
    }

    /**
     * Hook nach dem letzten Fehlversuch im Platz und unter der ID des eben beendeten Handlers: Der Platz bleibt belegt,
     * bis auch der Hook-Thread endet.
     */
    private void nachbearbeitenImPlatz(Auftrag auftrag, UUID fehlerId) {
        Lauf hook;
        try {
            hook = starten(auftrag, () -> verzeichnis.handler(auftrag.typ()).nachLetztemFehlversuch(auftrag, fehlerId));
        } catch (RejectedExecutionException e) {
            LOG.warnf("Auftrag %d (%s): Nachbearbeitung nach Fehler %s beim Herunterfahren nicht gestartet, folgt "
                    + "beim Aufräumen", auftrag.id(), auftrag.typ(), fehlerId);
            return;
        }
        try {
            hookAuswerten(auftrag, fehlerId, 0, hook);
        } finally {
            hookBeenden(auftrag, hook);
        }
    }

    /**
     * Holt den Hook nach dem letzten Fehlversuch nach (Aufräumer): wie ein Handler auf einem freien Platz und nur, wenn
     * in dieser JVM kein Thread für den Auftrag mehr läuft; sonst ohne Zählung beim nächsten Aufräumen. Wartet
     * höchstens bis zur Zeitgrenze des Typs; Platz und ID bleiben belegt, bis der Hook-Thread endet. Scheitert der
     * Hook, folgt er mit Wartezeit erneut, nach {@link Nachbearbeitung#MAX_VERSUCHE} Fehlschlägen nicht mehr.
     *
     * @param bisher bisher gescheiterte Nachbearbeitungen ({@code nachbearbeitung_versuche})
     * @return {@code true}, wenn der Hook erfolgreich war
     */
    public boolean nachbearbeiten(Auftrag auftrag, UUID fehlerId, int bisher) {
        if (herunterfahren.get() || !plaetze.tryAcquire()) {
            LOG.infof("Auftrag %d (%s): kein Platz frei, Nachbearbeitung folgt beim nächsten Aufräumen", auftrag.id(),
                    auftrag.typ());
            return false;
        }
        boolean belegt;
        synchronized (belegung) {
            belegt = aktiv.add(auftrag.id());
        }
        if (!belegt) {
            plaetze.release();
            LOG.infof("Auftrag %d (%s): in dieser JVM läuft noch ein Thread für den Auftrag, Nachbearbeitung folgt "
                    + "beim nächsten Aufräumen", auftrag.id(), auftrag.typ());
            return false;
        }
        Lauf hook;
        try {
            hook = starten(auftrag, () -> verzeichnis.handler(auftrag.typ()).nachLetztemFehlversuch(auftrag, fehlerId));
        } catch (RejectedExecutionException e) {
            idFreigeben(auftrag.id());
            plaetze.release();
            return false;
        }
        try {
            return hookAuswerten(auftrag, fehlerId, bisher, hook);
        } finally {
            freigebenNachHook(auftrag, hook);
        }
    }

    /** Wartet höchstens bis zur Zeitgrenze auf den Hook und speichert Erfolg oder Fehlschlag. */
    private boolean hookAuswerten(Auftrag auftrag, UUID fehlerId, int bisher, Lauf hook) {
        Throwable fehler;
        try {
            if (hook.beendet.await(verzeichnis.zeitgrenze(auftrag.typ()).toNanos(), TimeUnit.NANOSECONDS)) {
                fehler = hook.fehler;
            } else {
                hook.future.cancel(true);
                fehler = new TimeoutException();
            }
        } catch (InterruptedException e) {
            // Herunterfahren: kein Fehlschlag des Hooks, zählt nicht
            hook.future.cancel(true);
            Thread.currentThread().interrupt();
            LOG.warnf("Auftrag %d (%s): Nachbearbeitung nach Fehler %s beim Herunterfahren abgebrochen, folgt beim "
                    + "Aufräumen", auftrag.id(), auftrag.typ(), fehlerId);
            return false;
        }
        try {
            if (fehler == null) {
                if (!schlange.nachbearbeitet(auftrag)) {
                    LOG.warnf("Auftrag %d (%s): Nachbearbeitung schon vermerkt", auftrag.id(), auftrag.typ());
                }
                return true;
            }
            gescheitert(auftrag, fehlerId, bisher, fehler);
        } catch (RuntimeException e) {
            LOG.errorf("Auftrag %d (%s): Fehler %s, Ergebnis der Nachbearbeitung nicht gespeichert, folgt beim "
                    + "Aufräumen, ursache=%s", auftrag.id(), auftrag.typ(), UUID.randomUUID(), Ursachen.kette(e));
        }
        return false;
    }

    private void gescheitert(Auftrag auftrag, UUID fehlerId, int bisher, Throwable ursache) {
        int n = bisher + 1;
        Optional<Duration> wartezeit = Nachbearbeitung.nachFehlschlag(n);
        boolean gespeichert = schlange.nachbearbeitungGescheitert(auftrag, bisher, wartezeit);
        LOG.errorf("Auftrag %d (%s): Fehler %s nach dem letzten Fehlversuch (Fehler %s), Nachbearbeitung %d von %d "
                        + "gescheitert, %s, ursache=%s", auftrag.id(), auftrag.typ(), UUID.randomUUID(), fehlerId, n,
                Nachbearbeitung.MAX_VERSUCHE, wartezeit.map(w -> "Nachbearbeitung folgt beim Aufräumen, frühestens in "
                        + w).orElse("keine weitere Nachbearbeitung"), Ursachen.kette(ursache));
        if (wartezeit.isEmpty() && gespeichert) {
            LOG.errorf("Auftrag %d (%s, Mandant %s): Fehler %s, Nachbearbeitung nach %d Versuchen aufgegeben; der "
                            + "Hook läuft nicht mehr, bitte von Hand prüfen", auftrag.id(), auftrag.typ(),
                    auftrag.mandant().wert(), UUID.randomUUID(), Nachbearbeitung.MAX_VERSUCHE);
        }
    }

    /** Aufräumer: ID und Platz frei, sobald der Hook-Thread endet, ohne den Aufräumer auf einen hängenden zu warten. */
    private void freigebenNachHook(Auftrag auftrag, Lauf hook) {
        if (hook.beendet.getCount() == 0) {
            idFreigeben(auftrag.id());
            plaetze.release();
            return;
        }
        try {
            ueberwachung.execute(() -> {
                try {
                    hookBeenden(auftrag, hook);
                } finally {
                    idFreigeben(auftrag.id());
                    plaetze.release();
                }
            });
        } catch (RejectedExecutionException e) {
            // Herunterfahren: Platz und ID bleiben belegt, solange der Hook-Thread lebt
        }
    }

    /** Wartet, bis der Hook-Thread endet; endet er auch nach einer weiteren Zeitgrenze nicht, gilt er als hängend. */
    private void hookBeenden(Auftrag auftrag, Lauf hook) {
        if (!warten(hook, verzeichnis.zeitgrenze(auftrag.typ())) && !Thread.currentThread().isInterrupted()) {
            LOG.errorf("Auftrag %d (%s): Fehler %s, Nachbearbeitung endet seit dem Abbruch nicht und gilt als "
                            + "hängend; Platz bleibt belegt", auftrag.id(), auftrag.typ(), UUID.randomUUID());
        }
        bisBeendet(hook);
    }

    private boolean warten(Lauf lauf, Duration hoechstens) {
        try {
            return lauf.beendet.await(hoechstens.toNanos(), TimeUnit.NANOSECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return lauf.beendet.getCount() == 0;
        }
    }

    /** Wartet ohne Unterbrechung, bis der Handler-Thread endet; bei einem hängenden Handler unbegrenzt. */
    private static void bisBeendet(Lauf lauf) {
        boolean unterbrochen = false;
        while (true) {
            try {
                lauf.beendet.await();
                break;
            } catch (InterruptedException e) {
                unterbrochen = true;
            }
        }
        if (unterbrochen) {
            Thread.currentThread().interrupt();
        }
    }

    private enum Abbruch { KEINER, ZEITGRENZE, ANSPRUCH_VERLOREN }

    /** Arbeit eines Handler- oder Hook-Threads. */
    @FunctionalInterface
    private interface Arbeit {
        void tun() throws Exception;
    }

    /** Zustand eines Handler- oder Hook-Laufs; {@code fehler} ist {@code null}, wenn er normal endete. */
    private static final class Lauf {
        final CountDownLatch beendet = new CountDownLatch(1);
        volatile Throwable fehler;
        volatile Future<?> future;
    }

    /** Trägt eine geprüfte Ausnahme des Handlers aus dem Mandanten-Kontext heraus. */
    private static final class HandlerFehler extends RuntimeException {
        HandlerFehler(Exception ursache) {
            // ohne Meldung: die der Ursache könnte Werte enthalten
            super(null, ursache, false, false);
        }
    }
}
