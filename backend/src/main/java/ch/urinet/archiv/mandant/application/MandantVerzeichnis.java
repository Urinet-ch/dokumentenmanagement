package ch.urinet.archiv.mandant.application;

import ch.urinet.archiv.mandant.domain.MandantCode;

import java.util.Optional;

/**
 * Driven Port: Schema eines aktiven Mandanten aus {@code public.mandant}; leer, wenn der Mandant fehlt oder inaktiv
 * ist. Ohne Cache, damit ein deaktivierter Mandant sofort gesperrt ist.
 */
public interface MandantVerzeichnis {

    Optional<String> aktivesSchema(MandantCode code);

    /**
     * Schema eines eingetragenen, noch nie aktivierten Mandanten (Provisionierung, Req 1.5); leer für jeden anderen
     * Zustand. Standard leer, damit ein Verzeichnis ohne diese Angabe fail closed bleibt.
     */
    default Optional<String> schemaInProvisionierung(MandantCode code) {
        return Optional.empty();
    }
}
