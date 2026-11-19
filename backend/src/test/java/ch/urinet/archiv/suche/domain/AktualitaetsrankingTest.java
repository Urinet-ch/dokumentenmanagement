package ch.urinet.archiv.suche.domain;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AktualitaetsrankingTest {

    private static final Clock UHR = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
    private static final Aktualitaetsregel REGEL = new Aktualitaetsregel(true, 0.05, 365);
    private static final UUID ALT = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID NEU = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID OHNE_DATUM = UUID.fromString("00000000-0000-0000-0000-000000000003");

    @Test
    void beiGleicherRelevanzKommtDerNeuereTrefferZuerst() {
        assertThat(Aktualitaetsranking.ordnen(List.of(ALT, NEU), List.of(0.7, 0.7),
                Map.of(ALT, LocalDate.of(2000, 1, 1), NEU, LocalDate.of(2020, 1, 1)), REGEL, UHR))
                .containsExactly(NEU, ALT);
    }

    @Test
    void einKlarRelevantererAlterTrefferBleibtVorEinemSchwachenNeuenTreffer() {
        assertThat(Aktualitaetsranking.ordnen(List.of(ALT, NEU), List.of(0.95, 0.1),
                Map.of(ALT, LocalDate.of(2000, 1, 1), NEU, LocalDate.of(2025, 1, 1)), REGEL, UHR))
                .containsExactly(ALT, NEU);
    }

    @Test
    void trefferOhneDatumErhaltenKeineAbwertung() {
        assertThat(Aktualitaetsranking.ordnen(List.of(OHNE_DATUM, ALT, NEU), List.of(0.7, 0.7, 0.7),
                Map.of(ALT, LocalDate.of(2000, 1, 1), NEU, LocalDate.of(2020, 1, 1)), REGEL, UHR))
                .containsExactly(OHNE_DATUM, NEU, ALT);
    }

    @Test
    void deaktivierteRegelBewahrtDieRerankerReihenfolge() {
        assertThat(Aktualitaetsranking.ordnen(List.of(ALT, NEU), List.of(0.1, 0.9),
                Map.of(ALT, LocalDate.of(2000, 1, 1), NEU, LocalDate.of(2020, 1, 1)),
                Aktualitaetsregel.AUS, UHR)).containsExactly(ALT, NEU);
    }
}
