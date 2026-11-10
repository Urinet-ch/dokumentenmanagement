package ch.urinet.archiv.ablage.domain;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

/**
 * {@code retainUntil} nach design.md «Ablage», Prüfung in dieser Reihenfolge: (1) Art ohne gesetzliche Pflicht → keine
 * Sperre (Req 6.12); (2) Frist abgelaufen → keine Sperre (Req 9.8); (3) unbefristet → heute + 10 Jahre (Req 6.9);
 * (4) sonst Ende des Tages {@code aufbewahren_bis} in Europe/Zurich, als UTC. Eine Höchstfrist (Stufe A, Req 6.11)
 * begrenzt das Ergebnis; liegt es nicht in der Zukunft, wird keine Sperre gesetzt.
 */
public final class Sperrfrist {

    public static final ZoneId ZUERICH = ZoneId.of("Europe/Zurich");
    public static final int UNBEFRISTET_JAHRE = 10;

    private Sperrfrist() {
    }

    public static Optional<Instant> retainUntil(Sperrvorgabe vorgabe, Instant jetzt, Optional<Duration> hoechstfrist) {
        if (!vorgabe.gesetzlichePflicht() || vorgabe.fristAbgelaufen()) {
            return Optional.empty();
        }
        Instant bis = vorgabe.aufbewahrenBis() == null ? zehnJahre(jetzt) : tagesende(vorgabe.aufbewahrenBis());
        return begrenzt(bis, jetzt, hoechstfrist);
    }

    /**
     * Sperre eines Manifests (Req 15.4): bis zum spätesten {@code aufbewahren_bis} der enthaltenen Dateien, ohne Datei
     * mit Frist 10 Jahre (Req 6.9). Ein Manifest wird immer gesperrt.
     */
    public static Instant manifest(Optional<LocalDate> spaetestesAufbewahrenBis, Instant jetzt,
                                   Optional<Duration> hoechstfrist) {
        Instant bis = spaetestesAufbewahrenBis.map(Sperrfrist::tagesende).orElseGet(() -> zehnJahre(jetzt));
        return begrenzt(bis, jetzt, hoechstfrist)
                .orElseThrow(() -> new IllegalArgumentException("Sperrfrist des Manifests liegt nicht in der Zukunft"));
    }

    /** Ende des Tages in Europe/Zurich (Beginn des Folgetags), als Instant. */
    public static Instant tagesende(LocalDate tag) {
        return tag.plusDays(1).atStartOfDay(ZUERICH).toInstant();
    }

    private static Instant zehnJahre(Instant jetzt) {
        return jetzt.atZone(ZUERICH).plusYears(UNBEFRISTET_JAHRE).toInstant();
    }

    private static Optional<Instant> begrenzt(Instant bis, Instant jetzt, Optional<Duration> hoechstfrist) {
        Instant ergebnis = hoechstfrist.map(jetzt::plus).filter(grenze -> grenze.isBefore(bis)).orElse(bis);
        // S3 speichert Sekunden; aufrunden, damit die Sperre nie kürzer ist als berechnet
        Instant sekunden = ergebnis.truncatedTo(ChronoUnit.SECONDS);
        if (sekunden.isBefore(ergebnis)) {
            sekunden = sekunden.plusSeconds(1);
        }
        return sekunden.isAfter(jetzt) ? Optional.of(sekunden) : Optional.empty();
    }
}
