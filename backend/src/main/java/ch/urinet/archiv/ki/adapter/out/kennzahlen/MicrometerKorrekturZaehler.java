package ch.urinet.archiv.ki.adapter.out.kennzahlen;

import ch.urinet.archiv.ki.application.KorrekturZaehlerPort;
import ch.urinet.archiv.ki.domain.Feld;
import ch.urinet.archiv.mandant.domain.MandantCode;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Set;

/**
 * Zähler der Korrekturquote je Feld und Mandant (Req 7.7, 16.4): {@code archiv_ki_feld_bestaetigt_total} und
 * {@code archiv_ki_feld_korrigiert_total} mit den Tags {@code feld} und {@code mandant}. Die Quote über 90 Tage rechnet
 * das Monitoring ({@code increase(…[90d])}); die Zähler tragen keine Inhalte.
 */
@ApplicationScoped
public class MicrometerKorrekturZaehler implements KorrekturZaehlerPort {

    static final String BESTAETIGT = "archiv.ki.feld.bestaetigt";
    static final String KORRIGIERT = "archiv.ki.feld.korrigiert";

    private final MeterRegistry registry;

    public MicrometerKorrekturZaehler(MeterRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void zaehlen(MandantCode mandant, Set<Feld> bestaetigt, Set<Feld> korrigiert) {
        bestaetigt.forEach(feld -> registry.counter(BESTAETIGT, "feld", feld.schluessel(), "mandant", mandant.wert())
                .increment());
        korrigiert.forEach(feld -> registry.counter(KORRIGIERT, "feld", feld.schluessel(), "mandant", mandant.wert())
                .increment());
    }
}
