package ch.urinet.archiv.sicherheit.adapter.in.rest;

import ch.urinet.archiv.mandant.application.MandantAufloesung;
import ch.urinet.archiv.mandant.application.MandantVerweigert;
import ch.urinet.archiv.sicherheit.application.AlleArchivRollen;
import ch.urinet.archiv.sicherheit.application.Berechtigung;
import ch.urinet.archiv.sicherheit.application.ErfordertAktion;
import ch.urinet.archiv.sicherheit.application.Oeffentlich;
import ch.urinet.archiv.sicherheit.domain.Benutzer;
import ch.urinet.archiv.sicherheit.domain.Rolle;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.ws.rs.container.ResourceInfo;
import org.jboss.resteasy.reactive.server.ServerRequestFilter;

import java.lang.reflect.Method;
import java.util.Optional;

/**
 * Prüft jede Anfrage an einen Endpunkt, bevor Daten gelesen werden (Req 1.3, 2.2, 2.6): gültiger, aktiver Mandant aus
 * dem Token, genau eine Archiv-Rolle und – bei {@link ErfordertAktion} – das Recht der Aktion nach der Rechtematrix.
 * Ein Endpunkt ohne Erklärung ({@link ErfordertAktion}, {@link AlleArchivRollen}, {@link Oeffentlich}) antwortet 403
 * (fail closed); {@code EndpunktWaechterTest} verlangt die Erklärung schon im Build.
 */
public class ZugangFilter {

    private final SecurityIdentity identitaet;
    private final MandantAufloesung aufloesung;
    private final Berechtigung berechtigung;

    public ZugangFilter(SecurityIdentity identitaet, MandantAufloesung aufloesung, Berechtigung berechtigung) {
        this.identitaet = identitaet;
        this.aufloesung = aufloesung;
        this.berechtigung = berechtigung;
    }

    @ServerRequestFilter
    public void pruefen(ResourceInfo ressource) {
        Method methode = ressource.getResourceMethod();
        if (methode == null || methode.isAnnotationPresent(Oeffentlich.class)) {
            return;
        }
        Optional<String> benutzerId = identitaet.isAnonymous()
                ? Optional.empty() : Optional.of(identitaet.getPrincipal().getName());
        try {
            aufloesung.pruefen();
        } catch (MandantVerweigert e) {
            berechtigung.ohneZugang(benutzerId);
        }
        Optional<Rolle> rolle = Rolle.ausRealmRollen(identitaet.getRoles());
        if (rolle.isEmpty() || benutzerId.isEmpty()) {
            berechtigung.ohneZugang(benutzerId);
            return;
        }
        ErfordertAktion aktion = methode.getAnnotation(ErfordertAktion.class);
        if (aktion != null) {
            berechtigung.aktion(new Benutzer(benutzerId.get(), rolle.get()), aktion.value());
        } else if (!methode.isAnnotationPresent(AlleArchivRollen.class)) {
            berechtigung.ohneZugang(benutzerId);
        }
    }
}
