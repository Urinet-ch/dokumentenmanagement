package ch.urinet.archiv.ki.application.oeffentlich;

import ch.urinet.archiv.gemeinsam.domain.ZuVieleAnfragen;
import ch.urinet.archiv.integration.domain.webseite.Lesezugriff;
import ch.urinet.archiv.mandant.domain.MandantCode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Grenzen des öffentlichen Chatbots im Speicher dieser Instanz (Spec 03, Req 6.4, 15.1; design.md «Grenzen»; die Zähler
 * beginnen nach einem Neustart neu, mehrere Instanzen sind nicht vorgesehen):
 * <ul>
 *   <li>je Client (der von der Webseite übermittelte Hash, ohne Hash alle Besucher ohne Hash der Webseite zusammen)
 *       höchstens {@code archiv.webseite.chat.fragen-je-stunde} (20) Fragen in 60 Minuten,</li>
 *   <li>je Webseite höchstens {@code archiv.webseite.chat.fragen-je-stunde-schluessel} Fragen in 60 Minuten: Wer den Hash
 *       bei jeder Frage wechselt, umgeht sonst die Grenze je Client,</li>
 *   <li>gleichzeitig laufende Fragen: {@code …gleichzeitig-je-client} und {@code …gleichzeitig-gesamt} (eigene Zahl, damit
 *       der öffentliche Chat den internen nie verdrängt).</li>
 * </ul>
 * Eine abgelehnte Frage zählt in keiner Grenze. Der Hash steht nur hier im Speicher, nie in einer Tabelle.
 */
@ApplicationScoped
public class OeffentlicheGrenzen {

    static final Duration FENSTER = Duration.ofHours(1);
    /** {@code Retry-After}, wenn schon eine Frage des Clients (oder die Höchstzahl der Instanz) läuft. */
    static final long LAEUFT_SEKUNDEN = 5;
    private static final int AUFRAEUMEN_AB = 10_000;

    private final int jeClientProStunde;
    private final int jeSchluesselProStunde;
    private final int gleichzeitigJeClient;
    private final int gleichzeitigGesamt;
    private final Clock uhr;
    private final Map<String, ArrayDeque<Instant>> fenster = new HashMap<>();
    private final Map<String, Integer> laufend = new HashMap<>();
    private int belegt;

    @Inject
    public OeffentlicheGrenzen(
            @ConfigProperty(name = "archiv.webseite.chat.fragen-je-stunde") int jeClientProStunde,
            @ConfigProperty(name = "archiv.webseite.chat.fragen-je-stunde-schluessel") int jeSchluesselProStunde,
            @ConfigProperty(name = "archiv.webseite.chat.gleichzeitig-je-client") int gleichzeitigJeClient,
            @ConfigProperty(name = "archiv.webseite.chat.gleichzeitig-gesamt") int gleichzeitigGesamt) {
        this(jeClientProStunde, jeSchluesselProStunde, gleichzeitigJeClient, gleichzeitigGesamt, Clock.systemUTC());
    }

    OeffentlicheGrenzen(int jeClientProStunde, int jeSchluesselProStunde, int gleichzeitigJeClient,
                        int gleichzeitigGesamt, Clock uhr) {
        if (jeClientProStunde < 1 || jeSchluesselProStunde < 1 || gleichzeitigJeClient < 1 || gleichzeitigGesamt < 1) {
            throw new IllegalArgumentException("archiv.webseite.chat.* (Grenzen) muss mindestens 1 sein");
        }
        this.jeClientProStunde = jeClientProStunde;
        this.jeSchluesselProStunde = jeSchluesselProStunde;
        this.gleichzeitigJeClient = gleichzeitigJeClient;
        this.gleichzeitigGesamt = gleichzeitigGesamt;
        this.uhr = uhr;
    }

    /** Belegter Platz; {@link #freigeben()} gibt ihn genau einmal frei. */
    public final class Platz {

        private final String client;
        private final String schluessel;
        private final Instant angenommen;
        private final AtomicBoolean frei = new AtomicBoolean();

        private Platz(String client, String schluessel, Instant angenommen) {
            this.client = client;
            this.schluessel = schluessel;
            this.angenommen = angenommen;
        }

