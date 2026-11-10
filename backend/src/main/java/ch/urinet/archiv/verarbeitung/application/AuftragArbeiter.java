package ch.urinet.archiv.verarbeitung.application;

import ch.urinet.archiv.gemeinsam.domain.Ursachen;
import ch.urinet.archiv.mandant.application.MandantKontext;
import ch.urinet.archiv.verarbeitung.domain.AuftragFehler;
import ch.urinet.archiv.verarbeitung.domain.Wiederholung;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
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
 * wenn er wirklich endet, speichert der Worker Abschluss oder Fehlversuch und gibt den Platz frei. So startet weder ein
 * zweiter Platz noch eine andere JVM denselben Auftrag parallel, und es laufen nie mehr Handler als Plätze
 * (Speicherbudget Stufe A). Endet ein abgebrochener Handler auch nach {@value #HAENGEND_NACH_ZEITGRENZEN} Zeitgrenzen
 * nicht, gilt er als hängend: Warnung mit Fehler-ID, keine Lebenszeichen mehr, der Platz bleibt belegt, und der Auftrag
 * bleibt {@code LAEUFT}, bis der Aufräumer ihn freigibt; der Worker selbst speichert keinen Versuch.
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
            try {
                laeufe.add(CompletableFuture.runAsync(() -> bearbeiten(auftrag), ueberwachung));
            } catch (RejectedExecutionException e) {
                // Herunterfahren zwischen Holen und Start: nie gelaufen, also ohne Versuch zurück
                plaetze.release();
                freigebenOhneVersuch(auftrag);
            }
        }
        return laeufe;
    }

    /**
     * Geordneter Stopp (Herunterfahren): holt nichts mehr, unterbricht die laufenden Handler und wartet höchstens
     * {@code archiv.worker.stopp-frist} auf sie. Ein Auftrag, dessen Handler rechtzeitig endet, geht ohne Erhöhung von
     * {@code versuche} zurück auf {@code OFFEN} (ein Deploy kostet keinen Versuch); einer, dessen Handler nicht endet,
     * bleibt {@code LAEUFT}, bis der Aufräumer ihn freigibt.
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
        Lauf lauf = new Lauf();
        try {
            lauf.future = ausfuehrung.submit(() -> ausfuehren(auftrag, lauf));
        } catch (RejectedExecutionException e) {
            plaetze.release();
            freigebenOhneVersuch(auftrag);
            return;
        }
        try {
            ueberwachen(auftrag, lauf);
        } catch (RuntimeException e) {
            // z. B. Abschluss oder Fehlversuch nicht gespeichert: der Auftrag bleibt LAEUFT, der Aufräumer gibt frei
            LOG.errorf("Auftrag %d (%s): Fehler %s, Ergebnis nicht gespeichert, ursache=%s", auftrag.id(),
                    auftrag.typ(), UUID.randomUUID(), Ursachen.kette(e));
        } finally {
            // der Platz wird erst frei, wenn der Handler-Thread wirklich beendet ist
            bisBeendet(lauf);
            plaetze.release();
        }
    }

    private void ausfuehren(Auftrag auftrag, Lauf lauf) {
        try {
            AuftragHandler handler = verzeichnis.handler(auftrag.typ());
            kontext.mit(auftrag.mandant(), () -> {
                try {
                    handler.ausfuehren(auftrag);
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
        freigebenOhneVersuch(auftrag);
    }

    private void freigebenOhneVersuch(Auftrag auftrag) {
        try {
            if (schlange.freigeben(auftrag)) {
                LOG.infof("Auftrag %d (%s): beim Herunterfahren ohne Versuch freigegeben", auftrag.id(), auftrag.typ());
            }
        } catch (RuntimeException e) {
            LOG.warnf("Auftrag %d (%s): beim Herunterfahren nicht freigegeben, bleibt LAEUFT bis zum Aufräumen, "
                    + "ursache=%s", auftrag.id(), auftrag.typ(), Ursachen.kette(e));
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
        nachbearbeiten(auftrag, fehler.fehlerId());
    }

    /**
     * Ruft den Handler nach dem letzten Fehlversuch im Kontext des Mandanten auf, in einem eigenen Thread ohne
     * Request-Kontext und höchstens bis zur Zeitgrenze des Typs, und setzt danach {@code nachbearbeitet}. Auch für den
     * Aufräumer, der im Request-Kontext des Schedulers läuft, wo {@link MandantKontext#mit} abbricht. Scheitert der
     * Hook, bleibt {@code nachbearbeitet} leer und der Aufräumer ruft ihn erneut auf.
     */
    boolean nachbearbeiten(Auftrag auftrag, UUID fehlerId) {
        AuftragHandler handler = verzeichnis.handler(auftrag.typ());
        Throwable fehler;
        try {
            Future<?> lauf = ausfuehrung.submit(() -> kontext.mit(auftrag.mandant(),
                    () -> handler.nachLetztemFehlversuch(auftrag, fehlerId)));
            try {
                lauf.get(verzeichnis.zeitgrenze(auftrag.typ()).toNanos(), TimeUnit.NANOSECONDS);
                if (!schlange.nachbearbeitet(auftrag)) {
                    LOG.warnf("Auftrag %d (%s): Nachbearbeitung schon vermerkt", auftrag.id(), auftrag.typ());
                }
                return true;
            } catch (ExecutionException e) {
                fehler = e.getCause();
            } catch (TimeoutException e) {
                lauf.cancel(true);
                fehler = e;
            } catch (InterruptedException e) {
                lauf.cancel(true);
                Thread.currentThread().interrupt();
                fehler = e;
            }
        } catch (RuntimeException e) {
            fehler = e;
        }
        LOG.errorf("Auftrag %d (%s): Fehler %s nach dem letzten Fehlversuch (Fehler %s), Nachbearbeitung folgt beim "
                        + "Aufräumen, ursache=%s", auftrag.id(), auftrag.typ(), UUID.randomUUID(), fehlerId,
                Ursachen.kette(fehler));
        return false;
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

    /** Zustand eines Handler-Laufs; {@code fehler} ist {@code null}, wenn der Handler normal endete. */
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
