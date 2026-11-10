package ch.urinet.archiv.sicherheit.application;

/**
 * Attribute der Identität eines API-Tokens (Req 2.5). Der Principal heisst {@code token:<id>}
 * ({@code ApiToken.principal}); den Mandanten trägt das Attribut {@code mandant}, das {@code MandantAusAnmeldung}
 * vor dem JWT-Claim liest. Nur der Anmelde-Mechanismus für API-Tokens setzt {@link #SCOPE}; daran erkennt der Filter
 * eine Token-Identität, nicht an ihren Rollen.
 */
public final class TokenIdentitaet {

    /** Wert: {@code ApiTokenScope}. */
    public static final String SCOPE = "api-token-scope";
    /** Wert: Mandant-Code als Text. */
    public static final String MANDANT = "mandant";

    private TokenIdentitaet() {
    }
}
