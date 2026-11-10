package ch.urinet.archiv.dokumentart.adapter.out.protokoll;

import ch.urinet.archiv.dokumentart.application.RegelProtokollPort;
import ch.urinet.archiv.dokumentart.domain.Dokumentart;
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

/**
 * Schreibt {@code REGEL_AENDERN} ohne Dokument, mit Code der Art und alter und neuer Regel in {@code details}
 * (Req 9.10). Benutzer und Rolle kommen aus der Anmeldung, die IP wie beim Interceptor aus {@code X-Real-Ip}.
 */
@ApplicationScoped
public class ZugriffRegelProtokoll implements RegelProtokollPort {

    private static final String REAL_IP = "X-Real-Ip";

    private final ZugriffProtokoll protokoll;
    private final SecurityIdentity identitaet;
    private final Instance<HttpServerRequest> anfrage;

    public ZugriffRegelProtokoll(ZugriffProtokoll protokoll, SecurityIdentity identitaet,
                                 Instance<HttpServerRequest> anfrage) {
        this.protokoll = protokoll;
        this.identitaet = identitaet;
        this.anfrage = anfrage;
    }

    @Override
    public void regelGeaendert(Dokumentart bisher, Dokumentart neu) {
        var aenderung = new ProtokollDetails.RegelAenderung(neu.code(), regel(bisher), regel(neu));
        protokoll.schreiben(new ZugriffEintrag(benutzer(), rolle(), ProtokollAktion.REGEL_AENDERN, Optional.empty(),
                ip(), ProtokollDetails.regelAenderung(aenderung)));
    }

    private static ProtokollDetails.Regel regel(Dokumentart art) {
        var ab = art.aufbewahrung().ab();
        return new ProtokollDetails.Regel(art.aufbewahrung().jahre(), ab == null ? null : ab.name(),
                art.gesetzlichePflicht());
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
