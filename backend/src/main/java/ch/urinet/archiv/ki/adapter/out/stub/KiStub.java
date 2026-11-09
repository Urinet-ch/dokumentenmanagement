package ch.urinet.archiv.ki.adapter.out.stub;

import ch.urinet.archiv.ki.application.KiPort;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Typed;

/** Dev-Stub von {@link KiPort}: feste Antworten statt Infomaniak AI Services; aktiv mit {@code archiv.ki.stub=true} (Profil dev). */
@ApplicationScoped
@Typed(KiStub.class)
public class KiStub implements KiPort {
}
