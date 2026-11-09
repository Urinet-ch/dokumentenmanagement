package ch.urinet.archiv.integration.adapter.out.stub;

import ch.urinet.archiv.integration.application.IntegrationPort;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Typed;

/** Dev-Stub von {@link IntegrationPort}: ohne externe Dienste (AbaNinja, Mail, WordPress); aktiv mit {@code archiv.integration.stub=true} (Profil dev). */
@ApplicationScoped
@Typed(IntegrationStub.class)
public class IntegrationStub implements IntegrationPort {
}
