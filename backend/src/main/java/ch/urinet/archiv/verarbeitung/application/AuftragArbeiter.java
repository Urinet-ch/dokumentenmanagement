package ch.urinet.archiv.verarbeitung.application;

import ch.urinet.archiv.gemeinsam.domain.Ursachen;
import ch.urinet.archiv.mandant.application.MandantKontext;
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
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Der Worker (Req 1.6, 5.4, 5.6, 5.7; design.md «Verarbeitung»): holt fällige Aufträge für die freien Plätze
 * ({@code archiv.worker.parallel}) und führt jeden in einem frischen Thread ohne Request-Kontext und ohne Transaktion
 * im Kontext seines Mandanten aus ({@link MandantKontext#mit}). Ein Überwachungs-Thread je Auftrag schreibt alle
 * {@code archiv.auftrag.lebenszeichen} ein Lebenszeichen und bricht den Handler beim Ablauf der Zeitgrenze ab
 * (Unterbrechung). Der Platz bleibt belegt, bis der Handler-Thread wirklich endet, damit nie mehr Handler laufen als
 * Plätze (Speicherbudget Stufe A). Fehler landen ohne Werte im Auftrag und im Log: nur Art, Fehler-ID und die
 * Klassennamen der Ursachen (Req 16.7).
 */
@ApplicationScoped
public class AuftragArbeiter {

    static final String FEHLGESCHLAGEN = "fehlgeschlagen";
    static final String ZEITGRENZE = "zeitgrenze";
    static final String VERWAIST = "verwaist";

    private static final Logger LOG = Logger.getLogger(AuftragArbeiter.class);

    private final AuftragSchlange schlange;
    private final AuftragHandlerVerzeichnis verzeichnis;
    private final MandantKontext kontext;
    private final Duration lebenszeichen;
    private final Semaphore plaetze;
    private final ExecutorService ueberwachung = Executors.newThreadPerTaskExecutor(
            Thread.ofPlatform().name("auftrag-", 0).daemon().factory());
    private final ExecutorService ausfuehrung = Executors.newThreadPerTaskExecutor(
            Thread.ofPlatform().name("auftrag-handler-", 0).daemon().factory());

    @Inject
    public AuftragArbeiter(AuftragSchlange schlange, AuftragHandlerVerzeichnis verzeichnis, MandantKontext kontext,
                           @ConfigProperty(name = "archiv.worker.parallel") int parallel,
                           @ConfigProperty(name = "archiv.auftrag.lebenszeichen") Duration lebenszeichen) {
        if (parallel < 1) {
            throw new IllegalArgumentException("archiv.worker.parallel muss mindestens 1 sein");
        }
        if (lebenszeichen.isNegative() || lebenszeichen.isZero()) {
            throw new IllegalArgumentException("archiv.auftrag.lebenszeichen muss positiv sein");
        }
        this.schlange = schlange;
        this.verzeichnis = verzeichnis;
        this.kontext = kontext;
        this.lebenszeichen = lebenszeichen;
        this.plaetze = new Semaphore(parallel);
    }

    /**
     * Holt so viele Aufträge, wie Plätze frei sind, und startet sie; jedes Ergebnis ist erfüllt, sobald der Auftrag
     * abgeschlossen ist und sein Platz wieder frei.
     */
    public List<CompletableFuture<Void>> abholen() {
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
            laeufe.add(CompletableFuture.runAsync(() -> {
                try {
                    bearbeiten(auftrag);
                } finally {
                    plaetze.release();
                }
            }, ueberwachung));
        }
        return laeufe;
    }

    @PreDestroy
    void beenden() {
        ueberwachung.shutdownNow();
        ausfuehrung.shutdownNow();
    }

    private void bearbeiten(Auftrag auftrag) {
        AuftragHandler handler = verzeichnis.handler(auftrag.typ());
        Duration zeitgrenze = verzeichnis.zeitgrenze(auftrag.typ());
        CountDownLatch beendet = new CountDownLatch(1);
        Future<?> lauf = ausfuehrung.submit(() -> {
            try {
                kontext.mit(auftrag.mandant(), () -> {
                    try {
                        handler.ausfuehren(auftrag);
                    } catch (RuntimeException e) {
                        throw e;
                    } catch (Exception e) {
                        throw new HandlerFehler(e);
                    }
                });
            } finally {
                beendet.countDown();
            }
        });
        long frist = System.nanoTime() + zeitgrenze.toNanos();
        try {
            while (true) {
                long rest = frist - System.nanoTime();
                if (rest <= 0) {
                    lauf.cancel(true);
                    fehlversuch(auftrag, ZEITGRENZE, null);
                    abwarten(auftrag, beendet);
                    return;
                }
                try {
                    lauf.get(Math.min(rest, lebenszeichen.toNanos()), TimeUnit.NANOSECONDS);
                    if (!schlange.abschliessen(auftrag)) {
                        LOG.warnf("Auftrag %d (%s): Anspruch verloren, Abschluss nicht gespeichert", auftrag.id(),
                                auftrag.typ());
                    }
                    return;
                } catch (TimeoutException e) {
                    if (!lebenszeichenSchreiben(auftrag)) {
                        lauf.cancel(true);
                        LOG.warnf("Auftrag %d (%s): Anspruch verloren (freigegeben), Ausführung abgebrochen",
                                auftrag.id(), auftrag.typ());
                        abwarten(auftrag, beendet);
                        return;
                    }
                } catch (ExecutionException e) {
                    Throwable ursache = e.getCause() instanceof HandlerFehler h ? h.getCause() : e.getCause();
                    fehlversuch(auftrag, FEHLGESCHLAGEN, ursache);
                    return;
                }
            }
        } catch (InterruptedException e) {
            // Herunterfahren: Der Auftrag bleibt LAEUFT, der Aufräumer gibt ihn nach der Zeitgrenze frei (Req 5.7)
            lauf.cancel(true);
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            LOG.errorf("Auftrag %d (%s): Überwachung fehlgeschlagen, ursache=%s", auftrag.id(), auftrag.typ(),
                    Ursachen.kette(e));
            lauf.cancel(true);
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
        String fehlerId = UUID.randomUUID().toString();
        int versuch = auftrag.versuche() + 1;
        Optional<Duration> wartezeit = Wiederholung.nachFehlversuch(versuch);
        String ursachen = ursache == null ? "-" : Ursachen.kette(ursache);
        if (!schlange.fehlversuch(auftrag, art + " fehler-id=" + fehlerId, wartezeit)) {
            LOG.warnf("Auftrag %d (%s): Fehler %s (%s) nicht gespeichert, Anspruch verloren, ursache=%s",
                    auftrag.id(), auftrag.typ(), fehlerId, art, ursachen);
            return;
        }
        if (wartezeit.isPresent()) {
            LOG.warnf("Auftrag %d (%s, Mandant %s): Versuch %d von %d %s, Fehler %s, nächster Versuch in %s, "
                            + "ursache=%s", auftrag.id(), auftrag.typ(), auftrag.mandant().wert(), versuch,
                    Wiederholung.MAX_VERSUCHE, art, fehlerId, wartezeit.get(), ursachen);
            return;
        }
        LOG.errorf("Auftrag %d (%s, Mandant %s): Versuch %d von %d %s, Fehler %s, Status FEHLER, ursache=%s",
                auftrag.id(), auftrag.typ(), auftrag.mandant().wert(), versuch, Wiederholung.MAX_VERSUCHE, art,
                fehlerId, ursachen);
        nachLetztemFehlversuch(auftrag);
    }

    /**
     * Ruft den Handler nach dem letzten Fehlversuch im Kontext des Mandanten auf, in einem eigenen Thread ohne
     * Request-Kontext und höchstens bis zur Zeitgrenze des Typs. Auch für den Aufräumer, der im Request-Kontext des
     * Schedulers läuft, wo {@link MandantKontext#mit} abbricht.
     */
    void nachLetztemFehlversuch(Auftrag auftrag) {
        AuftragHandler handler = verzeichnis.handler(auftrag.typ());
        Future<?> lauf = ausfuehrung.submit(
                () -> kontext.mit(auftrag.mandant(), () -> handler.nachLetztemFehlversuch(auftrag)));
        Throwable fehler;
        try {
            lauf.get(verzeichnis.zeitgrenze(auftrag.typ()).toNanos(), TimeUnit.NANOSECONDS);
            return;
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
        LOG.errorf("Auftrag %d (%s): Fehler %s nach dem letzten Fehlversuch, ursache=%s", auftrag.id(), auftrag.typ(),
                UUID.randomUUID(), Ursachen.kette(fehler));
    }

    private void abwarten(Auftrag auftrag, CountDownLatch beendet) throws InterruptedException {
        while (!beendet.await(lebenszeichen.toNanos(), TimeUnit.NANOSECONDS)) {
            LOG.warnf("Auftrag %d (%s): Ausführung reagiert nicht auf den Abbruch, Platz bleibt belegt", auftrag.id(),
                    auftrag.typ());
        }
    }

    /** Trägt eine geprüfte Ausnahme des Handlers aus dem Mandanten-Kontext heraus. */
    private static final class HandlerFehler extends RuntimeException {
        HandlerFehler(Exception ursache) {
            // ohne Meldung: die der Ursache könnte Werte enthalten
            super(null, ursache, false, false);
        }
    }
}
