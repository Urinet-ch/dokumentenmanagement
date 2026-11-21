package ch.urinet.archiv.benutzer.application;

import ch.urinet.archiv.mandant.domain.MandantCode;

import java.util.UUID;

public interface Vorgangssperre {

    enum Abschluss { ABGESCHLOSSEN, FEHLGESCHLAGEN, UNKLAR }

    boolean belegen(MandantCode mandant, UUID vorgangId);

    boolean gueltig(MandantCode mandant, UUID vorgangId);

    /** In derselben Transaktion wie das Protokoll {@code ANGEFRAGT}, vor jeder Wirkung in Keycloak. */
    boolean anfragen(MandantCode mandant, UUID vorgangId);

    /**
     * In derselben Transaktion wie Abschlussprotokoll und Datenbankänderungen. {@code FEHLGESCHLAGEN} ist nur
     * zulässig, wenn keine externe Wirkung mehr ausstehen kann; andernfalls bleibt die Sperre {@code UNKLAR}.
     */
    boolean abschliessen(MandantCode mandant, UUID vorgangId, Abschluss abschluss);

    boolean freigeben(MandantCode mandant, UUID vorgangId);
}
