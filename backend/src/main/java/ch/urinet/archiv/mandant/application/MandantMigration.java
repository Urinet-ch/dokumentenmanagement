package ch.urinet.archiv.mandant.application;

import ch.urinet.archiv.mandant.domain.Mandant;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.util.List;

/**
 * Bringt beim Start das Schema jedes aktiven Mandanten über {@code db/mandant} auf den aktuellen Stand (ADR-002) und
 * prüft danach jedes Mandanten-Schema gegen das Mapping der Entitäten (Req 1.7, design.md «Mandant»).
 */
@ApplicationScoped
public class MandantMigration {

    private static final Logger LOG = Logger.getLogger(MandantMigration.class);

    private final MandantRegister register;
    private final MandantDatenbank datenbank;
    private final MandantSchemaPruefung schemaPruefung;

    public MandantMigration(MandantRegister register, MandantDatenbank datenbank, MandantSchemaPruefung schemaPruefung) {
        this.register = register;
        this.datenbank = datenbank;
        this.schemaPruefung = schemaPruefung;
    }

    /** API: alle aktiven Schemas migrieren, danach jedes validieren. */
    public int aktiveMigrieren() {
        datenbank.gemeinsamesSchemaBerechtigen();
        List<Mandant> aktive = register.aktive();
        for (Mandant mandant : aktive) {
            schemaVorhanden(mandant);
            datenbank.schemaMigrieren(mandant.schema());
        }
        LOG.infof("%d aktive Mandanten migriert", aktive.size());
        pruefen(aktive);
        return aktive.size();
    }

    /**
     * Worker und API ohne Migration: jedes aktive Schema nur validieren, ohne Eigentümer-Zugang; ein fehlendes Schema
     * meldet die Prüfung als {@code SCHEMA_ABWEICHUNG}.
     */
    public int aktivePruefen() {
        List<Mandant> aktive = register.aktive();
        pruefen(aktive);
        return aktive.size();
    }

    private void pruefen(List<Mandant> aktive) {
        for (Mandant mandant : aktive) {
            try {
                schemaPruefung.pruefen(mandant.schema());
            } catch (MandantFehler e) {
                LOG.errorf("Schema weicht ab für Mandant %s, Start abgebrochen", mandant.code().wert());
                throw e;
            }
        }
        LOG.infof("%d Mandanten-Schemas geprüft", aktive.size());
    }

    private void schemaVorhanden(Mandant mandant) {
        // ein fehlendes Schema (z. B. nach unvollständiger Wiederherstellung) nie still leer neu anlegen
        if (!datenbank.schemaVorhanden(mandant.schema())) {
            LOG.errorf("Schema fehlt für aktiven Mandanten %s, Start abgebrochen", mandant.code().wert());
            throw new MandantFehler(MandantFehler.Grund.SCHEMA_FEHLT,
                    "Schema fehlt für aktiven Mandanten " + mandant.code().wert());
        }
    }
}
