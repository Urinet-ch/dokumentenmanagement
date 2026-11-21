package ch.urinet.archiv.verarbeitung.application;

import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.verarbeitung.domain.Befund;

import java.util.List;

public interface VorgangUeberwachung {
    List<Befund> ungeklaert(MandantCode mandant);
}
