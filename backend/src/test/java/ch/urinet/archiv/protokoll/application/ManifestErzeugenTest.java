package ch.urinet.archiv.protokoll.application;

import ch.urinet.archiv.protokoll.application.ManifestDaten.Fristen;
import ch.urinet.archiv.verarbeitung.domain.Nutzlast;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** Sperre des Manifests (Req 15.4) und letzter zu erzeugender Tag (Req 15.6, 15.7). */
class ManifestErzeugenTest {

    private static final LocalDate HEUTE = LocalDate.of(2026, 11, 12);

    @Test
    void dieSperreReichtBisZumSpaetestenAufbewahrenBis() {
        Fristen fristen = new Fristen(3, false, Optional.of(LocalDate.of(2037, 12, 31)));

        assertThat(ManifestErzeugen.sperre(fristen, HEUTE)).contains(LocalDate.of(2037, 12, 31));
    }

    @Test
    void einUnbefristetesDokumentVerlangtMindestensZehnJahre() {
        assertThat(ManifestErzeugen.sperre(new Fristen(2, true, Optional.of(LocalDate.of(2030, 1, 1))), HEUTE))
                .contains(LocalDate.of(2036, 11, 12));
        assertThat(ManifestErzeugen.sperre(new Fristen(2, true, Optional.of(LocalDate.of(2040, 1, 1))), HEUTE))
                .contains(LocalDate.of(2040, 1, 1));
        assertThat(ManifestErzeugen.sperre(new Fristen(1, true, Optional.empty()), HEUTE))
                .contains(LocalDate.of(2036, 11, 12));
    }

    @Test
    void ohneDokumentOderMitAbgelaufenenFristenGiltDerStandardVonZehnJahren() {
        assertThat(ManifestErzeugen.sperre(new Fristen(0, false, Optional.empty()), HEUTE)).isEmpty();
        assertThat(ManifestErzeugen.sperre(new Fristen(1, false, Optional.of(LocalDate.of(2026, 1, 1))), HEUTE))
                .isEmpty();
    }

    @Test
    void derZieltagIstDerVortagSobaldDerNachlaufVorbeiIst() {
        Instant knappNachMitternacht = Instant.parse("2026-11-11T23:10:00Z"); // 00:10 in Zürich
        Instant nachDemNachlauf = Instant.parse("2026-11-11T23:20:00Z");

        assertThat(ManifestErzeugen.zielTag(Nutzlast.leer(), knappNachMitternacht)).isEqualTo(LocalDate.of(2026, 11, 10));
        assertThat(ManifestErzeugen.zielTag(Nutzlast.leer(), nachDemNachlauf)).isEqualTo(LocalDate.of(2026, 11, 11));
    }

    @Test
    void bisTagBegrenztDenZieltagNurNachUnten() {
        Instant jetzt = Instant.parse("2026-11-12T10:00:00Z");
        Nutzlast frueher = new Nutzlast(Map.of(ManifestErzeugen.BIS_TAG, LocalDate.of(2026, 1, 5).toEpochDay()));
        Nutzlast spaeter = new Nutzlast(Map.of(ManifestErzeugen.BIS_TAG, LocalDate.of(2026, 12, 1).toEpochDay()));

        assertThat(ManifestErzeugen.zielTag(frueher, jetzt)).isEqualTo(LocalDate.of(2026, 1, 5));
        assertThat(ManifestErzeugen.zielTag(spaeter, jetzt)).isEqualTo(LocalDate.of(2026, 11, 11));
    }
}
