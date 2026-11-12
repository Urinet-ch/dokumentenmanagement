package ch.urinet.archiv.dokument.adapter.out.protokoll;

import ch.urinet.archiv.dokument.application.VerwerfenProtokollPort;
import ch.urinet.archiv.protokoll.application.ZugriffProtokoll;
import ch.urinet.archiv.protokoll.domain.IpAdresse;
import ch.urinet.archiv.protokoll.domain.ProtokollAktion;
import ch.urinet.archiv.protokoll.domain.ProtokollDetails;
import ch.urinet.archiv.protokoll.domain.ZugriffEintrag;
import ch.urinet.archiv.sicherheit.domain.Rolle;
import io.quarkus.security.identity.SecurityIdentity;
import io.vertx.core.http.HttpServerRequest;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;

import java.util.Optional;
import java.util.UUID;

/**
 * Schreibt {@code VERWERFEN} je Datei mit SHA-256 und Grund (Req 6.6). Benutzer und Rolle kommen aus der Anmeldung, die
 * IP wie beim Interceptor aus {@code X-Real-Ip}.
 */
@ApplicationScoped
public class ZugriffVerwerfenProtokoll implements VerwerfenProtokollPort {

    private static final String REAL_IP = "X-Real-Ip";

    private final ZugriffProtokoll protokoll;
    private final SecurityIdentity identitaet;
    private final Instance<HttpServerRequest> anfrage;

    public ZugriffVerwerfenProtokoll(ZugriffProtokoll protokoll, SecurityIdentity identitaet,
                                     Instance<HttpServerRequest> anfrage) {
        this.protokoll = protokoll;
        this.identitaet = identitaet;
        this.anfrage = anfrage;
    }

    @Override
    public void dateiVerworfen(UUID dokumentId, String sha256, String grund) {
        protokoll.schreiben(new ZugriffEintrag(benutzer(), rolle(), ProtokollAktion.VERWERFEN,
                Optional.of(dokumentId), ip(), new ProtokollDetails(grund, sha256, null)));
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
