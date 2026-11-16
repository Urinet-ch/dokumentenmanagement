package ch.urinet.archiv.dokument.adapter.out.persistence;

import ch.urinet.archiv.sicherheit.domain.Sichtregel;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Sicht der Webseiten-Schnittstelle in jeder Suchabfrage (Spec 03, Aufgabe 11.2): Der feste Filter mit den Stufen der
 * Anfrage kommt zur Sicht hinzu, Jahr und Ausgabe sind gebundene Werte, und die HQL-Variante für die App lehnt die Regel
 * ab (nie eine zu weite Sicht).
 */
class VertraulichkeitFilterWebseiteTest {

    private static Sichtregel regel(String jahr, String ausgabe) {
        return Sichtregel.fuerWebseite(new Sichtregel.Webseite(List.of("oeffentlich", "mitglieder"), jahr, ausgabe));
    }

    @Test
    void festerFilterKommtZurSichtHinzu() {
        VertraulichkeitFilter f = VertraulichkeitFilter.sql(regel(null, null), "d");
        assertThat(f.bedingung()).contains("d.status = 'ARCHIVIERT'").contains("d.vertraulichkeit = 'NORMAL'")
                .contains("d.zusatz -> 'veroeffentlicht' = 'true'::jsonb")
                .contains("d.zusatz ->> 'zugriff' = any(:stufen)")
                // die Sicht selbst: nur bestätigte NORMAL-Dokumente, nie unbestätigte
                .contains("d.bestaetigt is not null").contains("1 = 0");
        assertThat(f.parameter()).containsOnlyKeys("stufen");
        assertThat((String[]) f.parameter().get("stufen")).containsExactly("oeffentlich", "mitglieder");
    }

    @Test
    void jahrUndAusgabeSindGebundeneWerte() {
        VertraulichkeitFilter f = VertraulichkeitFilter.sql(regel("2025", "4'; drop table dokument; --"), "d");
        assertThat(f.bedingung()).contains("d.zusatz ->> 'jahr' = :webJahr")
                .contains("d.zusatz ->> 'ausgabe' = :webAusgabe").doesNotContain("drop table");
        assertThat(f.parameter()).containsEntry("webJahr", "2025")
                .containsEntry("webAusgabe", "4'; drop table dokument; --");
    }

    @Test
    void dieRegelIstNieUneingeschraenkt() {
        assertThat(regel(null, null).uneingeschraenkt()).isFalse();
    }

    @Test
    void keineStufenGibtEsNicht() {
        assertThatThrownBy(() -> new Sichtregel.Webseite(List.of(), null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void dieHqlVarianteDerAppLehntDieRegelAb() {
        assertThatThrownBy(() -> VertraulichkeitFilter.fuer(regel(null, null)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void appRegelnBleibenOhneFestenFilter() {
        Sichtregel app = new Sichtregel("u", Sichtregel.Unbestaetigt.KEINE, true, true, Sichtregel.Personal.KEINE,
                false);
        assertThat(VertraulichkeitFilter.sql(app, "d").bedingung()).doesNotContain("veroeffentlicht");
        assertThat(VertraulichkeitFilter.sql(app, "d").parameter()).doesNotContainKey("stufen");
    }
}
