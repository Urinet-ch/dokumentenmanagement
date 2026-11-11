package ch.urinet.archiv.ablage.adapter.out;

import ch.urinet.archiv.ablage.domain.Sperrmodus;
import ch.urinet.archiv.ablage.domain.Stufenregel;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/**
 * Konfiguration der Ablage ({@code archiv.ablage.*}, design.md «Protokoll und Manifest»): Bucket-Präfix, Höchstfrist für
 * {@code archiv/} (nur Stufe A), Sperrmodus und Höchstfrist für {@code manifest/} (Standard COMPLIANCE ohne Höchstfrist)
 * und Gültigkeit der vorsignierten URL (höchstens 5 Minuten, Req 14.6).
 */
public record AblageEinstellungen(String bucketPraefix, Optional<Duration> archivHoechstfrist,
                                  Sperrmodus manifestSperrmodus, Optional<Duration> manifestHoechstfrist,
                                  Duration urlGueltigkeit) {

    public static final Duration URL_HOECHSTENS = Duration.ofMinutes(5);

    public AblageEinstellungen {
        Objects.requireNonNull(bucketPraefix, "bucketPraefix");
        Objects.requireNonNull(manifestSperrmodus, "manifestSperrmodus");
        positiv(archivHoechstfrist, "archiv.ablage.max-sperrfrist");
        positiv(manifestHoechstfrist, "archiv.ablage.manifest-max-sperrfrist");
        if (urlGueltigkeit == null || urlGueltigkeit.isNegative() || urlGueltigkeit.isZero()
                || urlGueltigkeit.compareTo(URL_HOECHSTENS) > 0) {
            throw new IllegalStateException("archiv.ablage.url-gueltigkeit muss zwischen 0 und 5 Minuten liegen (Req 14.6)");
        }
    }

    public Stufenregel.Ablageeinstellungen fuerStufenregel() {
        return new Stufenregel.Ablageeinstellungen(manifestSperrmodus, manifestHoechstfrist, archivHoechstfrist,
                bucketPraefix);
    }

    private static void positiv(Optional<Duration> frist, String schluessel) {
        if (frist.isPresent() && (frist.get().isNegative() || frist.get().isZero())) {
            throw new IllegalStateException(schluessel + " muss positiv sein");
        }
    }
}
