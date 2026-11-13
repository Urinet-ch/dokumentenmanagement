package ch.urinet.archiv.ki.application;

import ch.urinet.archiv.ki.domain.QrDaten;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Driven Port zum Lesen des Swiss QR Codes aus Seitenbildern (Req 7.11, design.md «SwissQrLeser»), vor dem KI-Aufruf.
 * Adapter mit ZXing; der erste gültige Code ({@code SPC}) gewinnt. Ein unlesbares Bild ergibt keinen Code, keinen
 * Fehler; eine Seite über {@code archiv.ki.render-pixel-max} lässt der Adapter aus (Review PR #120).
 */
public interface QrLeserPort {

    /** @param dokument nur für das Log */
    Optional<QrDaten> lesen(UUID dokument, List<Path> seiten);
}
