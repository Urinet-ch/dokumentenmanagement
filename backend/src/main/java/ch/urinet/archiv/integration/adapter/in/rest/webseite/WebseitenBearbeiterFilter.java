package ch.urinet.archiv.integration.adapter.in.rest.webseite;

import ch.urinet.archiv.integration.application.webseite.WebseitenErfassung;
import ch.urinet.archiv.protokoll.application.BenutzerVerzeichnis;
import ch.urinet.archiv.protokoll.domain.VerzeichnisEintrag;
import ch.urinet.archiv.protokoll.application.Protokolliert;
import ch.urinet.archiv.sicherheit.application.TokenIdentitaet;
import ch.urinet.archiv.sicherheit.application.WebseitenZugang;
import ch.urinet.archiv.sicherheit.domain.Rolle;
import io.quarkus.security.identity.SecurityIdentity;
import io.vertx.core.http.HttpServerRequest;
import jakarta.enterprise.inject.Instance;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ResourceInfo;
import org.jboss.resteasy.reactive.server.ServerRequestFilter;

import java.lang.reflect.Method;
import java.util.Optional;

/** Prüft vor jeder protokollierten Erfassungsaktion die gemeldete Kennung des Webseiten-Bearbeiters. */
public class WebseitenBearbeiterFilter {

    private final SecurityIdentity identitaet;
    private final BenutzerVerzeichnis verzeichnis;
    private final Instance<HttpServerRequest> anfrage;

    public WebseitenBearbeiterFilter(SecurityIdentity identitaet, BenutzerVerzeichnis verzeichnis,
                                     Instance<HttpServerRequest> anfrage) {
        this.identitaet = identitaet;
        this.verzeichnis = verzeichnis;
        this.anfrage = anfrage;
    }

    @ServerRequestFilter(priority = Priorities.USER + 60)
    public void pruefen(ResourceInfo ressource) {
        Method methode = ressource.getResourceMethod();
        WebseitenZugang zugang = methode == null ? null : methode.getAnnotation(WebseitenZugang.class);
        if (zugang == null || zugang.value() != WebseitenZugang.Art.ERFASSUNG
                || methode.getAnnotation(Protokolliert.class) == null
                && !methode.isAnnotationPresent(BearbeiterErforderlich.class)) {
            return;
        }
        String webseite = identitaet.getAttribute(TokenIdentitaet.WEBSEITE);
        String kennung = anfrage.isResolvable() ? anfrage.get().getHeader("X-Archiv-Bearbeiter") : null;
        if (webseite == null || !WebseitenErfassung.bearbeiterGueltig(kennung)) {
            throw new ch.urinet.archiv.gemeinsam.domain.FachFehler(
                    ch.urinet.archiv.gemeinsam.domain.Fehlercode.ANFRAGE_UNGUELTIG);
        }
        Optional<VerzeichnisEintrag> eintrag = verzeichnis.letzter(Optional.empty(), webseite + "/" + kennung);
        if (eintrag.filter(e -> !e.beendet())
                .filter(e -> e.rolle().filter(Rolle.GESCHAEFTSSTELLE.name()::equals).isPresent()).isEmpty()) {
            throw new ch.urinet.archiv.gemeinsam.domain.FachFehler(
                    ch.urinet.archiv.gemeinsam.domain.Fehlercode.ANFRAGE_UNGUELTIG);
        }
    }
}
