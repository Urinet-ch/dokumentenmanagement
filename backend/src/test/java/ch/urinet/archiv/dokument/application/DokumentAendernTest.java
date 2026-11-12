package ch.urinet.archiv.dokument.application;

import ch.urinet.archiv.ablage.domain.SperrAenderung;
import ch.urinet.archiv.dokument.application.DokumentAenderungPort.Metadaten;
import ch.urinet.archiv.dokument.application.DokumentStatusPort.Finanz;
import ch.urinet.archiv.dokument.domain.DokumentStatus;
import ch.urinet.archiv.dokumentart.domain.AufbewahrungAb;
import ch.urinet.archiv.dokumentart.domain.Aufbewahrungsregel;
import ch.urinet.archiv.dokumentart.domain.Dokumentart;
import ch.urinet.archiv.dokumentart.domain.Vertraulichkeit;
import ch.urinet.archiv.gemeinsam.domain.PflichtfelderFehlen;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/** Pflichtfelder archivierter Dokumente beim Ändern (Req 10.1, 8.3) und Ende der Sperre (Req 9.7), ohne Quarkus. */
class DokumentAendernTest {

    private static final Dokumentart ART = new Dokumentart("SPENDE", "Spende", null, List.of("finanz", "frei"),
            List.of("titel", "datum", "betrag", "zusatz.zweck"), Dokumentart.OHNE_ZUSATZ, Vertraulichkeit.INTERN,
            new Aufbewahrungsregel(10, AufbewahrungAb.GESCHAEFTSJAHR), true, true);

    @Test
    void einPflichtfeldDarfNichtNeuFehlen() {
        Metadaten alt = metadaten("Spende", new BigDecimal("10.00"), Set.of("zweck"));
        Metadaten neu = metadaten(null, null, Set.of("zweck"));
        PflichtfelderFehlen fehler = catchThrowableOfType(PflichtfelderFehlen.class,
                () -> DokumentAendern.pflichtfelderPruefen(ART, alt, neu, false));
        assertThat(fehler.felder()).containsExactly("titel", "betrag");
    }

    @Test
    void einSchonFehlendesPflichtfeldBleibtErlaubt() {
        Metadaten alt = metadaten(null, null, Set.of());
        assertThatCode(() -> DokumentAendern.pflichtfelderPruefen(ART, alt, alt, false)).doesNotThrowAnyException();
    }

    @Test
    void nachDemWechselDerArtGeltenAllePflichtfelder() {
        Metadaten alt = metadaten(null, null, Set.of());
        PflichtfelderFehlen fehler = catchThrowableOfType(PflichtfelderFehlen.class,
                () -> DokumentAendern.pflichtfelderPruefen(ART, alt, alt, true));
        assertThat(fehler.felder()).containsExactly("titel", "betrag", "zusatz.zweck");
    }

    @Test
    void dieSperreEndetAmLetztenTagVorDemFolgetagInZuerich() {
        // Mitternacht Zürich (Winterzeit) des 01.01.2037 → letzter gesperrter Tag 31.12.2036
        Instant spaeter = Instant.parse("2036-12-31T23:00:00Z");
        List<SperrAenderung> ergebnis = List.of(
                new SperrAenderung("archiv/a", "1", SperrAenderung.Ergebnis.VERLAENGERT,
                        Optional.of(Instant.parse("2030-01-01T00:00:00Z"))),
                new SperrAenderung("archiv/b", "2", SperrAenderung.Ergebnis.GESETZT, Optional.of(spaeter)),
                new SperrAenderung("archiv/c", "3", SperrAenderung.Ergebnis.KONFLIKT, Optional.empty()));
        assertThat(DokumentAendern.letzterTag(ergebnis)).contains(LocalDate.of(2036, 12, 31));
        assertThat(DokumentAendern.letzterTag(List.of())).isEmpty();
    }

    private static Metadaten metadaten(String titel, BigDecimal betrag, Set<String> zusatz) {
        return new Metadaten(UUID.randomUUID(), DokumentStatus.ARCHIVIERT, 3, Instant.now(), "SPENDE", titel,
                LocalDate.of(2026, 3, 10), null, null, null, List.of(), "INTERN", null, null, null, false, null,
                null, zusatz, Optional.of(new Finanz(null, null, betrag, "CHF", null, null, null)), Optional.empty());
    }
}
