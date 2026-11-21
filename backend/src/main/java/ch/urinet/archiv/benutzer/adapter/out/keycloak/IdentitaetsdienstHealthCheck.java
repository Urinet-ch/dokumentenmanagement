package ch.urinet.archiv.benutzer.adapter.out.keycloak;

import ch.urinet.archiv.benutzer.application.IdentitaetsDienst;
import ch.urinet.archiv.gemeinsam.domain.FachFehler;
import io.quarkus.arc.profile.IfBuildProfile;
import io.smallrye.health.api.HealthGroup;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;

/** Interne Detailprüfung, getrennt von Liveness und Readiness (Spec 06, Requirement 5.8). */
@IfBuildProfile("prod")
@HealthGroup("detail")
@ApplicationScoped
public class IdentitaetsdienstHealthCheck implements HealthCheck {

    private final IdentitaetsDienst identitaetsDienst;

    public IdentitaetsdienstHealthCheck(IdentitaetsDienst identitaetsDienst) {
        this.identitaetsDienst = identitaetsDienst;
    }

    @Override
    public HealthCheckResponse call() {
        try {
            identitaetsDienst.verbindungPruefen();
            return HealthCheckResponse.named("identitaetsdienst").up().build();
        } catch (FachFehler e) {
            return HealthCheckResponse.named("identitaetsdienst")
                    .down().withData("fehlercode", e.code().name()).build();
        }
    }
}
