package ch.urinet.archiv.integration.domain.webseite;

import java.util.Arrays;
import java.util.Optional;

/** Sortierung der Liste; der Code steht im Parameter {@code sortierung}. */
public enum WebseiteSortierung {
    DATUM_AB("datum_ab"), DATUM_AUF("datum_auf"), TITEL("titel");

    private final String code;

    WebseiteSortierung(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    public static Optional<WebseiteSortierung> ausCode(String code) {
        return Arrays.stream(values()).filter(s -> s.code.equals(code)).findFirst();
    }
}
