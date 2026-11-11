package ch.urinet.archiv.kontakt.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AbgleichRegelTest {

    private static final BigDecimal SCHWELLE = new BigDecimal("0.6");
    private static final UUID A = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID B = UUID.fromString("00000000-0000-0000-0000-00000000000b");

    @Test
    void ohneExakteTrefferWeiterZurAehnlichkeit() {
        assertThat(AbgleichRegel.exakt(Map.of())).isEmpty();
        assertThat(AbgleichRegel.exakt(Map.of(Merkmal.EMAIL, Set.of(), Merkmal.IBAN, Set.of()))).isEmpty();
    }

    @Test
    void ersteStufeMitTrefferGewinnt() {
        assertThat(AbgleichRegel.exakt(Map.of(Merkmal.UID, Set.of(A), Merkmal.IBAN, Set.of(A))))
                .contains(new AbgleichErgebnis.Verknuepft(A, Merkmal.UID));
        assertThat(AbgleichRegel.exakt(Map.of(Merkmal.EMAIL, Set.of(A), Merkmal.UID, Set.of(A))))
                .contains(new AbgleichErgebnis.Verknuepft(A, Merkmal.EMAIL));
    }

    @Test
    void stufenAufVerschiedeneKontakteSindUnsicher() {
        assertThat(AbgleichRegel.exakt(Map.of(Merkmal.EMAIL, Set.of(A), Merkmal.IBAN, Set.of(B))))
                .contains(new AbgleichErgebnis.Unsicher(List.of(A, B), Merkmal.EMAIL));
    }

    @Test
    void mehrereTrefferAufEinerStufeSindUnsicher() {
        Set<UUID> beide = new LinkedHashSet<>(List.of(A, B));
        assertThat(AbgleichRegel.exakt(Map.of(Merkmal.IBAN, beide)))
                .contains(new AbgleichErgebnis.Unsicher(List.of(A, B), Merkmal.IBAN));
    }

    @Test
    void ohneKandidatenNeuerPartner() {
        assertThat(AbgleichRegel.aehnlich(List.of(), SCHWELLE)).isEqualTo(new AbgleichErgebnis.NeuerPartner());
    }

    @ParameterizedTest(name = "{0} / {1} → {2}")
    @CsvSource(nullValues = "-", textBlock = """
            0.6,  -,    VERKNUEPFT
            0.59, -,    NEU
            0.8,  0.7,  VERKNUEPFT
            0.7,  0.6,  VERKNUEPFT
            0.79, 0.7,  UNSICHER
            0.8,  0.8,  UNSICHER
            0.65, 0.55, VERKNUEPFT
            0.65, 0.56, UNSICHER
            0.55, 0.5,  NEU
            """)
    void aehnlichkeitMitSchwelleUndAbstand(String bester, String zweiter, String erwartet) {
        List<PartnerKandidat> kandidaten = zweiter == null ? List.of(kandidat(A, bester))
                : List.of(kandidat(A, bester), kandidat(B, zweiter));
        AbgleichErgebnis ergebnis = AbgleichRegel.aehnlich(kandidaten, SCHWELLE);
        switch (erwartet) {
            case "VERKNUEPFT" -> assertThat(ergebnis).isEqualTo(new AbgleichErgebnis.Verknuepft(A, Merkmal.NAME));
            case "UNSICHER" -> assertThat(ergebnis).isEqualTo(new AbgleichErgebnis.Unsicher(List.of(A, B), Merkmal.NAME));
            default -> assertThat(ergebnis).isEqualTo(new AbgleichErgebnis.NeuerPartner());
        }
    }

    @Test
    void untergrenzeLiegtUmDenAbstandTiefer() {
        assertThat(AbgleichRegel.untergrenze(SCHWELLE)).isEqualByComparingTo("0.5");
        assertThat(AbgleichRegel.untergrenze(new BigDecimal("0.05"))).isEqualByComparingTo("0");
    }

    private static PartnerKandidat kandidat(UUID id, String aehnlichkeit) {
        return new PartnerKandidat(id, new BigDecimal(aehnlichkeit));
    }
}