        public void freigeben() {
            if (frei.compareAndSet(false, true)) {
                zurueckgeben(client);
            }
        }

        /** Gibt den Platz frei und nimmt die Frage aus den Stundenfenstern: eine abgelehnte Frage zählt nicht. */
        public void verwerfen() {
            if (frei.compareAndSet(false, true)) {
                zurueckziehen(client, schluessel, angenommen);
            }
        }
    }

    /**
     * Nimmt eine Frage an: 429 {@code ZU_VIELE_ANFRAGEN} mit {@code grund} {@code LAEUFT} (schon eine Frage des Clients oder
     * die Höchstzahl der Instanz) bzw. {@code STUNDENLIMIT} (je Client oder je Webseite) und {@code Retry-After}.
     */
    public synchronized Platz annehmen(MandantCode mandant, Lesezugriff zugriff) {
        Instant jetzt = uhr.instant();
        String schluessel = mandant.wert() + "\n" + zugriff.webseite();
        String client = schluessel + "\n" + zugriff.clientHash().orElse("-");
        if (laufend.getOrDefault(client, 0) >= gleichzeitigJeClient || belegt >= gleichzeitigGesamt) {
            throw new ZuVieleAnfragen(ZuVieleAnfragen.Grund.LAEUFT, LAEUFT_SEKUNDEN);
        }
        if (fenster.size() > AUFRAEUMEN_AB) {
            aufraeumen(jetzt);
        }
        ArrayDeque<Instant> clientFenster = fenster.computeIfAbsent("c\n" + client, k -> new ArrayDeque<>());
        ArrayDeque<Instant> schluesselFenster = fenster.computeIfAbsent("w\n" + schluessel, k -> new ArrayDeque<>());
        long wartezeit = Math.max(warten(clientFenster, jeClientProStunde, jetzt),
                warten(schluesselFenster, jeSchluesselProStunde, jetzt));
        if (wartezeit > 0) {
            if (clientFenster.isEmpty()) {
                fenster.remove("c\n" + client);
            }
            if (schluesselFenster.isEmpty()) {
                fenster.remove("w\n" + schluessel);
            }
            throw new ZuVieleAnfragen(ZuVieleAnfragen.Grund.STUNDENLIMIT, wartezeit);
        }
        clientFenster.addLast(jetzt);
        schluesselFenster.addLast(jetzt);
        laufend.merge(client, 1, Integer::sum);
        belegt++;
        return new Platz(client, schluessel, jetzt);
    }

    /** Zahl der belegten Plätze (für Tests und Kennzahlen). */
    public synchronized int belegt() {
        return belegt;
    }

    /** Entfernt abgelaufene Einträge des Fensters; gibt die Wartezeit in Sekunden zurück, 0 wenn Platz ist. */
    private static long warten(ArrayDeque<Instant> zeiten, int grenze, Instant jetzt) {
        Instant start = jetzt.minus(FENSTER);
        while (!zeiten.isEmpty() && !zeiten.peekFirst().isAfter(start)) {
            zeiten.removeFirst();
        }
        if (zeiten.size() < grenze) {
            return 0;
        }
        long sekunden = Duration.between(jetzt, zeiten.peekFirst().plus(FENSTER)).toSeconds() + 1;
        return Math.max(1, sekunden);
    }

    private void aufraeumen(Instant jetzt) {
        Instant start = jetzt.minus(FENSTER);
        fenster.values().removeIf(z -> z.isEmpty() || !z.peekLast().isAfter(start));
    }

    private synchronized void zurueckziehen(String client, String schluessel, Instant angenommen) {
        zurueckgeben(client);
        for (String name : new String[]{"c\n" + client, "w\n" + schluessel}) {
            ArrayDeque<Instant> zeiten = fenster.get(name);
            if (zeiten != null) {
                zeiten.removeLastOccurrence(angenommen);
                if (zeiten.isEmpty()) {
                    fenster.remove(name);
                }
            }
        }
    }

    private synchronized void zurueckgeben(String client) {
        laufend.computeIfPresent(client, (k, n) -> n > 1 ? n - 1 : null);
        belegt--;
    }
}
