package ch.urinet.archiv.integration.adapter.in.rest.webseite;

import ch.urinet.archiv.gemeinsam.domain.FachFehler;
import ch.urinet.archiv.gemeinsam.domain.Fehlercode;
import ch.urinet.archiv.integration.domain.webseite.Lesezugriff;
import ch.urinet.archiv.integration.domain.webseite.Zugriffsstufe;
import ch.urinet.archiv.sicherheit.application.Berechtigung;
import ch.urinet.archiv.sicherheit.application.TokenIdentitaet;
import ch.urinet.archiv.sicherheit.application.WebseitenZugang;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ResourceInfo;
import org.jboss.resteasy.reactive.server.ServerRequestFilter;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Liest bei Endpunkten mit {@code @WebseitenZugang(LESEN)} die Header {@code X-Archiv-Zugriff} und
 * {@code X-Archiv-Client} und legt den {@link Lesezugriff} in {@link LeseAnfrage} (Spec 03, Aufgabe 11.1, Req 3.7, 5.3,
 * 5.9). Läuft nach {@code ZugangFilter}, der Scope und Mandant schon geprüft hat; Webseiten-Daten der Identität
 * fehlen nie, fehlen sie doch, gilt 403 {@code NICHT_ERLAUBT} (fail closed).
 * <ul>
 * <li>{@code X-Archiv-Zugriff} fehlt: {@code oeffentlich}; unbekannter, leerer oder mehrfacher Wert (auch andere
 * Schreibweise): 400 {@code ANFRAGE_UNGUELTIG}, ohne den Wert zu nennen.</li>
 * <li>Gemeldete Stufe über der höchsten Stufe des Schlüssels: 403 {@code NICHT_ERLAUBT}, im Zugriffsprotokoll als
 * {@code VERWEIGERT}; die Anfrage wird nie auf die Stufe des Schlüssels gekappt, damit ein Fehler der Webseite nicht
 * unbemerkt weniger Daten liefert.</li>
 * <li>{@code X-Archiv-Client} fehlt: kein Hash (nur der Chat verlangt ihn, Aufgabe 11.3); sonst genau 64 Hexziffern
 * in Kleinbuchstaben (SHA-256), andernfalls 400. Der Hash gelangt nie ins Protokoll.</li>
 * </ul>
 * Nicht beachtet im Änderungs-Feed (Aufgabe 11.9, {@link FeedZugriff}): dort gilt die Stufe des Schlüssels; der Feed
 * liest die Header nicht.
 */
public class LeseZugriffFilter {

    static final String ZUGRIFF = "X-Archiv-Zugriff";
    static final String CLIENT = "X-Archiv-Client";
    private static final Pattern HASH = Pattern.compile("^[0-9a-f]{64}$");

    private final SecurityIdentity identitaet;
    private final Berechtigung berechtigung;
    private final LeseAnfrage anfrage;

    public LeseZugriffFilter(SecurityIdentity identitaet, Berechtigung berechtigung, LeseAnfrage anfrage) {
        this.identitaet = identitaet;
        this.berechtigung = berechtigung;
        this.anfrage = anfrage;
    }

    @ServerRequestFilter(priority = Priorities.USER + 100)
    public void lesen(ResourceInfo ressource, ContainerRequestContext kontext) {
        Method methode = ressource.getResourceMethod();
        WebseitenZugang zugang = methode == null ? null : methode.getAnnotation(WebseitenZugang.class);
        if (zugang == null || zugang.value() != WebseitenZugang.Art.LESEN) {
            return;
        }
        Optional<String> benutzer = identitaet.isAnonymous() ? Optional.empty()
                : Optional.ofNullable(identitaet.getPrincipal().getName());
        Optional<String> webseite = Optional.ofNullable(identitaet.getAttribute(TokenIdentitaet.WEBSEITE))
                .map(Object::toString);
        Optional<Zugriffsstufe> hoechste = Optional.ofNullable(identitaet.getAttribute(TokenIdentitaet.HOECHSTE_STUFE))
                .flatMap(w -> Zugriffsstufe.ausCode(w.toString()));
        if (webseite.isEmpty() || hoechste.isEmpty()) {
            berechtigung.ohneZugang(benutzer, Optional.empty(), Optional.empty());
            return;
        }
        if (methode.isAnnotationPresent(FeedZugriff.class)) {
            // Feed: die Stufe des Schlüssels, die Header der Webseite werden nicht gelesen (Req 14.1)
            anfrage.setzen(new Lesezugriff(webseite.get(), hoechste.get(), hoechste.get(), Optional.empty()));
            return;
        }
        Zugriffsstufe gemeldet = einzelwert(kontext, ZUGRIFF)
                .map(w -> Zugriffsstufe.ausCode(w).orElseThrow(() -> new FachFehler(Fehlercode.ANFRAGE_UNGUELTIG)))
                .orElse(Zugriffsstufe.OEFFENTLICH);
        if (gemeldet.hoeherAls(hoechste.get())) {
            berechtigung.ohneZugang(benutzer, Optional.empty(), Optional.empty());
        }
        Optional<String> hash = einzelwert(kontext, CLIENT).map(w -> {
            if (!HASH.matcher(w).matches()) {
                throw new FachFehler(Fehlercode.ANFRAGE_UNGUELTIG);
            }
            return w;
        });
        anfrage.setzen(new Lesezugriff(webseite.get(), hoechste.get(), gemeldet, hash));
    }

    /** Der eine Wert des Headers; mehrere Header oder Werte mit Komma sind ungültig (400), kein Header ist leer. */
    private static Optional<String> einzelwert(ContainerRequestContext kontext, String name) {
        List<String> werte = kontext.getHeaders().get(name);
        if (werte == null || werte.isEmpty()) {
            return Optional.empty();
        }
        if (werte.size() > 1 || werte.getFirst().isEmpty()) {
            throw new FachFehler(Fehlercode.ANFRAGE_UNGUELTIG);
        }
        return Optional.of(werte.getFirst());
    }
}
