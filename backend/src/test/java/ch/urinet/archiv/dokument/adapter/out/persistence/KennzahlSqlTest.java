package ch.urinet.archiv.dokument.adapter.out.persistence;

import ch.urinet.archiv.ki.application.chat.WerkzeugDaten;
import ch.urinet.archiv.ki.application.chat.WerkzeugDaten.ArtRegel;
import ch.urinet.archiv.ki.application.chat.WerkzeugDaten.Basis;
import ch.urinet.archiv.ki.application.chat.WerkzeugDaten.Gruppierung;
import ch.urinet.archiv.ki.application.chat.WerkzeugDaten.Richtung;
import ch.urinet.archiv.sicherheit.domain.Benutzer;
import ch.urinet.archiv.sicherheit.domain.Rolle;
import ch.urinet.archiv.sicherheit.domain.Sichtregel;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spec 02, Req 3.2, 3.3, 5.2: Die SQL-Stücke von {@code kennzahl} und {@code offene_posten} entstehen aus festen
 * Teilen je Gruppierung und Basis; jeder Wert ist ein gebundener Parameter, jeder Parameter wird benutzt.
 */
class KennzahlSqlTest {

    private static final LocalDate VON = LocalDate.of(2071, 1, 1);
    private static final LocalDate BIS = LocalDate.of(2071, 12, 31);
    private static final List<ArtRegel> ARTEN = List.of(new ArtRegel("AUSGANGSRECHNUNG", null),
            new ArtRegel("LOHNABRECHNUNG", "periode"));
    private static final Pattern PARAMETER = Pattern.compile("(?<![:\\w]):([a-zA-Z]\\w*)");

    @Test
    void jedeKombinationAusRichtungGruppierungUndBasis() {
        Sichtregel sicht = Sichtregel.fuer(new Benutzer("b", Rolle.MITARBEITER));
        for (Richtung richtung : Richtung.values()) {
            for (Gruppierung gruppierung : Gruppierung.values()) {
                for (Basis basis : Basis.values()) {
                    String fall = richtung + "/" + gruppierung + "/" + basis;
                    KennzahlSql sql = KennzahlSql.kennzahl(new WerkzeugDaten.KennzahlAnfrage(richtung, ARTEN, VON, BIS,
                            gruppierung, basis, 50), sicht);
                    String datum = basis == Basis.BELEGDATUM ? "d.datum" : "f.bezahlt_am";
                    for (String stueck : List.of(sql.zeilen(), sql.summe(), sql.belege())) {
                        assertThat(stueck).as(fall).contains("d.status = 'ARCHIVIERT'", "d.bestaetigt is not null",
                                "f.richtung = :richtung", datum + " between :von and :bis", "d.art in (:arten)",
                                "(d.art = :periodeArt1 and d.zusatz ->> :periodeFeld1 ~ " + KennzahlSql.MONAT + ")");
                        nurGebunden(fall, stueck, sql.parameter());
                    }
                    assertThat(sql.zeilen()).as(fall).contains("f.waehrung = 'CHF'").endsWith(" limit 201");
                    assertThat(sql.summe()).as(fall).contains("f.waehrung <> 'CHF'");
                    assertThat(sql.belege()).as(fall).contains("f.waehrung = 'CHF'").endsWith(" limit 50");
                    assertThat(sql.parameter()).as(fall).containsEntry("richtung", richtung.name())
                            .containsEntry("von", VON).containsEntry("bis", BIS);
                    assertThat(sql.parameter().get("arten")).as(fall).isEqualTo(List.of("AUSGANGSRECHNUNG"));
                    assertThat(sql.parameter()).as(fall).containsEntry("periodeArt1", "LOHNABRECHNUNG")
                            .containsEntry("periodeFeld1", "periode");
                    String gruppe = switch (gruppierung) {
                        case JAHR -> "to_char(" + datum + ", 'YYYY') as gruppe";
                        case MONAT -> "to_char(" + datum + ", 'YYYY-MM') as gruppe";
                        case PARTNER -> "d.partner_name as gruppe";
                        case ART -> "d.art as gruppe";
                        case KEINE -> "cast(null as text) as gruppe";
                    };
                    assertThat(sql.zeilen()).as(fall).contains(gruppe);
                    if (gruppierung == Gruppierung.KEINE) {
                        assertThat(sql.zeilen()).as(fall).doesNotContain("group by");
                    } else {
                        assertThat(sql.zeilen()).as(fall).contains(" group by 1 order by ");
                    }
                }
            }
        }
    }

