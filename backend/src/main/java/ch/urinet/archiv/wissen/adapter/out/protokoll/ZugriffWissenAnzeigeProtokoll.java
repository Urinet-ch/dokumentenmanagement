package ch.urinet.archiv.wissen.adapter.out.protokoll;

import ch.urinet.archiv.protokoll.application.ZugriffProtokoll;
import ch.urinet.archiv.protokoll.domain.IpAdresse;
import ch.urinet.archiv.protokoll.domain.ProtokollAktion;
import ch.urinet.archiv.protokoll.domain.ProtokollDetails;
import ch.urinet.archiv.protokoll.domain.ZugriffEintrag;
import ch.urinet.archiv.sicherheit.domain.Rolle;
import ch.urinet.archiv.wissen.application.WissenAnzeigeProtokoll;
import io.quarkus.security.identity.SecurityIdentity;
import io.vertx.core.http.HttpServerRequest;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;

import java.util.Optional;
import java.util.UUID;

/**
 * Schreibt {@code WISSEN_ANZEIGEN} mit der Seiten-ID und ohne Inhalte; Benutzer und Rolle kommen aus der Anmeldung,
 * die IP wie beim Interceptor aus {@code X-Real-Ip}.
 */
@ApplicationScoped
public class ZugriffWissenAnzeigeProtokoll implements WissenAnzeigeProtokoll {

    private static final String REAL_IP = "X-Real-Ip";

    private final ZugriffProtokoll protokoll;
    private final SecurityIdentity identitaet;
    private final Instance<HttpServerRequest> anfrage;

    public ZugriffWissenAnzeigeProtokoll(ZugriffProtokoll protokoll, SecurityIdentity identitaet,
                                         Instance<HttpServerRequest> anfrage) {
        this.protokoll = protokoll;
        this.identitaet = identitaet;
        this.anfrage = anfrage;
    }

    @Override
    public void seiteAngezeigt(UUID seiteId) {
        protokoll.schreiben(new ZugriffEintrag(benutzer(), rolle(), ProtokollAktion.WISSEN_ANZEIGEN,
                Optional.of(seiteId), ip(), ProtokollDetails.KEINE));
    }

    private Optional<String> benutzer() {
        return identitaet.isAnonymous() ? Optional.empty() : Optional.ofNullable(identitaet.getPrincipal().getName());
    }

    private Optional<String> rolle() {
        return Rolle.ausRealmRollen(identitaet.getRoles()).map(Rolle::name);
    }

    private Optional<IpAdresse> ip() {
        if (!anfrage.isResolvable()) {
            return Optional.empty();
        }
        try {
            return IpAdresse.aus(anfrage.get().getHeader(REAL_IP));
        } catch (RuntimeException e) {
            // ausserhalb einer HTTP-Anfrage gibt es keine IP
            return Optional.empty();
        }
    }
}
