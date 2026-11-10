package ch.urinet.archiv.sicherheit.adapter.in.rest;

import ch.urinet.archiv.mandant.application.MandantVerweigert;
import ch.urinet.archiv.sicherheit.application.Verweigerung;
import jakarta.ws.rs.core.Response;
import org.jboss.resteasy.reactive.server.ServerExceptionMapper;

/** Verweigerungen als HTTP-Status ohne Details (Problem Details mit Code folgen mit Aufgabe 1.6). */
public class VerweigerungAbbildung {

    @ServerExceptionMapper
    public Response verweigert(Verweigerung verweigerung) {
        return Response.status(verweigerung.grund() == Verweigerung.Grund.NICHT_GEFUNDEN
                ? Response.Status.NOT_FOUND : Response.Status.FORBIDDEN).build();
    }

    @ServerExceptionMapper
    public Response ohneMandant(MandantVerweigert verweigert) {
        return Response.status(Response.Status.FORBIDDEN).build();
    }
}