    @Test
    void ordnungDerGruppen() {
        Sichtregel sicht = Sichtregel.fuer(new Benutzer("b", Rolle.INHABER));
        assertThat(zeilen(Gruppierung.JAHR, sicht)).contains(" order by 1 limit");
        assertThat(zeilen(Gruppierung.MONAT, sicht)).contains(" order by 1 limit");
        assertThat(zeilen(Gruppierung.PARTNER, sicht)).contains(" order by 2 desc, 1 nulls last limit");
        assertThat(zeilen(Gruppierung.ART, sicht)).contains(" order by 2 desc, 1 nulls last limit");
    }

    @Test
    void sichtFilterUndUnbestaetigteBelege() {
        Sichtregel inhaber = Sichtregel.fuer(new Benutzer("i", Rolle.INHABER));
        Sichtregel mitarbeiter = Sichtregel.fuer(new Benutzer("m", Rolle.MITARBEITER));
        KennzahlSql alle = KennzahlSql.kennzahl(anfrage(Gruppierung.KEINE), inhaber);
        assertThat(alle.summe()).contains("1 = 1").contains("d.bestaetigt is not null");
        KennzahlSql eingeschraenkt = KennzahlSql.kennzahl(anfrage(Gruppierung.KEINE), mitarbeiter);
        assertThat(eingeschraenkt.summe()).contains("d.vertraulichkeit = 'NORMAL'").doesNotContain("1 = 1")
                .contains("d.bestaetigt is not null");
        assertThat(eingeschraenkt.parameter()).containsKey("benutzer");
        nurGebunden("mitarbeiter", eingeschraenkt.summe(), eingeschraenkt.parameter());
    }

    @Test
    void ohneArtenZaehltNichts() {
        KennzahlSql sql = KennzahlSql.kennzahl(new WerkzeugDaten.KennzahlAnfrage(Richtung.EINNAHME, List.of(), VON, BIS,
                Gruppierung.KEINE, Basis.BELEGDATUM, 50), Sichtregel.fuer(new Benutzer("i", Rolle.INHABER)));
        assertThat(sql.summe()).contains(" and 1 = 0 and ").doesNotContain(":arten");
    }

    @Test
    void offenePostenMitFesterReihenfolgeUndGebundenenWerten() {
        Sichtregel sicht = Sichtregel.fuer(new Benutzer("m", Rolle.MITARBEITER));
        KennzahlSql sql = KennzahlSql.offenePosten(Richtung.AUSGABE, ARTEN, VON, BIS, sicht);
        assertThat(sql.offen()).contains("d.status = 'ARCHIVIERT'", "d.bestaetigt is not null",
                "f.richtung = :richtung", "f.faelligkeit between :von and :bis", "d.art in (:arten)",
                "count(*) over () as gesamt").endsWith(" order by f.faelligkeit, d.id limit :hoechstens");
        Map<String, Object> mitGrenze = new java.util.HashMap<>(sql.parameter());
        mitGrenze.put("hoechstens", 50);
        nurGebunden("offene Posten", sql.offen(), mitGrenze);
        assertThat(sql.parameter()).containsEntry("richtung", "AUSGABE");
    }

    private static String zeilen(Gruppierung gruppierung, Sichtregel sicht) {
        return KennzahlSql.kennzahl(anfrage(gruppierung), sicht).zeilen();
    }

    private static WerkzeugDaten.KennzahlAnfrage anfrage(Gruppierung gruppierung) {
        return new WerkzeugDaten.KennzahlAnfrage(Richtung.EINNAHME, ARTEN, VON, BIS, gruppierung, Basis.BELEGDATUM, 50);
    }

    /** Jeder Parameter im Text ist gebunden und jeder gebundene Parameter kommt im Text vor; keine Werte im Text. */
    private static void nurGebunden(String fall, String sql, Map<String, Object> parameter) {
        Set<String> imText = new HashSet<>();
        Matcher m = PARAMETER.matcher(sql.replaceAll("'[^']*'", "''"));
        while (m.find()) {
            imText.add(m.group(1));
        }
        assertThat(imText).as(fall).isEqualTo(parameter.keySet());
        assertThat(sql).as(fall).doesNotContain("2071", "AUSGANGSRECHNUNG", "LOHNABRECHNUNG", "'periode'");
    }
}
