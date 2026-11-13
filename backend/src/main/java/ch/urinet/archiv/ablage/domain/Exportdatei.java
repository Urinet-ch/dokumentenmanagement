package ch.urinet.archiv.ablage.domain;

import java.util.Objects;
import java.util.UUID;

/**
 * Schlüssel des ZIP eines Jahresexports (Spec 02, design.md «Jahresexport»): {@code exporte/<exportId>.zip}, ohne
 * Sperre; der Lebenszyklus des Buckets entfernt alles unter {@code exporte/} nach 7 Tagen.
 */
public final class Exportdatei {

    public static final String PRAEFIX = "exporte/";
    /** Tage bis der Lebenszyklus ein Export-ZIP entfernt (design.md «Jahresexport»). */
    public static final int LEBENSZYKLUS_TAGE = 7;

    private Exportdatei() {
    }

    public static String schluessel(UUID exportId) {
        return PRAEFIX + Objects.requireNonNull(exportId, "exportId") + ".zip";
    }
}
