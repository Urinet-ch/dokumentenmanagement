package ch.urinet.archiv.ablage.domain;

import java.util.Objects;
import java.util.UUID;

/** Schlüssel der Originaldatei eines CSV-Imports (Req 13.8, design.md «Ablage»): {@code importe/<laufId>.csv}. */
public final class Importdatei {

    public static final String PRAEFIX = "importe";

    private Importdatei() {
    }

    public static String schluessel(UUID laufId) {
        return PRAEFIX + "/" + Objects.requireNonNull(laufId, "laufId") + ".csv";
    }
}
