package ch.urinet.archiv.integration.adapter.out.stub;

import ch.urinet.archiv.integration.application.mail.PostfachPort;
import ch.urinet.archiv.integration.domain.mail.Postfach;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Typed;

/** Dev-Stub von {@link PostfachPort}: ein leeres Postfach ohne Verbindung; aktiv mit {@code archiv.integration.stub=true}. */
@ApplicationScoped
@Typed(PostfachStub.class)
public class PostfachStub implements PostfachPort {

    @Override
    public Abruf abrufen(Postfach postfach, String passwort, int max, Verarbeiter verarbeiter) {
        return new Abruf(0, 0);
    }
}
