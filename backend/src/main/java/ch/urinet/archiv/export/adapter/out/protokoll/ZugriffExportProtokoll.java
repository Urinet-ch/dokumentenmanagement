package ch.urinet.archiv.export.adapter.out.protokoll;

import ch.urinet.archiv.export.application.ExportProtokoll;
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
 * Zugriffsprotokoll des Jahresexports (Spec 02, Req 7.3; design.md «Jahresexport»): die Export-ID steht an Stelle der
 * Dokument-ID. Bei Anforderung und Download kommen Benutzer und Rolle aus der Anmeldung, die IP wie beim Interceptor
 * aus {@code X-Real-Ip}; beim Abschluss im Worker Benutzer und Rolle der Anforderung, ohne IP, mit der SHA-256 des ZIP.
 */
@ApplicationScoped
public class ZugriffExportProtokoll implements ExportProtokoll {

    private static final String REAL_IP = "X-Real-Ip";

    private final ZugriffProtokoll protokoll;
    private final Instance<SecurityIdentity> identitaet;
    private final Instance<HttpServerRequest> anfrage;

    public ZugriffExportProtokoll(ZugriffProtokoll protokoll, Instance<SecurityIdentity> identitaet,
                                  Instance<HttpServerRequest> anfrage) {
        this.protokoll = protokoll;
        this.identitaet = identitaet;
        this.anfrage = anfrage;
    }

    @Override
    public void angefordert(UUID exportId) {
        SecurityIdentity id = identitaet.get();
        protokoll.schreiben(new ZugriffEintrag(Optional.ofNullable(id.getPrincipal().getName()),
                Rolle.ausRealmRollen(id.getRoles()).map(Rolle::name), ProtokollAktion.EXPORTIEREN,
                Optional.of(exportId), ip(), ProtokollDetails.KEINE));
    }

    @Override
    public void abgeschlossen(UUID exportId, String benutzer, String rolle, String sha256) {
        protokoll.schreiben(new ZugriffEintrag(Optional.of(benutzer), Optional.of(rolle), ProtokollAktion.EXPORTIEREN,
                Optional.of(exportId), Optional.empty(), new ProtokollDetails(null, sha256, null)));
    }

    @Override
    public void heruntergeladen(UUID exportId, String sha256) {
        SecurityIdentity id = identitaet.get();
        protokoll.schreiben(new ZugriffEintrag(Optional.ofNullable(id.getPrincipal().getName()),
                Rolle.ausRealmRollen(id.getRoles()).map(Rolle::name), ProtokollAktion.HERUNTERLADEN,
                Optional.of(exportId), ip(), new ProtokollDetails(null, sha256, null)));
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
