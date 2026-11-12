package ch.urinet.archiv.ki.application;

import ch.urinet.archiv.ki.domain.QrDaten;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Driven Port zum Lesen des Swiss QR Codes aus Seitenbildern (Req 7.11, design.md «SwissQrLeser»), vor dem KI-Aufruf.
 * Adapter mit ZXing; der erste gültige Code ({@code SPC}) gewinnt. Ein unlesbares Bild ergibt keinen Code, keinen
 * Fehler.
 */
public interface QrLeserPort {

    Optional<QrDaten> lesen(List<Path> seiten);
}
