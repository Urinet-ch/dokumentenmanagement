package ch.urinet.archiv.sicherheit.application;

import ch.urinet.archiv.gemeinsam.domain.FachFehler;
import ch.urinet.archiv.gemeinsam.domain.Fehlercode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Duration;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Begrenzt die gleichzeitigen Argon2id-Rechnungen (je rund 19 MiB Heap) auf {@code archiv.api-token.parallele-pruefungen}:
 * Prüfen und Anlegen eines API-Tokens teilen sich dieselben Plätze, damit das Speicherbudget der Stufe A
 * (tech.md, 2 × 19 MiB) für jede Rechnung gilt. Wer innerhalb von {@code archiv.api-token.wartezeit} keinen Platz
 * erhält, bekommt ohne Argon2 den Fehler {@code UEBERLASTET} (503). Wartezeit 0 heisst: nicht warten.
 * Ungültige Werte (weniger als ein oder mehr als {@value #MAX_PLAETZE} Plätze, negative Wartezeit) brechen den Start ab.
 */
@ApplicationScoped
public class HashSperre {

    /** Obergrenze, damit ein Tippfehler nicht {@code n} × 19 MiB Heap freigibt (Review PR #40 Nacht, K2). */
    public static final int MAX_PLAETZE = 16;

    private final Semaphore plaetze;
    private final Duration wartezeit;

    @Inject
    public HashSperre(@ConfigProperty(name = "archiv.api-token.parallele-pruefungen") int parallelePruefungen,
                      @ConfigProperty(name = "archiv.api-token.wartezeit") Duration wartezeit) {
        if (parallelePruefungen < 1 || parallelePruefungen > MAX_PLAETZE) {
            throw new IllegalArgumentException(
                    "archiv.api-token.parallele-pruefungen muss zwischen 1 und " + MAX_PLAETZE + " liegen");
        }
        if (wartezeit == null || wartezeit.isNegative()) {
            throw new IllegalArgumentException("archiv.api-token.wartezeit darf nicht negativ sein");
        }
        this.plaetze = new Semaphore(parallelePruefungen, true);
        this.wartezeit = wartezeit;
    }

    /** Führt die Rechnung aus, sobald ein Platz frei ist; sonst {@code UEBERLASTET}, ohne zu rechnen. */
    public <T> T ausfuehren(Supplier<T> rechnung) {
        try {
            if (!plaetze.tryAcquire(wartezeit.toNanos(), TimeUnit.NANOSECONDS)) {
                throw new FachFehler(Fehlercode.UEBERLASTET);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new FachFehler(Fehlercode.UEBERLASTET);
        }
        try {
            return rechnung.get();
        } finally {
            plaetze.release();
        }
    }
}
