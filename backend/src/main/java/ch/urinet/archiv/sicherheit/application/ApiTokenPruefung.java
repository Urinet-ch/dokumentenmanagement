package ch.urinet.archiv.sicherheit.application;

import ch.urinet.archiv.gemeinsam.domain.FachFehler;
import ch.urinet.archiv.gemeinsam.domain.Fehlercode;
import ch.urinet.archiv.sicherheit.domain.ApiClient;
import ch.urinet.archiv.sicherheit.domain.ApiToken;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Prüft ein API-Token bei jeder Anfrage, ohne Cache (Req 2.5): Lookup über die ID, dann der Argon2id-Vergleich.
 * <p>
 * Invariante gegen Timing: Auch für eine unbekannte ID läuft genau ein Vergleich, gegen einen festen Attrappen-Hash.
 * Sonst verriete die Antwortzeit, welche IDs es gibt. Der Vergleich läuft immer vor der Prüfung von {@code aktiv}.
 * Die Attrappe entsteht beim Start ({@link #attrappeBerechnen()}), nicht bei der ersten Anfrage.
 * <p>
 * Jeder Vergleich kostet rund 19 MiB Heap. Darum laufen höchstens {@code archiv.api-token.parallele-pruefungen}
 * Vergleiche gleichzeitig, auch der gegen die Attrappe (gleicher Weg, gleiche Laufzeit). Wer innerhalb von
 * {@code archiv.api-token.wartezeit} keinen Platz erhält, bekommt ohne Argon2 den Fehler {@code UEBERLASTET} (503);
 * so erschöpfen Anfragen ohne Anmeldung den Heap nicht.
 * <p>
 * {@code zuletzt} wird höchstens einmal pro Minute und Token nachgeführt; ein Fehler dabei bricht die Anfrage nicht ab.
 */
@ApplicationScoped
public class ApiTokenPruefung {

    static final Duration DROSSEL = Duration.ofMinutes(1);
    private static final SecureRandom ZUFALL = new SecureRandom();
    private static final Logger LOG = Logger.getLogger(ApiTokenPruefung.class);

    private final ApiClientRegister register;
    private final GeheimnisHasher hasher;
    private final Clock uhr;
    private final Semaphore sperre;
    private final Duration wartezeit;
    private final Map<String, Instant> zuletztGemeldet = new ConcurrentHashMap<>();
    private volatile String attrappe;

    @Inject
    public ApiTokenPruefung(ApiClientRegister register, GeheimnisHasher hasher,
                            @ConfigProperty(name = "archiv.api-token.parallele-pruefungen") int parallelePruefungen,
                            @ConfigProperty(name = "archiv.api-token.wartezeit") Duration wartezeit) {
        this(register, hasher, Clock.systemUTC(), parallelePruefungen, wartezeit);
    }

    ApiTokenPruefung(ApiClientRegister register, GeheimnisHasher hasher, Clock uhr, int parallelePruefungen,
                     Duration wartezeit) {
        if (parallelePruefungen < 1) {
            throw new IllegalArgumentException("archiv.api-token.parallele-pruefungen muss mindestens 1 sein");
        }
        this.register = register;
        this.hasher = hasher;
        this.uhr = uhr;
        this.sperre = new Semaphore(parallelePruefungen, true);
        this.wartezeit = wartezeit;
    }

    /**
     * Aktiver Eintrag, wenn ID und Geheimnis passen; sonst leer (unbekannt, falsches Geheimnis, widerrufen).
     *
     * @throws FachFehler {@code UEBERLASTET}, wenn innerhalb der Wartezeit kein Platz für den Vergleich frei wird
     */
    public Optional<ApiClient> pruefen(ApiToken token) {
        Optional<ApiClientRegister.Zugang> zugang = register.finden(token.id());
        String hash = zugang.map(ApiClientRegister.Zugang::hash).orElseGet(this::attrappeBerechnen);
        boolean passt = gesperrtPruefen(token.geheimnis(), hash);
        if (zugang.isEmpty() || !passt || !zugang.get().client().aktiv()) {
            return Optional.empty();
        }
        ApiClient client = zugang.get().client();
        zuletztNachfuehren(client.id());
        return Optional.of(client);
    }

    private boolean gesperrtPruefen(String geheimnis, String hash) {
        try {
            if (!sperre.tryAcquire(wartezeit.toNanos(), TimeUnit.NANOSECONDS)) {
                throw new FachFehler(Fehlercode.UEBERLASTET);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new FachFehler(Fehlercode.UEBERLASTET);
        }
        try {
            return hasher.pruefen(geheimnis, hash);
        } finally {
            sperre.release();
        }
    }

    private void zuletztNachfuehren(String id) {
        Instant jetzt = uhr.instant();
        AtomicBoolean faellig = new AtomicBoolean();
        zuletztGemeldet.compute(id, (schluessel, letzte) -> {
            if (letzte != null && letzte.plus(DROSSEL).isAfter(jetzt)) {
                return letzte;
            }
            faellig.set(true);
            return jetzt;
        });
        if (!faellig.get()) {
            return;
        }
        try {
            register.zuletztSetzen(id, jetzt);
        } catch (RuntimeException e) {
            LOG.warnf("Letzte Verwendung des API-Tokens nicht nachgeführt: id=%s", id);
        }
    }

    /**
     * Berechnet beim Start den Hash eines zufälligen Geheimnisses, das niemand kennt, mit den Parametern des Hashers.
     * Ohne Start (Unit-Tests) holt {@link #pruefen} das einmal nach.
     */
    public String attrappeBerechnen() {
        String wert = attrappe;
        if (wert == null) {
            synchronized (this) {
                wert = attrappe;
                if (wert == null) {
                    wert = hasher.hashen(ApiToken.neu(ZUFALL).geheimnis());
                    attrappe = wert;
                }
            }
        }
        return wert;
    }
}
