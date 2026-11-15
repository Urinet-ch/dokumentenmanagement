package ch.urinet.archiv.sicherung.application;

import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.sicherung.domain.SicherungsArt;

import java.util.Optional;

/** Driven Port für die nur anfügenden Einträge in {@code public.sicherung_export}. */
public interface SicherungExportSpeicher {

    void festhalten(SicherungsArt art, Optional<MandantCode> mandant, String sha256);
}
