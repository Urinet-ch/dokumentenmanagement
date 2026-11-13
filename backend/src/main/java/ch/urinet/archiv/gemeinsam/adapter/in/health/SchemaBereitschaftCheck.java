package ch.urinet.archiv.gemeinsam.adapter.in.health;

import ch.urinet.archiv.gemeinsam.application.SchemaBereitschaft;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Readiness;

/**
 * Readiness DOWN, bis {@code MigrationBeimStart} jedes aktive Mandanten-Schema migriert bzw. geprüft hat. Heute öffnet
 * Quarkus den HTTP-Server ohnehin erst nach allen Start-Beobachtern; der Check hält die Zusage auch dann, wenn sich
 * diese Reihenfolge ändert (design.md «Mandant», «Start und Migration»).
 */
@Readiness
@ApplicationScoped
public class SchemaBereitschaftCheck implements HealthCheck {

    static final String NAME = "Mandanten-Schemas migriert";

    private final SchemaBereitschaft bereitschaft;

    public SchemaBereitschaftCheck(SchemaBereitschaft bereitschaft) {
        this.bereitschaft = bereitschaft;
    }

    @Override
    public HealthCheckResponse call() {
        return HealthCheckResponse.named(NAME).status(bereitschaft.bereit()).build();
    }
}
