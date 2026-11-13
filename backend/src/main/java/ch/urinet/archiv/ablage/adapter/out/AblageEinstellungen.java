package ch.urinet.archiv.ablage.adapter.out;

import ch.urinet.archiv.ablage.domain.Sperrmodus;
import ch.urinet.archiv.ablage.domain.Stufenregel;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/**
 * Konfiguration der Ablage ({@code archiv.ablage.*}, design.md «Protokoll und Manifest»): Bucket-Präfix, Höchstfrist für
 * {@code archiv/} (nur Stufe A), Sperrmodus und Höchstfrist für {@code manifest/} (Standard COMPLIANCE ohne Höchstfrist)
 * Gültigkeit der vorsignierten URL (höchstens 5 Minuten, Req 14.6) und Verzeichnis für Zwischenkopien
 * ({@code archiv.ablage.zwischenablage}, im Arbeitsverzeichnis, nie {@code /tmp}). {@code exportTeil} ist die Grösse
 * eines Teils beim Multipart-Upload des Jahresexports ({@code archiv.export.teil-groesse}, Standard 8 MiB, mindestens
 * 5 MiB); so viel hält ein laufender Export im Heap.
 */
public record AblageEinstellungen(String bucketPraefix, Optional<Duration> archivHoechstfrist,
                                  Sperrmodus manifestSperrmodus, Optional<Duration> manifestHoechstfrist,
                                  Duration urlGueltigkeit, Path zwischenablage, int exportTeil) {

    public static final Duration URL_HOECHSTENS = Duration.ofMinutes(5);
    public static final int EXPORT_TEIL_STANDARD = 8 * 1024 * 1024;
    private static final int EXPORT_TEIL_MIN = 5 * 1024 * 1024;
    private static final int EXPORT_TEIL_MAX = 64 * 1024 * 1024;

    public AblageEinstellungen {
        Objects.requireNonNull(bucketPraefix, "bucketPraefix");
        Objects.requireNonNull(manifestSperrmodus, "manifestSperrmodus");
        Objects.requireNonNull(zwischenablage, "zwischenablage");
        positiv(archivHoechstfrist, "archiv.ablage.max-sperrfrist");
        positiv(manifestHoechstfrist, "archiv.ablage.manifest-max-sperrfrist");
        if (urlGueltigkeit == null || urlGueltigkeit.isNegative() || urlGueltigkeit.isZero()
                || urlGueltigkeit.compareTo(URL_HOECHSTENS) > 0) {
            throw new IllegalStateException("archiv.ablage.url-gueltigkeit muss zwischen 0 und 5 Minuten liegen (Req 14.6)");
        }
        if (exportTeil < EXPORT_TEIL_MIN || exportTeil > EXPORT_TEIL_MAX) {
            throw new IllegalStateException("archiv.export.teil-groesse muss zwischen 5 MiB und 64 MiB liegen");
        }
    }

    public AblageEinstellungen(String bucketPraefix, Optional<Duration> archivHoechstfrist,
                               Sperrmodus manifestSperrmodus, Optional<Duration> manifestHoechstfrist,
                               Duration urlGueltigkeit, Path zwischenablage) {
        this(bucketPraefix, archivHoechstfrist, manifestSperrmodus, manifestHoechstfrist, urlGueltigkeit,
                zwischenablage, EXPORT_TEIL_STANDARD);
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
