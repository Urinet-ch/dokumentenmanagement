package ch.urinet.archiv.mandant.adapter.in.start;

import ch.urinet.archiv.gemeinsam.application.SchemaBereitschaft;
import ch.urinet.archiv.mandant.application.MandantMigration;
import io.agroal.api.AgroalDataSource;
import io.quarkus.arc.InjectableInstance;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Instance;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.function.Supplier;

/**
 * Nach der Migration von {@code db/public} (Quarkus Liquibase) migriert die API beim Start jedes aktive Mandanten-Schema
 * und prüft danach jedes gegen das Mapping (design.md «Mandant», Req 1.7). Der Worker migriert nie, prüft aber. Ohne
 * aktive Datasource entfällt beides, die Readiness meldet dann DOWN; ohne {@code strategy=validate} (nur in Tests)
 * entfällt die Prüfung, beim Admin-Kommando beides: Es migriert und prüft das neue Schema selbst
 * ({@code MandantProvisionierung}). Die Entscheidung trifft {@link #entscheiden}, Nachweis {@code MigrationEntscheidTest}.
 * <p>
 * Erst danach meldet der Start die {@link SchemaBereitschaft}: Bis dahin überspringen die geplanten Jobs mit
 * Mandanten-Daten ihren Takt, und die Readiness meldet DOWN (design.md «Mandant», «Start und Migration»). Bricht die
 * Migration oder Prüfung ab, bleibt die Meldung aus und der Start bricht ab; ohne aktive Datasource und beim
 * Admin-Kommando bleibt sie ebenfalls aus ({@link #meldet}).
 */
@ApplicationScoped
public class MigrationBeimStart {

    static final String VALIDIEREN = "validate";

    /** Was der Start mit den Mandanten-Schemas tut. */
    enum Schritt { NICHTS, MIGRIEREN, PRUEFEN }

    void beimStart(@Observes StartupEvent start,
                   @ConfigProperty(name = "archiv.rolle") String rollen,
                   @ConfigProperty(name = "quarkus.liquibase.migrate-at-start") boolean migrieren,
                   @ConfigProperty(name = "quarkus.hibernate-orm.schema-management.strategy") String strategie,
                   InjectableInstance<AgroalDataSource> datenquelle,
                   Instance<MandantMigration> migration,
                   SchemaBereitschaft bereitschaft) {
        boolean datenquelleAktiv = !datenquelle.listActive().isEmpty();
        ausfuehren(entscheiden(rollen, migrieren, strategie, datenquelleAktiv), meldet(rollen, datenquelleAktiv),
                migration::get, bereitschaft::melden);
    }

    /** Führt den Schritt aus und meldet danach die Bereitschaft; eine Ausnahme bricht vor der Meldung ab. */
    static void ausfuehren(Schritt schritt, boolean meldet, Supplier<MandantMigration> migration, Runnable melden) {
        switch (schritt) {
            case MIGRIEREN -> migration.get().aktiveMigrieren();
            case PRUEFEN -> migration.get().aktivePruefen();
            case NICHTS -> {
                // Admin-Kommando, ohne Datenquelle oder ohne validate
            }
        }
        if (meldet) {
            melden.run();
        }
    }

    /**
     * Das Admin-Kommando migriert nur das neue Schema, nicht die bestehenden; es meldet darum ebenso wenig Bereitschaft
     * wie ein Start ohne Datasource.
     */
    static boolean meldet(String rollen, boolean datenquelleAktiv) {
        return datenquelleAktiv && !Rollen.lesen(rollen).contains("kommando");
    }

    static Schritt entscheiden(String rollen, boolean migrieren, String strategie, boolean datenquelleAktiv) {
        var rolle = Rollen.lesen(rollen);
        if (rolle.contains("kommando") || !datenquelleAktiv) {
            return Schritt.NICHTS;
        }
        if (migrieren && rolle.contains("api")) {
            return Schritt.MIGRIEREN;
        }
        return VALIDIEREN.equals(strategie) ? Schritt.PRUEFEN : Schritt.NICHTS;
    }
}
