package ch.urinet.archiv.sicherung.application;

import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.sicherung.domain.SicherungsArt;

import java.time.Instant;
import java.util.Optional;

/** Driven Port für das nur anfügende Register {@code public.sicherung_export} und seine Zeitstempel-Abfrage. */
public interface SicherungExportSpeicher {

    void festhalten(SicherungsArt art, Optional<MandantCode> mandant, String sha256);

    Optional<Instant> juengsterEintrag(SicherungsArt art, Optional<MandantCode> mandant);
}
