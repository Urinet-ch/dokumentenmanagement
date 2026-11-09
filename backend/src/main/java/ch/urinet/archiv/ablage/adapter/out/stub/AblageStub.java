package ch.urinet.archiv.ablage.adapter.out.stub;

import ch.urinet.archiv.ablage.application.AblagePort;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Typed;

/** Dev-Stub von {@link AblagePort}: Dateisystem statt Object Storage; aktiv mit {@code archiv.ablage.stub=true} (Profil dev). */
@ApplicationScoped
@Typed(AblageStub.class)
public class AblageStub implements AblagePort {
}
