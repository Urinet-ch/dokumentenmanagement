package ch.urinet.archiv.wissen.adapter.out.protokoll;

import ch.urinet.archiv.protokoll.application.ZugriffProtokoll;
import ch.urinet.archiv.protokoll.domain.IpAdresse;
import ch.urinet.archiv.protokoll.domain.ProtokollAktion;
import ch.urinet.archiv.protokoll.domain.ProtokollDetails;
import ch.urinet.archiv.protokoll.domain.ZugriffEintrag;
import ch.urinet.archiv.sicherheit.domain.Rolle;
import ch.urinet.archiv.wissen.application.WissenExportProtokoll;
import io.quarkus.security.identity.SecurityIdentity;
import io.vertx.core.http.HttpServerRequest;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.ContextNotActiveException;
import jakarta.enterprise.inject.Instance;

import java.util.Optional;
import java.util.UUID;

@ApplicationScoped
public class ZugriffWissenExportProtokoll implements WissenExportProtokoll {
    private final ZugriffProtokoll protokoll;
    private final Instance<SecurityIdentity> identitaet;
    private final Instance<HttpServerRequest> anfrage;

    public ZugriffWissenExportProtokoll(ZugriffProtokoll protokoll, Instance<SecurityIdentity> identitaet,
                                       Instance<HttpServerRequest> anfrage) {
        this.protokoll = protokoll;
        this.identitaet = identitaet;
        this.anfrage = anfrage;
    }

    @Override
    public void angefordert(UUID exportId) {
        schreiben(exportId, null);
    }

    @Override
    public void abgeschlossen(UUID exportId, String benutzer, String rolle, String sha256) {
        protokoll.schreiben(new ZugriffEintrag(Optional.of(benutzer), Optional.of(rolle), ProtokollAktion.EXPORTIEREN,
                Optional.of(exportId), Optional.empty(), new ProtokollDetails(null, sha256, null)));
    }

    @Override
    public void heruntergeladen(UUID exportId, String sha256) {
        schreiben(exportId, sha256);
    }

    private void schreiben(UUID exportId, String sha256) {
        SecurityIdentity id = identitaet.get();
        Optional<IpAdresse> ip = Optional.empty();
        if (anfrage.isResolvable()) {
            try {
                ip = IpAdresse.aus(anfrage.get().getHeader("X-Real-Ip"));
            } catch (ContextNotActiveException e) {
                ip = Optional.empty();
            }
        }
        protokoll.schreiben(new ZugriffEintrag(Optional.of(id.getPrincipal().getName()),
                Rolle.ausRealmRollen(id.getRoles()).map(Rolle::name), ProtokollAktion.EXPORTIEREN,
                Optional.of(exportId), ip, new ProtokollDetails(null, sha256, null)));
    }
}
