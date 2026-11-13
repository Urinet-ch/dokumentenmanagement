package ch.urinet.archiv.verarbeitung.adapter.out.platzhalter;

import ch.urinet.archiv.verarbeitung.application.SicherungUeberwachung;
import ch.urinet.archiv.verarbeitung.domain.Befund;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Platzhalter, bis der Sicherungs-Export (Aufgabe 5.5) eine eigene Umsetzung von {@link SicherungUeberwachung} bringt:
 * meldet «nicht angebunden», damit die Überwachung keinen Alarm erfindet und keinen erledigt; einmal ein WARN im Log.
 */
@DefaultBean
@ApplicationScoped
public class SicherungUeberwachungNichtAngebunden implements SicherungUeberwachung {

    private static final Logger LOG = Logger.getLogger(SicherungUeberwachungNichtAngebunden.class);

    private final AtomicBoolean gemeldet = new AtomicBoolean();

    @Override
    public Optional<List<Befund>> befunde() {
        if (gemeldet.compareAndSet(false, true)) {
            LOG.warn("Überwachung der Sicherung nicht angebunden (Aufgabe 5.5); den Fehlschlag meldet nur der "
                    + "Sicherungs-Workflow");
        }
        return Optional.empty();
    }
}
