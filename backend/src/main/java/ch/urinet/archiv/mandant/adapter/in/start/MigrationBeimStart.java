package ch.urinet.archiv.mandant.adapter.in.start;

import ch.urinet.archiv.mandant.application.MandantMigration;
import io.agroal.api.AgroalDataSource;
import io.quarkus.arc.InjectableInstance;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Instance;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.List;

/**
 * Nach der Migration von {@code db/public} (Quarkus Liquibase) migriert die API beim Start jedes aktive Mandanten-Schema
 * (design.md «Mandant»); der Worker migriert nie. Ohne aktive Datasource oder mit {@code migrate-at-start=false} entfällt
 * das, die Readiness meldet dann DOWN.
 */
@ApplicationScoped
public class MigrationBeimStart {

    void beimStart(@Observes StartupEvent start,
                   @ConfigProperty(name = "archiv.rolle") List<String> rollen,
                   @ConfigProperty(name = "quarkus.liquibase.migrate-at-start") boolean migrieren,
                   InjectableInstance<AgroalDataSource> datenquelle,
                   Instance<MandantMigration> migration) {
        if (migrieren && rollen.contains("api") && !datenquelle.listActive().isEmpty()) {
            migration.get().aktiveMigrieren();
        }
    }
}
