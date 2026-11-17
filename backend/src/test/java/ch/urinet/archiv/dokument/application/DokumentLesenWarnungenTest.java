package ch.urinet.archiv.dokument.application;

import ch.urinet.archiv.dokument.domain.Dokument;
import ch.urinet.archiv.dokument.domain.DokumentStatus;
import ch.urinet.archiv.dokumentart.application.DokumentartVerwaltung;
import ch.urinet.archiv.dokumentart.domain.AufbewahrungAb;
import ch.urinet.archiv.dokumentart.domain.Aufbewahrungsregel;
import ch.urinet.archiv.dokumentart.domain.Dokumentart;
import ch.urinet.archiv.dokumentart.domain.Fristbezug;
import ch.urinet.archiv.dokumentart.domain.Vertraulichkeit;
import ch.urinet.archiv.gemeinsam.domain.FachFehler;
import ch.urinet.archiv.gemeinsam.domain.Fehlercode;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Warnung {@code FRISTBEZUG_FEHLT} beim Lesen (Spec 05, Req 4.4), ohne Quarkus. */
class DokumentLesenWarnungenTest {

    private static final Dokumentart ART = new Dokumentart("PROBE", "Probe", null, List.of("frei"),
            List.of("titel"), Dokumentart.OHNE_ZUSATZ, Vertraulichkeit.INTERN,
            new Aufbewahrungsregel(10, AufbewahrungAb.FELD, "sitzung"), true, true);

    @Test
    void einUnlesbarerAltwertImFristfeldGiltAlsFehlendUndBrichtDasLesenNichtAb() {
        List<Warnung> warnungen = lesen(feld -> {
            throw new FachFehler(Fehlercode.ZUSATZ_UNGUELTIG);
        });
        assertThat(warnungen).extracting(Warnung::code).containsExactly(Warnung.FRISTBEZUG_FEHLT);
    }

    @Test
    void ohneWertGibtEsDieWarnungMitWertKeine() {
        assertThat(lesen(feld -> null)).extracting(Warnung::code).containsExactly(Warnung.FRISTBEZUG_FEHLT);
        assertThat(lesen(feld -> LocalDate.of(2026, 5, 1))).isEmpty();
    }

    @Test
    void andereFehlerWerdenWeitergegeben() {
        assertThatThrownBy(() -> lesen(feld -> {
            throw new FachFehler(Fehlercode.NICHT_GEFUNDEN);
        })).isInstanceOf(FachFehler.class);
    }

    private static List<Warnung> lesen(Fristbezug bezug) {
        var arten = new DokumentartVerwaltung(null, null, null, null, null, null) {
            @Override
            public Dokumentart finden(String code) {
                return ART;
            }

            @Override
            public Fristbezug fristbezug(String zusatzJson) {
                return bezug;
            }
        };
        var lesen = new DokumentLesen(null, null, null, null, null, BigDecimal.ONE, arten);
        Dokument dokument = new Dokument(UUID.randomUUID(), "PROBE", null, null, null, null, null, null, null, null,
                DokumentStatus.ARCHIVIERT, null, null, null, null, false, "{}", 1, null, false, null, null, null,
                null, null, null, null, null, null);
        return lesen.warnungen(null, dokument);
    }
}
