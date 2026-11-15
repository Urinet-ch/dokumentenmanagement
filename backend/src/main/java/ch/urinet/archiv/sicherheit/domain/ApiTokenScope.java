package ch.urinet.archiv.sicherheit.domain;

import java.util.Arrays;
import java.util.Optional;

/**
 * Scope eines API-Tokens (Req 2.5): {@code generator} erreicht nur {@code POST /dokumente/generator}, {@code import} nur
 * {@code POST /dokumente}. Ein Token hat keine Archiv-Rolle; seine Identität trägt nur die Scope-Rolle
 * ({@link #rolle()}), damit {@code @RolesAllowed} den Scope-Endpunkt zulässt. Den Scope prüft der Filter zusätzlich.
 */
public enum ApiTokenScope {
    GENERATOR, IMPORT, WEBSEITE_LESEN, WEBSEITE_ERFASSUNG;

    /** Rolle der Token-Identität für {@code @RolesAllowed}; keine Realm-Rolle in Keycloak. */
    public static final String ROLLE_GENERATOR = "api-token:generator";
    public static final String ROLLE_IMPORT = "api-token:import";
    public static final String ROLLE_WEBSEITE_LESEN = "api-token:webseite-lesen";
    public static final String ROLLE_WEBSEITE_ERFASSUNG = "api-token:webseite-erfassung";

    /** Wert in {@code api_client.scope} und in der Schnittstelle, z. B. {@code import}. */
    public String code() {
        return switch (this) {
            case GENERATOR -> "generator";
            case IMPORT -> "import";
            case WEBSEITE_LESEN -> "webseite-lesen";
            case WEBSEITE_ERFASSUNG -> "webseite-erfassung";
        };
    }

    public String rolle() {
        return "api-token:" + code();
    }

    public boolean webseitenSchluessel() {
        return this == WEBSEITE_LESEN || this == WEBSEITE_ERFASSUNG;
    }

    public boolean istErfassung() {
        return this == WEBSEITE_ERFASSUNG;
    }

    /** Nur die Kleinschreibung wie in {@code api_client.scope}; sonst leer. */
    public static Optional<ApiTokenScope> ausCode(String code) {
        return Arrays.stream(values()).filter(s -> s.code().equals(code)).findFirst();
    }
}
