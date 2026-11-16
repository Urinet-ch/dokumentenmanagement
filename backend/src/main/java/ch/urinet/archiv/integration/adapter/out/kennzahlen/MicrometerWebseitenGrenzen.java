package ch.urinet.archiv.integration.adapter.out.kennzahlen;

import ch.urinet.archiv.gemeinsam.domain.ZuVieleAnfragen;
import ch.urinet.archiv.integration.application.webseite.WebseitenGrenzenKennzahl;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.EnumMap;
import java.util.Map;

/**
 * Kennzahl {@code archiv_webseite_grenze_abgelehnt_total} mit dem Tag {@code grund} aus der festen Liste
 * {@code ANFRAGEN}, {@code SUCHE}, {@code UPLOADS} (alle von Beginn an mit 0 angelegt) und
 * {@code archiv_webseite_grenze_ausgefallen_total} (Prüfung ausgefallen, Anfrage durchgelassen). Nie Schlüssel-ID,
 * Webseite oder Client als Tag (Kardinalität fest).
 */
@ApplicationScoped
public class MicrometerWebseitenGrenzen implements WebseitenGrenzenKennzahl {

    static final String ABGELEHNT = "archiv.webseite.grenze.abgelehnt";
    static final String AUSGEFALLEN = "archiv.webseite.grenze.ausgefallen";

    private final Map<ZuVieleAnfragen.Grund, Counter> abgelehnt = new EnumMap<>(ZuVieleAnfragen.Grund.class);
    private final Counter ausgefallen;

    public MicrometerWebseitenGrenzen(MeterRegistry registry) {
        for (ZuVieleAnfragen.Grund g : new ZuVieleAnfragen.Grund[] {ZuVieleAnfragen.Grund.ANFRAGEN,
                ZuVieleAnfragen.Grund.SUCHE, ZuVieleAnfragen.Grund.UPLOADS}) {
            abgelehnt.put(g, Counter.builder(ABGELEHNT).tag("grund", g.name()).register(registry));
        }
        ausgefallen = Counter.builder(AUSGEFALLEN).register(registry);
    }

    @Override
    public void abgelehnt(ZuVieleAnfragen.Grund grund) {
        Counter zaehler = abgelehnt.get(grund);
        if (zaehler != null) {
            zaehler.increment();
        }
    }

    @Override
    public void ausgefallen() {
        ausgefallen.increment();
    }
}
