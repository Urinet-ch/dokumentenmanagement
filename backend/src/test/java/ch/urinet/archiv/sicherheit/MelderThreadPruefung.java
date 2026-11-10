package ch.urinet.archiv.sicherheit;

import ch.urinet.archiv.sicherheit.application.Verweigerung;
import ch.urinet.archiv.sicherheit.application.VerweigerungMelder;
import ch.urinet.archiv.sicherheit.domain.Aktion;
import ch.urinet.archiv.sicherheit.domain.Rolle;
import io.quarkus.runtime.BlockingOperationControl;
import jakarta.annotation.Priority;
import jakarta.decorator.Decorator;
import jakarta.decorator.Delegate;
import jakarta.enterprise.inject.Any;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Nur Tests: merkt sich bei jeder Verweigerung, ob der Thread blockieren darf. Der Melder schreibt mit JTA und JDBC und
 * darf nie auf einem Event-Loop-Thread laufen (Vorab-Korrektur 1 aus dem Review von PR #35).
 */
@Decorator
@Priority(1)
public abstract class MelderThreadPruefung implements VerweigerungMelder {

    static final List<Boolean> BLOCKIEREN_ERLAUBT = new CopyOnWriteArrayList<>();

    @Inject
    @Delegate
    @Any
    VerweigerungMelder melder;

    @Override
    public void verweigert(Optional<String> benutzerId, Optional<Rolle> rolle, Optional<Aktion> aktion,
                           Optional<UUID> dokumentId, Verweigerung.Grund grund) {
        BLOCKIEREN_ERLAUBT.add(BlockingOperationControl.isBlockingAllowed());
        melder.verweigert(benutzerId, rolle, aktion, dokumentId, grund);
    }
}
