package ch.urinet.archiv.suche.domain;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.IntStream;

/** Begrenzte Abwertung älterer Suchtreffer relativ zur Spannweite der Reranker-Scores. */
public final class Aktualitaetsranking {

    private Aktualitaetsranking() {
    }

    public static List<UUID> ordnen(List<UUID> ids, List<Double> scores, Map<UUID, LocalDate> daten,
                                    Aktualitaetsregel regel, Clock uhr) {
        Objects.requireNonNull(ids, "ids");
        Objects.requireNonNull(scores, "scores");
        Objects.requireNonNull(daten, "daten");
        Objects.requireNonNull(regel, "regel");
        Objects.requireNonNull(uhr, "uhr");
        if (ids.size() != scores.size()) {
            throw new IllegalArgumentException("je Treffer ein Reranker-Score");
        }
        if (!regel.aktiviert() || ids.size() < 2) {
            return List.copyOf(ids);
        }
        if (scores.stream().anyMatch(score -> score == null || !Double.isFinite(score))) {
            throw new IllegalArgumentException("Reranker-Scores müssen endlich sein");
        }

        double minimum = scores.stream().mapToDouble(Double::doubleValue).min().orElseThrow();
        double maximum = scores.stream().mapToDouble(Double::doubleValue).max().orElseThrow();
        double spannweite = maximum - minimum;
        LocalDate heute = LocalDate.now(uhr.withZone(ZoneOffset.UTC));
        List<Bewertung> bewertungen = new ArrayList<>(ids.size());
        IntStream.range(0, ids.size()).forEach(index -> {
            UUID id = ids.get(index);
            double score = scores.get(index);
            double normiert = spannweite == 0 ? 0 : (score - minimum) / spannweite;
            LocalDate datum = daten.get(id);
            double korrektur = datum == null ? 0 : abwertung(datum, heute, regel);
            bewertungen.add(new Bewertung(id, normiert - korrektur, index));
        });
        bewertungen.sort(Comparator.comparingDouble(Bewertung::score).reversed()
                .thenComparingInt(Bewertung::index));
        return bewertungen.stream().map(Bewertung::id).toList();
    }

    private static double abwertung(LocalDate datum, LocalDate heute, Aktualitaetsregel regel) {
        long alterTage = Math.max(0, ChronoUnit.DAYS.between(datum, heute));
        double verbleibenderAnteil = Math.pow(0.5, (double) alterTage / regel.halbwertzeitTage());
        return regel.faktor() * (1 - verbleibenderAnteil);
    }

    private record Bewertung(UUID id, double score, int index) {
    }
}
