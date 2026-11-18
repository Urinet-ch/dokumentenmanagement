package ch.urinet.archiv.dokument.adapter.out.persistence;

import ch.urinet.archiv.sicherheit.application.SichtregelSql;
import ch.urinet.archiv.sicherheit.domain.Benutzer;
import ch.urinet.archiv.sicherheit.domain.Rolle;
import ch.urinet.archiv.sicherheit.domain.Sichtregel;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Dokumentlisten (HQL) und Suche/Wissensbasis (SQL) wenden dieselbe Sicht an (Req 2.3, 2.21): Für jede Sichtregel
 * unterscheiden sich die Prädikate nur in den Spaltennamen (Review PR #311, S1).
 */
class VertraulichkeitFilterTest {

    private static String alsHql(String sql) {
        return sql.replace("d.bestaetigt", "bestaetigt").replace("d.erstellt_von", "erstelltVon")
                .replace("d.vertraulichkeit", "vertraulichkeit").replace("d.person_benutzer", "personBenutzer");
    }

    private static List<Sichtregel> alleRegeln() {
        List<Sichtregel> regeln = new ArrayList<>();
        for (Rolle rolle : Rolle.values()) {
            regeln.add(Sichtregel.fuer(new Benutzer("u-1", rolle)));
        }
        for (Sichtregel.Unbestaetigt unbestaetigt : Sichtregel.Unbestaetigt.values()) {
            for (Sichtregel.Personal personal : Sichtregel.Personal.values()) {
                for (int stufen = 0; stufen < 4; stufen++) {
                    regeln.add(new Sichtregel("u-1", unbestaetigt, (stufen & 1) != 0, (stufen & 2) != 0, personal,
                            false));
                }
            }
        }
        return regeln;
    }

    @Test
    void hqlUndSqlEntstehenAusDerselbenRegel() {
        for (Sichtregel regel : alleRegeln()) {
            VertraulichkeitFilter hql = VertraulichkeitFilter.fuer(regel);
            VertraulichkeitFilter sql = VertraulichkeitFilter.sql(regel, "d");
            assertThat(hql.bedingung()).as(regel.toString()).isEqualTo(alsHql(sql.bedingung()));
            assertThat(hql.parameter()).as(regel.toString()).isEqualTo(sql.parameter());
        }
    }

    @Test
    void mitarbeiterSiehtNormalUndEigenePersonalDokumente() {
        Sichtregel regel = Sichtregel.fuer(new Benutzer("u-1", Rolle.MITARBEITER));
        VertraulichkeitFilter hql = VertraulichkeitFilter.fuer(regel);
        assertThat(hql.bedingung()).contains("vertraulichkeit = 'NORMAL'")
                .contains("(vertraulichkeit = 'PERSONAL' and personBenutzer = :benutzer)")
                .doesNotContain("'INTERN'");
        assertThat(hql.parameter()).containsEntry("benutzer", "u-1");
    }

    @Test
    void spaltenNehmenNurFesteBezeichner() {
        assertThatThrownBy(() -> new SichtregelSql.Spalten("bestaetigt", "erstelltVon", "x or 1=1", "personBenutzer"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SichtregelSql.Spalten.sql("d; drop"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
