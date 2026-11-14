package ch.urinet.archiv.mandant;

import ch.urinet.archiv.gemeinsam.application.SchemaBereitschaft;
import ch.urinet.archiv.protokoll.application.ManifestPlanen;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Attrappe für {@link JobsVorMigrationTest} (nur dort aktiv): hält jeden Aufruf von {@link ManifestPlanen#planen} mit
 * dem Stand des Signals {@link SchemaBereitschaft} fest, ohne Aufträge einzureihen.
 */
@Alternative
@ApplicationScoped
public class ManifestPlanenAttrappe extends ManifestPlanen {

    private final List<Boolean> aufrufe = new CopyOnWriteArrayList<>();

    @Inject
    SchemaBereitschaft bereitschaft;

    public ManifestPlanenAttrappe() {
        super(null, null, null);
    }

    @Override
    public void planen(Instant jetzt) {
        aufrufe.add(bereitschaft.bereit());
    }

    @Override
    public void planenBeimStart(Instant jetzt) {
        planen(jetzt);
    }

    /** Je Aufruf, ob das Signal schon gemeldet war. */
    public List<Boolean> aufrufe() {
        return List.copyOf(aufrufe);
    }
}
