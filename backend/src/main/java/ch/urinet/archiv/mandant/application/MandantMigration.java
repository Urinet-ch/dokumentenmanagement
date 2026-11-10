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
            // ein fehlendes Schema (z. B. nach unvollständiger Wiederherstellung) nie still leer neu anlegen
            if (!datenbank.schemaVorhanden(mandant.schema())) {
                LOG.errorf("Schema fehlt für aktiven Mandanten %s, Start abgebrochen", mandant.code().wert());
                throw new MandantFehler(MandantFehler.Grund.SCHEMA_FEHLT,
                        "Schema fehlt für aktiven Mandanten " + mandant.code().wert());
            }
            datenbank.schemaMigrieren(mandant.schema());
            anzahl++;
        }
        LOG.infof("%d aktive Mandanten migriert", anzahl);
        return anzahl;
    }
}
