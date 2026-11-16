package ch.urinet.archiv.dokument.adapter.out.persistence;

import ch.urinet.archiv.sicherheit.domain.Sichtregel;
import ch.urinet.archiv.suche.domain.Suchanfrage;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Spec 03, Aufgabe 11.3: Die Suche der Webseite läuft über den festen Filter, nie über die Sicht der App. */
class SuchFilterWebseiteTest {

    private static final Suchanfrage ANFRAGE = new Suchanfrage("seilbahn", null, null, null, null, null, null, null,
            null, null, null, null, null, 0, 5);

    @Test
    void webseiteNimmtDenFestenFilterMitDenStufen() {
        Map<String, Object> parameter = new HashMap<>();
        List<String> bedingungen = SuchFilter.bedingungen(ANFRAGE, Sichtregel.webseite(List.of("oeffentlich", "mitglieder")),
                parameter);
        assertThat(bedingungen.getFirst()).isEqualTo("(d.status = 'ARCHIVIERT' and d.vertraulichkeit = 'NORMAL' and "
                + "d.zusatz -> 'veroeffentlicht' = 'true'::jsonb and d.zusatz ->> 'zugriff' = any(:stufen))");
        assertThat((String[]) parameter.get("stufen")).containsExactly("oeffentlich", "mitglieder");
        assertThat(String.join(" ", bedingungen)).doesNotContain("bestaetigt").doesNotContain("person_benutzer")
                .doesNotContain(":benutzer");
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

    @Test
    void dieWebseitenSichtGiltNurInDerSucheNichtInDerSichtDerApp() {
        Sichtregel regel = Sichtregel.webseite(List.of("oeffentlich"));
        assertThatThrownBy(() -> VertraulichkeitFilter.sql(regel, "d")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> VertraulichkeitFilter.fuer(regel)).isInstanceOf(IllegalArgumentException.class);
    }
}
