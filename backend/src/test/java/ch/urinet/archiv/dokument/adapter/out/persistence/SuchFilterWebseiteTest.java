package ch.urinet.archiv.dokument.adapter.out.persistence;

import ch.urinet.archiv.sicherheit.domain.Sichtregel;
import ch.urinet.archiv.suche.domain.Suchanfrage;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spec 03, Aufgabe 11.3: Die Suche der Webseite läuft mit der Sicht der Webseite ({@code Sichtregel.fuerWebseite}) über
 * {@code SuchFilter}: Sicht (nur bestätigte {@code NORMAL}) und fester Filter ({@code veroeffentlicht}, Stufen), nie
 * die Sicht eines App-Benutzers. Das Modell ist das von 11.2 ({@code VertraulichkeitFilterWebseiteTest}).
 */
class SuchFilterWebseiteTest {

    private static final Suchanfrage ANFRAGE = new Suchanfrage("seilbahn", null, null, null, null, null, null, null,
            null, null, null, null, null, 0, 5);

    @Test
    void webseiteNimmtSichtUndFestenFilterMitDenStufen() {
        Map<String, Object> parameter = new HashMap<>();
        List<String> bedingungen = SuchFilter.bedingungen(ANFRAGE, Sichtregel.fuerWebseite(
                new Sichtregel.Webseite(List.of("oeffentlich", "mitglieder"), null, null)), parameter);
        String sicht = bedingungen.getFirst();
        assertThat(sicht).contains("d.bestaetigt is not null").contains("d.vertraulichkeit = 'NORMAL'")
                .contains("d.status = 'ARCHIVIERT'").contains("d.zusatz -> 'veroeffentlicht' = 'true'::jsonb")
                .contains("d.zusatz ->> 'zugriff' = any(:stufen)").doesNotContain("'INTERN'").doesNotContain("PERSONAL");
        assertThat((String[]) parameter.get("stufen")).containsExactly("oeffentlich", "mitglieder");
    }

    @Test
    void appSichtBleibtUnveraendert() {
        Map<String, Object> parameter = new HashMap<>();
        List<String> bedingungen = SuchFilter.bedingungen(ANFRAGE,
                new Sichtregel("joel", Sichtregel.Unbestaetigt.KEINE, true, false, Sichtregel.Personal.KEINE, false),
                parameter);
        assertThat(bedingungen.getFirst()).contains("d.bestaetigt is not null").doesNotContain("veroeffentlicht");
        assertThat(parameter).doesNotContainKey("stufen");
    }
}
