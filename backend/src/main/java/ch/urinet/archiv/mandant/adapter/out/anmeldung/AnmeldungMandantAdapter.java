package ch.urinet.archiv.mandant.adapter.out.anmeldung;

import ch.urinet.archiv.mandant.application.MandantAusAnmeldung;
import ch.urinet.archiv.mandant.domain.MandantCode;
import io.quarkus.arc.Arc;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.json.JsonString;
import org.eclipse.microprofile.jwt.JsonWebToken;

/**
 * Mandant aus der verifizierten Anmeldung (tech.md Regel 1): zuerst das Attribut {@code mandant} der Identität
 * (API-Token, Aufgabe 1.5), sonst der Claim {@code mandant} des Keycloak-Tokens. Gültig ist nur ein einzelner Text, der
 * ein gültiger Mandant-Code ist; eine Liste, ein leerer oder ein anderer Wert gilt als ungültig (Req 1.3).
 */
@ApplicationScoped
public class AnmeldungMandantAdapter implements MandantAusAnmeldung {

    public static final String MANDANT = "mandant";

    private final Instance<SecurityIdentity> identitaet;

    public AnmeldungMandantAdapter(Instance<SecurityIdentity> identitaet) {
        this.identitaet = identitaet;
    }

    @Override
    public Ergebnis ermitteln() {
        if (!Arc.container().requestContext().isActive()) {
            return new Anonym();
        }
        SecurityIdentity id = identitaet.get();
        if (id == null || id.isAnonymous()) {
            return new Anonym();
        }
        Object attribut = id.getAttribute(MANDANT);
        Object claim = id.getPrincipal() instanceof JsonWebToken jwt ? jwt.getClaim(MANDANT) : null;
        return auswerten(attribut != null ? attribut : claim);
    }

    static Ergebnis auswerten(Object wert) {
        String text = switch (wert) {
            case String s -> s;
            case JsonString js -> js.getString();
            case null, default -> null;
        };
        if (text == null) {
            return new Ungueltig();
        }
        try {
            return new Gueltig(new MandantCode(text));
        } catch (IllegalArgumentException e) {
            return new Ungueltig();
        }
    }
}
