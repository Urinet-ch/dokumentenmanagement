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
    private static final LocalDate HEUTE = LocalDate.of(2026, 1, 1);
    private static final Aktualitaetsregel REGEL = new Aktualitaetsregel(true, 0.05, 365);
    private static final UUID ALT = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID NEU = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID OHNE_DATUM = UUID.fromString("00000000-0000-0000-0000-000000000003");
    private static final UUID BODEN = UUID.fromString("00000000-0000-0000-0000-000000000004");
    private static final Map<UUID, LocalDate> DATEN = Map.of(ALT, LocalDate.of(2000, 1, 1), NEU, HEUTE);

    @Test
    void beiGleicherRelevanzKommtDerNeuereTrefferZuerst() {
        assertThat(Aktualitaetsranking.ordnen(List.of(ALT, NEU), List.of(0.7, 0.7),
                Map.of(ALT, LocalDate.of(2000, 1, 1), NEU, LocalDate.of(2020, 1, 1)), REGEL, UHR))
                .containsExactly(NEU, ALT);
    }

    /** Normierter Abstand 0.06 liegt über dem Faktor 0.05: Der ältere Treffer bleibt vorn. */
    @Test
    void einAbstandUeberDemFaktorBleibtErhalten() {
        assertThat(Aktualitaetsranking.ordnen(List.of(ALT, NEU, BODEN), List.of(1.0, 0.94, 0.0), DATEN, REGEL, UHR))
                .containsExactly(ALT, NEU, BODEN);
    }

    /** Normierter Abstand 0.04 liegt unter dem Faktor 0.05: Der neuere Treffer zieht vorbei. */
    @Test
    void einAbstandUnterDemFaktorWirdUmgeordnet() {
        assertThat(Aktualitaetsranking.ordnen(List.of(ALT, NEU, BODEN), List.of(1.0, 0.96, 0.0), DATEN, REGEL, UHR))
                .containsExactly(NEU, ALT, BODEN);
    }

    @Test
    void einDatumInDerZukunftZaehltWieHeute() {
        UUID zukunft = UUID.fromString("00000000-0000-0000-0000-000000000005");
        assertThat(Aktualitaetsranking.ordnen(List.of(NEU, zukunft), List.of(0.7, 0.7),
                Map.of(NEU, HEUTE, zukunft, LocalDate.of(2030, 1, 1)), REGEL, UHR))
                .containsExactly(NEU, zukunft);
    }

    @Test
    void faktorNullAendertNichts() {
        assertThat(Aktualitaetsranking.ordnen(List.of(ALT, NEU), List.of(0.7, 0.7), DATEN,
                new Aktualitaetsregel(true, 0, 365), UHR)).containsExactly(ALT, NEU);
    }

    /** 31.12.2025, 23:30 UTC ist in Zürich schon der 01.01.2026: Der Treffer vom 31.12. ist einen Tag alt. */
    @Test
    void heuteGiltInZuerich() {
        Clock silvester = Clock.fixed(Instant.parse("2025-12-31T23:30:00Z"), ZoneOffset.UTC);
        assertThat(Aktualitaetsranking.ordnen(List.of(ALT, OHNE_DATUM), List.of(0.7, 0.7),
                Map.of(ALT, LocalDate.of(2025, 12, 31)), REGEL, silvester))
                .containsExactly(OHNE_DATUM, ALT);
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
