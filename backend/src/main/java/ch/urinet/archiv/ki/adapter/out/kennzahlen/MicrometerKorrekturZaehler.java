package ch.urinet.archiv.ki.adapter.out.kennzahlen;

import ch.urinet.archiv.ki.application.KorrekturZaehlerPort;
import ch.urinet.archiv.ki.domain.Feld;
import ch.urinet.archiv.mandant.domain.MandantCode;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Kennzahl Korrekturquote je Feld und Mandant (Req 7.7, 16.4): die Zähler {@code archiv_ki_feld_bestaetigt_total} und
 * {@code archiv_ki_feld_korrigiert_total} sowie die Gauge {@code archiv_ki_feld_korrekturquote} (korrigiert / bestätigt
 * seit dem Start des Prozesses, 0 ohne Bestätigung), alle mit den Tags {@code feld} und {@code mandant}. Beim ersten
 * Zählen eines Mandanten entstehen die Reihen für alle Felder des festen Katalogs {@link Feld} mit 0, damit das
 * Monitoring keine Reihe erst mit dem ersten Treffer sieht ({@code increase} verliert sonst den ersten Schritt); die
 * Kardinalität ist Felder × Mandanten. Die Quote über 90 Tage rechnet das Monitoring aus den Zählern
 * ({@code increase(…[90d])}); die Gauge zeigt nur den Stand seit dem Start. Keine Inhalte, nur Feldnamen.
 */
@ApplicationScoped
public class MicrometerKorrekturZaehler implements KorrekturZaehlerPort {

    static final String BESTAETIGT = "archiv.ki.feld.bestaetigt";
    static final String KORRIGIERT = "archiv.ki.feld.korrigiert";
    static final String QUOTE = "archiv.ki.feld.korrekturquote";

    private final MeterRegistry registry;
    private final Set<String> angelegt = ConcurrentHashMap.newKeySet();

    public MicrometerKorrekturZaehler(MeterRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void zaehlen(MandantCode mandant, Set<Feld> bestaetigt, Set<Feld> korrigiert) {
        anlegen(mandant);
        bestaetigt.forEach(feld -> zaehler(BESTAETIGT, feld, mandant).increment());
        korrigiert.forEach(feld -> zaehler(KORRIGIERT, feld, mandant).increment());
    }

    private void anlegen(MandantCode mandant) {
        if (!angelegt.add(mandant.wert())) {
            return;
        }
        for (Feld feld : Feld.values()) {
            Counter bestaetigt = zaehler(BESTAETIGT, feld, mandant);
            Counter korrigiert = zaehler(KORRIGIERT, feld, mandant);
            Gauge.builder(QUOTE, () -> bestaetigt.count() == 0 ? 0.0 : korrigiert.count() / bestaetigt.count())
                    .tag("feld", feld.schluessel()).tag("mandant", mandant.wert()).register(registry);
        }
    }

    private Counter zaehler(String name, Feld feld, MandantCode mandant) {
        return registry.counter(name, "feld", feld.schluessel(), "mandant", mandant.wert());
    }
}
