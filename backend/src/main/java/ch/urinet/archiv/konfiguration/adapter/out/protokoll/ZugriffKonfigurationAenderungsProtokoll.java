package ch.urinet.archiv.konfiguration.adapter.out.protokoll;

import ch.urinet.archiv.konfiguration.application.KonfigurationAenderungsProtokoll;
import ch.urinet.archiv.konfiguration.domain.Konfiguration;
import ch.urinet.archiv.konfiguration.domain.KonfigurationQuelle;
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

import java.util.List;
import java.util.Optional;

@ApplicationScoped
public class ZugriffKonfigurationAenderungsProtokoll implements KonfigurationAenderungsProtokoll {

    private static final String REAL_IP = "X-Real-Ip";

    private final ZugriffProtokoll protokoll;
    private final SecurityIdentity identitaet;
    private final Instance<HttpServerRequest> anfrage;

    public ZugriffKonfigurationAenderungsProtokoll(ZugriffProtokoll protokoll, SecurityIdentity identitaet,
                                                    Instance<HttpServerRequest> anfrage) {
        this.protokoll = protokoll;
        this.identitaet = identitaet;
        this.anfrage = anfrage;
    }

    @Override
    public void schreiben(int version, KonfigurationQuelle quelle, String grund,
                          List<Konfiguration.Aenderung> aenderungen) {
        var details = ProtokollDetails.konfiguration(grund,
                new ProtokollDetails.Konfiguration(version, quelle.name(), aenderungen.stream()
                        .map(a -> new ProtokollDetails.Aenderung(a.pfad(), a.alt(), a.neu())).toList()));
        protokoll.schreiben(new ZugriffEintrag(benutzer(), rolle(), ProtokollAktion.KONFIGURATION_AENDERN,
                Optional.empty(), ip(), details));
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
            return Optional.empty();
        }
    }
}
