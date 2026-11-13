package ch.urinet.archiv.ki.domain;

import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class KorrekturTest {

    private static Map<Feld, String> werte(Object... paare) {
        Map<Feld, String> m = new EnumMap<>(Feld.class);
        for (int i = 0; i < paare.length; i += 2) {
            m.put((Feld) paare[i], (String) paare[i + 1]);
        }
        return m;
    }

    @Test
    void korrigiertSindNurAbweichendeFelderMitDemBestaetigtenWert() {
        Korrektur k = Korrektur.ermitteln(
                werte(Feld.ART, "LIEFERANTENBELEG", Feld.TITEL, "Rechnung", Feld.BETRAG, "123.4", Feld.DATUM, null),
                werte(Feld.ART, "LIEFERANTENBELEG", Feld.TITEL, "Rechnung Büromaterial", Feld.BETRAG, "123.40",
                        Feld.DATUM, " "),
                true, false);

        assertThat(k.bestaetigt()).containsExactlyInAnyOrder(Feld.ART, Feld.TITEL, Feld.BETRAG);
        assertThat(k.korrigiert()).containsExactly(Map.entry(Feld.TITEL, "Rechnung Büromaterial"));
        assertThat(k.beispiel()).isTrue();
    }

    @Test
    void entfernterWertIstEineKorrekturMitNull() {
        Korrektur k = Korrektur.ermitteln(werte(Feld.TITEL, "Rechnung"), werte(Feld.TITEL, null), false, false);

        assertThat(k.korrigiert()).containsEntry(Feld.TITEL, null).hasSize(1);
    }

    @Test
    void finanzfelderZaehlenNurBeiFinanzarten() {
        Map<Feld, String> vorschlag = werte(Feld.ART, "KORRESPONDENZ", Feld.BETRAG, "10.00");
        Map<Feld, String> ergebnis = werte(Feld.ART, "KORRESPONDENZ", Feld.BETRAG, null);

        assertThat(Korrektur.ermitteln(vorschlag, ergebnis, false, false).beispiel()).isFalse();
        assertThat(Korrektur.ermitteln(vorschlag, ergebnis, true, false).korrigiert()).containsKey(Feld.BETRAG);
    }

    @Test
    void partnerOhneGrossKleinUndLeerraumOderUebernommenGiltAlsBestaetigt() {
        assertThat(Korrektur.ermitteln(werte(Feld.PARTNER, "Muster  bürobedarf AG"),
                werte(Feld.PARTNER, "Muster Bürobedarf AG"), false, false).beispiel()).isFalse();
        assertThat(Korrektur.ermitteln(werte(Feld.PARTNER, "Muster AG"),
                werte(Feld.PARTNER, "Muster Bürobedarf AG"), false, true).beispiel()).isFalse();
        assertThat(Korrektur.ermitteln(werte(Feld.PARTNER, "Muster AG"),
                werte(Feld.PARTNER, "Muster Bürobedarf AG"), false, false).korrigiert())
                .containsEntry(Feld.PARTNER, "Muster Bürobedarf AG");
    }

    @Test
    void tagsSindEineMengeOhneReihenfolgeUndDuplikate() {
        assertThat(Korrektur.tags(java.util.List.of("Steuern", " Büro", "Steuern", ""))).isEqualTo("Büro, Steuern");
        assertThat(Korrektur.tags(java.util.List.of())).isNull();
        assertThat(Korrektur.ermitteln(werte(Feld.TAGS, "Büro, Steuern"), werte(Feld.TAGS, "Büro"), false, false)
                .korrigiert()).containsEntry(Feld.TAGS, "Büro");
    }

    @Test
    void ohneWaehrungInVorschlagUndErgebnisKeineKorrektur() {
        assertThat(Korrektur.ermitteln(werte(Feld.WAEHRUNG, null), werte(Feld.WAEHRUNG, null), true, false)
                .beispiel()).isFalse();
    }
}
