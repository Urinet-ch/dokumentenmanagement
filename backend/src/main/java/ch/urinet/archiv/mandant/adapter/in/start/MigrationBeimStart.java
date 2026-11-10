package ch.urinet.archiv.mandant.adapter.in.start;

import ch.urinet.archiv.mandant.application.MandantMigration;
import io.agroal.api.AgroalDataSource;
import io.quarkus.arc.InjectableInstance;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Instance;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Nach der Migration von {@code db/public} (Quarkus Liquibase) migriert die API beim Start jedes aktive Mandanten-Schema
 * und prüft danach jedes gegen das Mapping (design.md «Mandant», Req 1.7). Der Worker migriert nie, prüft aber. Ohne
 * aktive Datasource entfällt beides, die Readiness meldet dann DOWN; ohne {@code strategy=validate} (nur in Tests)
 * entfällt die Prüfung, beim Admin-Kommando beides (es migriert selbst).
 */
@ApplicationScoped
public class MigrationBeimStart {

    static final String VALIDIEREN = "validate";

    void beimStart(@Observes StartupEvent start,
                   @ConfigProperty(name = "archiv.rolle") String rollen,
                   @ConfigProperty(name = "quarkus.liquibase.migrate-at-start") boolean migrieren,
                   @ConfigProperty(name = "quarkus.hibernate-orm.schema-management.strategy") String strategie,
                   InjectableInstance<AgroalDataSource> datenquelle,
                   Instance<MandantMigration> migration) {
        var rolle = Rollen.lesen(rollen);
        if (rolle.contains("kommando") || datenquelle.listActive().isEmpty()) {
            return;
        }
        if (migrieren && rolle.contains("api")) {
            migration.get().aktiveMigrieren();
        } else if (VALIDIEREN.equals(strategie)) {
            migration.get().aktivePruefen();
        }
    }
}
