package ch.urinet.archiv.dokument.application;

import ch.urinet.archiv.dokument.application.DokumentStatusPort.Stand;
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
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/** Pflichtfelder beim Bestätigen (Req 8.3, 2.22), ohne Quarkus. */
class DokumentBestaetigenTest {

    private static final Dokumentart ART = new Dokumentart("SPENDE", "Spende", null, List.of("finanz", "frei"),
            List.of("titel", "datum", "vertraulichkeit", "betrag", "waehrung", "zusatz.zweck"), Dokumentart.OHNE_ZUSATZ,
            Vertraulichkeit.INTERN, new Aufbewahrungsregel(10, AufbewahrungAb.GESCHAEFTSJAHR), true, true);

    @Test
    void nenntAlleFehlendenFelderInDerReihenfolgeDerArt() {
        PflichtfelderFehlen fehler = catchThrowableOfType(PflichtfelderFehlen.class,
                () -> DokumentBestaetigen.pflichtfelderPruefen(ART, bestaetigung("  ", null), stand(Set.of())));
        assertThat(fehler.felder()).containsExactly("titel", "betrag", "zusatz.zweck");
    }

    @Test
    void vollstaendigOhneVertraulichkeitIstGueltig() {
        assertThatCode(() -> DokumentBestaetigen.pflichtfelderPruefen(ART,
                bestaetigung("Spende Probe", new BigDecimal("10.00")), stand(Set.of("zweck"))))
                .doesNotThrowAnyException();
    }

    private static Bestaetigung bestaetigung(String titel, BigDecimal betrag) {
        return new Bestaetigung("SPENDE", titel, LocalDate.of(2026, 3, 10), null, null, null, null, null, null, null,
                null, betrag, "CHF", null, null, null, null, null, null, null, false, false);
    }

    private static Stand stand(Set<String> zusatz) {
        return new Stand(UUID.randomUUID(), DokumentStatus.EINGANGSKORB, 1, Instant.now(), false, zusatz, List.of());
    }
}
