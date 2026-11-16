package ch.urinet.archiv.integration.adapter.in.rest.webseite;

import ch.urinet.archiv.integration.application.webseite.WebseitenGrenzen;
import ch.urinet.archiv.integration.domain.webseite.Grenzklasse;
import ch.urinet.archiv.sicherheit.application.WebseitenZugang;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ResourceInfo;
import org.jboss.resteasy.reactive.server.ServerRequestFilter;

import java.lang.reflect.Method;

/**
 * Wendet bei Endpunkten mit {@code @WebseitenZugang} die Grenzen je Schlüssel an (Spec 03, 11.11, Req 15.1). Läuft nach
 * {@code ZugangFilter} (Scope und Mandant sind geprüft, der Schlüssel ist gültig) und vor {@link LeseZugriffFilter}, damit
 * auch Anfragen mit ungültigen Headern zählen. Gezählt wird je Schlüssel-ID (Principal {@code token:<id>}), nie je Wert von
 * {@code X-Archiv-Client} oder {@code X-Archiv-Zugriff}. Interne Endpunkte ohne {@code @WebseitenZugang} berührt er nicht.
 * Der öffentliche Chat zählt hier als Lese-Anfrage mit; seine eigenen Grenzen ({@code OeffentlicheGrenzen}) bleiben.
 */
public class WebseitenGrenzenFilter {

    private final SecurityIdentity identitaet;
    private final WebseitenGrenzen grenzen;

    public WebseitenGrenzenFilter(SecurityIdentity identitaet, WebseitenGrenzen grenzen) {
        this.identitaet = identitaet;
        this.grenzen = grenzen;
    }

    @ServerRequestFilter(priority = Priorities.USER + 50)
    public void begrenzen(ResourceInfo ressource) {
        Method methode = ressource.getResourceMethod();
        WebseitenZugang zugang = methode == null ? null : methode.getAnnotation(WebseitenZugang.class);
        if (zugang == null || identitaet.isAnonymous() || identitaet.getPrincipal().getName() == null) {
            return;
        }
        WebseitenGrenze stufung = methode.getAnnotation(WebseitenGrenze.class);
        Grenzklasse klasse = stufung != null ? stufung.value()
                : zugang.value() == WebseitenZugang.Art.LESEN ? Grenzklasse.LESEN : Grenzklasse.ERFASSUNG;
        grenzen.pruefen(identitaet.getPrincipal().getName(), klasse);
    }
}
