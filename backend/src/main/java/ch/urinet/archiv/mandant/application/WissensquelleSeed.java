package ch.urinet.archiv.mandant.application;

import ch.urinet.archiv.mandant.domain.DokumentartVorlage;

/** Driven Port: mandantenunabhängiger Seed der Dokumentart {@code WISSENSQUELLE}. */
public interface WissensquelleSeed {

    DokumentartVorlage lesen();
}
