package ch.urinet.archiv.dokument.adapter.out.protokoll;

import ch.urinet.archiv.dokument.application.SperrKonfliktProtokollPort;
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

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

/**
 * Schreibt {@code SPERRE_KONFLIKT} mit der bisherigen und der neu berechneten Frist (Req 9.7). Benutzer und Rolle kommen
 * aus der Anmeldung der auslösenden Änderung, die IP wie beim Interceptor aus {@code X-Real-Ip}.
 */
@ApplicationScoped
public class ZugriffSperrKonfliktProtokoll implements SperrKonfliktProtokollPort {

    private static final String REAL_IP = "X-Real-Ip";

    private final ZugriffProtokoll protokoll;
    private final SecurityIdentity identitaet;
    private final Instance<HttpServerRequest> anfrage;

    public ZugriffSperrKonfliktProtokoll(ZugriffProtokoll protokoll, SecurityIdentity identitaet,
                                         Instance<HttpServerRequest> anfrage) {
        this.protokoll = protokoll;
        this.identitaet = identitaet;
        this.anfrage = anfrage;
    }

    @Override
    public void sperreKonflikt(UUID dokumentId, LocalDate bisher, LocalDate neu) {
        protokoll.schreiben(new ZugriffEintrag(benutzer(), rolle(), ProtokollAktion.SPERRE_KONFLIKT,
                Optional.of(dokumentId), ip(), ProtokollDetails.fristKonflikt(
                        new ProtokollDetails.FristKonflikt(bisher, neu))));
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
