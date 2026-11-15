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
    /** Wert: Bezeichnung des Tokens als Text (für das Benutzerverzeichnis, Req 10.10). */
    public static final String NAME = "api-token-name";
    /** Wert: Webseiten-Kennung als Text, nur für Webseiten-Schlüssel. */
    public static final String WEBSEITE = "api-token-webseite";
    /** Wert: höchste Zugriffsstufe als Text, nur für Webseiten-Schlüssel. */
    public static final String HOECHSTE_STUFE = "api-token-hoechste-stufe";
    /** Wert: {@code Boolean}, ob die Absender-IP der Webseite eingeschränkt ist. */
    public static final String ABSENDER_PRUEFEN = "api-token-absender-pruefen";
    /** Wert: {@code Instant} oder {@code null}, Ablaufdatum des Webseiten-Schlüssels. */
    public static final String GUELTIG_BIS = "api-token-gueltig-bis";

    private TokenIdentitaet() {
    }
}
