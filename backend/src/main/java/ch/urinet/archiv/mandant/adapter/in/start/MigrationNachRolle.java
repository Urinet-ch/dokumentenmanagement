package ch.urinet.archiv.mandant.adapter.in.start;

import io.smallrye.config.ConfigSourceInterceptor;
import io.smallrye.config.ConfigSourceInterceptorContext;
import io.smallrye.config.ConfigValue;
import io.smallrye.config.Priorities;
import jakarta.annotation.Priority;

import java.util.Set;

/**
 * Koppelt {@code quarkus.liquibase.migrate-at-start} an {@code archiv.rolle}: {@code db/public} migrieren nur die API
 * und das Admin-Kommando (design.md «Mandant»), der Worker nie und braucht darum kein {@code archiv_db_owner}.
 */
@Priority(Priorities.APPLICATION)
public class MigrationNachRolle implements ConfigSourceInterceptor {

    static final String MIGRIEREN = "quarkus.liquibase.migrate-at-start";
    private static final Set<String> MIGRIERENDE_ROLLEN = Set.of("api", "kommando");

    @Override
    public ConfigValue getValue(ConfigSourceInterceptorContext context, String name) {
        ConfigValue wert = context.proceed(name);
        if (!MIGRIEREN.equals(name) || wert == null || !Boolean.parseBoolean(wert.getValue())) {
            return wert;
        }
        ConfigValue rolle = context.proceed("archiv.rolle");
        boolean migriert = rolle != null
                && Rollen.lesen(rolle.getValue()).stream().anyMatch(MIGRIERENDE_ROLLEN::contains);
        return migriert ? wert : wert.withValue("false");
    }
}
