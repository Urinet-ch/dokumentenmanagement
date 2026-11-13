package ch.urinet.archiv.gemeinsam.application;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Bereitschafts-Signal des Starts (design.md «Mandant», «Start und Migration»): {@code MigrationBeimStart} meldet es,
 * sobald jedes aktive Mandanten-Schema migriert (API) bzw. geprüft (Worker) ist. Bis dahin überspringen die geplanten
 * Jobs mit Mandanten-Daten ihren Takt, die Readiness meldet DOWN. Bricht die Migration ab, bleibt das Signal aus; der
 * Start bricht dann ohnehin ab. Mit dem Signal feuert einmalig {@link SchemasBereit}, z. B. für den Nachhollauf der
 * Manifeste.
 */
@ApplicationScoped
public class SchemaBereitschaft {

    private final AtomicBoolean bereit = new AtomicBoolean();
    private final Event<SchemasBereit> ereignis;

    public SchemaBereitschaft(Event<SchemasBereit> ereignis) {
        this.ereignis = ereignis;
    }

    public boolean bereit() {
        return bereit.get();
    }

    /** Nur nach erfolgreicher Migration bzw. Prüfung aller aktiven Mandanten-Schemas aufrufen; wirkt einmal. */
    public void melden() {
        if (bereit.compareAndSet(false, true)) {
            ereignis.fire(new SchemasBereit());
        }
    }
}
