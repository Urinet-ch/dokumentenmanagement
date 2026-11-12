package ch.urinet.archiv.verarbeitung.adapter.out.platzhalter;

import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.verarbeitung.application.ManifestUeberwachung;
import ch.urinet.archiv.verarbeitung.domain.Befund;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Platzhalter, bis das Tages-Manifest (Aufgabe 5.2) eine eigene Umsetzung von {@link ManifestUeberwachung} bringt: meldet
 * «nicht angebunden», damit die Überwachung keinen Alarm erfindet und keinen erledigt; einmal ein WARN im Log.
 */
@DefaultBean
@ApplicationScoped
public class ManifestUeberwachungNichtAngebunden implements ManifestUeberwachung {

    private static final Logger LOG = Logger.getLogger(ManifestUeberwachungNichtAngebunden.class);

    private final AtomicBoolean gemeldet = new AtomicBoolean();

    @Override
    public Optional<List<Befund>> befunde(MandantCode mandant) {
        if (gemeldet.compareAndSet(false, true)) {
            LOG.warn("Überwachung der Manifeste nicht angebunden (Aufgabe 5.2); kein Alarm zu fehlenden Manifesten");
        }
        return Optional.empty();
    }
}
