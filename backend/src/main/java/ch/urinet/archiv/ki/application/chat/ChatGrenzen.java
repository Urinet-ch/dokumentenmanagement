package ch.urinet.archiv.ki.application.chat;

import ch.urinet.archiv.mandant.domain.MandantCode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Gleichzeitig laufende Fragen im Speicher dieser Instanz (Requirement 5.5; design.md «Vertrag Welle 7», Anfrage):
 * höchstens {@code archiv.chat.gleichzeitig-je-benutzer} je Benutzer eines Mandanten und
 * {@code archiv.chat.gleichzeitig-gesamt} insgesamt. Ein Platz gilt vom Annehmen der Frage bis nach dem Speichern der
 * Antwort.
 */
@ApplicationScoped
public class ChatGrenzen {

    private final int jeBenutzer;
    private final int gesamt;
    private final Map<String, Integer> laufend = new HashMap<>();
    private int belegt;

    @Inject
    public ChatGrenzen(@ConfigProperty(name = "archiv.chat.gleichzeitig-je-benutzer") int jeBenutzer,
                       @ConfigProperty(name = "archiv.chat.gleichzeitig-gesamt") int gesamt) {
        if (jeBenutzer < 1 || gesamt < 1) {
            throw new IllegalArgumentException("archiv.chat.gleichzeitig-* muss mindestens 1 sein");
        }
        this.jeBenutzer = jeBenutzer;
        this.gesamt = gesamt;
    }

    /** Belegter Platz; {@link #freigeben()} gibt ihn genau einmal frei. */
    public final class Platz {

        private final String schluessel;
        private final AtomicBoolean frei = new AtomicBoolean();

        private Platz(String schluessel) {
            this.schluessel = schluessel;
        }

        public void freigeben() {
            if (frei.compareAndSet(false, true)) {
                zurueckgeben(schluessel);
            }
        }
    }

    /** Ein Platz für die Frage; leer, wenn der Benutzer oder die Instanz schon die Höchstzahl laufen hat. */
    public synchronized Optional<Platz> belegen(MandantCode mandant, String benutzer) {
        String schluessel = mandant.wert() + "\n" + benutzer;
        int eigene = laufend.getOrDefault(schluessel, 0);
        if (eigene >= jeBenutzer || belegt >= gesamt) {
            return Optional.empty();
        }
        laufend.put(schluessel, eigene + 1);
        belegt++;
        return Optional.of(new Platz(schluessel));
    }

    /** Zahl der belegten Plätze (für Tests und Kennzahlen). */
    public synchronized int belegt() {
        return belegt;
    }

    private synchronized void zurueckgeben(String schluessel) {
        laufend.computeIfPresent(schluessel, (k, n) -> n > 1 ? n - 1 : null);
        belegt--;
    }
}
