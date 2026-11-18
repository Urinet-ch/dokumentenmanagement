package ch.urinet.archiv.dokument.application;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Spec 05, Req 5.2: Regeln sehen beim Erfassen und Bestätigen die Werte, die gespeichert werden. */
class RegelMetadatenTest {

    @Test
    void ohneAngabeGeltenStandardvertraulichkeitUndBeiFinanzartenChf() {
        Bestaetigung ohne = new Bestaetigung("RECHNUNG", null, null, null, null, null, null, null, null, "R1",
                null, null, null, null, null, null, null, null, null, null, false, false);

        assertThat(RegelMetadaten.aus(ohne, "INTERN", true, Map.of()))
                .containsEntry("vertraulichkeit", "INTERN").containsEntry("waehrung", "CHF");
        assertThat(RegelMetadaten.aus(ohne, "INTERN", false, Map.of())).containsEntry("waehrung", null);

        Bestaetigung mit = new Bestaetigung("RECHNUNG", null, null, null, null, null, "PERSONAL", null, null, "R1",
                null, null, "EUR", null, null, null, null, null, null, null, false, false);
        assertThat(RegelMetadaten.aus(mit, "INTERN", true, Map.of()))
                .containsEntry("vertraulichkeit", "PERSONAL").containsEntry("waehrung", "EUR");
    }
}
