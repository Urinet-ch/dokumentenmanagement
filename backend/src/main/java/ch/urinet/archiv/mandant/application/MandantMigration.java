package ch.urinet.archiv.mandant.application;

import ch.urinet.archiv.mandant.domain.Mandant;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

/** Bringt beim Start das Schema jedes aktiven Mandanten über {@code db/mandant} auf den aktuellen Stand (ADR-002). */
@ApplicationScoped
public class MandantMigration {

    private static final Logger LOG = Logger.getLogger(MandantMigration.class);

    private final MandantRegister register;
    private final MandantDatenbank datenbank;

    public MandantMigration(MandantRegister register, MandantDatenbank datenbank) {
        this.register = register;
        this.datenbank = datenbank;
    }

    public int aktiveMigrieren() {
        datenbank.gemeinsamesSchemaBerechtigen();
        int anzahl = 0;
        for (Mandant mandant : register.aktive()) {
            datenbank.schemaEinrichten(mandant.schema());
            anzahl++;
        }
        LOG.infof("%d aktive Mandanten migriert", anzahl);
        return anzahl;
    }
}
