package ch.urinet.archiv.sicherung.domain;

import java.util.Arrays;
import java.util.Optional;

/** Arten von Exporten, deren SHA-256 im öffentlichen Sicherungsregister festgehalten wird. */
public enum SicherungsArt {
    DB("db", false),
    KEYCLOAK("keycloak", false),
    REALM("realm", false),
    SCHLUESSEL("schluessel", false),
    BUCKET("bucket", true),
    INVENTAR("inventar", true);

    private final String code;
    private final boolean mandantPflicht;

    SicherungsArt(String code, boolean mandantPflicht) {
        this.code = code;
        this.mandantPflicht = mandantPflicht;
    }

    public String code() {
        return code;
    }

    public boolean mandantPflicht() {
        return mandantPflicht;
    }

    public static Optional<SicherungsArt> aus(String code) {
        return Arrays.stream(values()).filter(art -> art.code.equals(code)).findFirst();
    }
}
