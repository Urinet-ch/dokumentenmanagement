package ch.urinet.archiv.mandant.application;

import ch.urinet.archiv.mandant.domain.DokumentartVorlage;
import ch.urinet.archiv.mandant.domain.MandantCode;

import java.util.List;

/** Driven Port: Seed der Dokumentarten eines Mandanten ({@code seed/<code>/dokumentarten.json}). */
public interface DokumentartSeed {

    /** Bricht mit {@link MandantFehler} ab, wenn es für den Mandanten keinen Seed gibt. */
    List<DokumentartVorlage> lesen(MandantCode code);
}
