package ch.urinet.archiv.benutzer.application;

import ch.urinet.archiv.mandant.domain.MandantCode;

import java.util.UUID;

public interface Vorgangssperre {

    boolean belegen(MandantCode mandant, UUID vorgangId);

    boolean gueltig(MandantCode mandant, UUID vorgangId);

    boolean freigeben(MandantCode mandant, UUID vorgangId);
}
