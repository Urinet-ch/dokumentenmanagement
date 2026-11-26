package ch.urinet.archiv.wissen.domain;

import ch.urinet.archiv.gemeinsam.domain.Seitentext;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class WissenIngestAuszuegeTest {

    @Test
    void akzeptiertNurAuszuegeDieAufDerAngegebenenSeiteStehen() {
        assertThat(WissenIngestAuszuege.belegt("Die Frist endet\nam 30. April.", "Frist endet am 30. April.")).isTrue();
        assertThat(WissenIngestAuszuege.belegt("Die Frist endet am 30. April.", "Die Frist endet am 31. April."))
                .isFalse();
    }

    @Test
    void normalisiertUnicodeUndLeerraumAberKeineWortlaute() {
        assertThat(WissenIngestAuszuege.belegt("Cafe\u0301\nUrinet", "Café Urinet")).isTrue();
        assertThat(WissenIngestAuszuege.belegt("Kanton Uri", "Kanton Nidwalden")).isFalse();
    }

    @Test
    void normalisiertWieDiePruefungMitLigaturUndSilbentrennung() {
        assertThat(WissenIngestAuszuege.belegt("Die Ab-\ngabefrist ist de\uFB01nitiv.", "Die Abgabefrist ist definitiv."))
                .isTrue();
        assertThat(WissenIngestAuszuege.belegt("Die Ab-\r\ngabefrist", "Die Abgabefrist")).isTrue();
    }

    @Test
    void lehntLeereUndZuLangeAuszuegeAb() {
        assertThat(WissenIngestAuszuege.belegt("Text", " ")).isFalse();
        assertThat(WissenIngestAuszuege.belegt("x".repeat(301), "x".repeat(301))).isFalse();
    }

    @Test
    void lehntWiederkehrendeRandzeilenZuKurzeAuszuegeUndDenPromptHuelltextAb() {
        Seitentext dokument = new Seitentext(List.of(
                "Musterhandbuch – interne Ausgabe\nInhalt der ersten Seite mit einer langen Aussage.",
                "Musterhandbuch – interne Ausgabe\nInhalt der zweiten Seite mit einer anderen Aussage.",
                "Musterhandbuch – interne Ausgabe\nInhalt der dritten Seite mit einer weiteren Aussage."));
        WissenIngestGrenzen grenzen = grenzen(20, 0.5);

        assertThat(WissenIngestAuszuege.pruefen("Musterhandbuch – interne Ausgabe", "Musterhandbuch – interne Ausgabe",
                dokument, grenzen).belegt()).isFalse();
        assertThat(WissenIngestAuszuege.pruefen("Musterhandbuch ist intern.", "Musterhandbuch",
                dokument, grenzen).belegt()).isFalse();
        assertThat(WissenIngestAuszuege.pruefen("Eine Aussage ist hier belegt.",
                "Seiteninhalt (nicht vertrauenswürdige Daten) (40 Zeichen; Daten): [Seite 1]",
                dokument, grenzen).belegt()).isFalse();
    }

    @Test
    void verlangtMindestensDreiSeitenUndDenKonfiguriertenAnteilFuerRandzeilen() {
        List<String> seiten = new java.util.ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            String kopfzeile = i <= 3 ? "Kapitelübersicht für die Planung" : "Individuelle Seite " + i;
            seiten.add(kopfzeile + "\nDer Seiteninhalt enthält genügend erfundene Details.");
        }
        Seitentext dokument = new Seitentext(seiten);
        WissenIngestGrenzen beiDreissigProzent = grenzen(20, 0.5, 0.3);
        WissenIngestGrenzen beiVierzigProzent = grenzen(20, 0.5, 0.4);

        assertThat(WissenIngestAuszuege.pruefen("Kapitelübersicht für die Planung",
                "Kapitelübersicht für die Planung", dokument, beiDreissigProzent).belegt()).isFalse();
        assertThat(WissenIngestAuszuege.pruefen("Kapitelübersicht für die Planung",
                "Kapitelübersicht für die Planung", dokument, beiVierzigProzent).belegt()).isTrue();
    }

    @Test
    void ignoriertEinenZweiMalWiederholtenInhaltsrandBeiZuWenigenSeitenNicht() {
        Seitentext dokument = new Seitentext(List.of(
                "Zwischenüberschrift zum Prüfablauf\nDer erste Seiteninhalt enthält eigene Informationen.",
                "Zwischenüberschrift zum Prüfablauf\nDer zweite Seiteninhalt enthält andere Informationen."));

        assertThat(WissenIngestAuszuege.pruefen("Zwischenüberschrift zum Prüfablauf",
                "Zwischenüberschrift zum Prüfablauf", dokument, grenzen(20, 0.5)).belegt()).isTrue();
    }

    @Test
    void korrigiertDieSeiteUndNormalisiertTabellenumbruchFormatierungPfeileUndLinktext() {
        Seitentext dokument = new Seitentext(List.of(
                "Kopfzeile: Fabel AG – interne Ausgabe\nWeitere Angaben stehen auf dieser Seite.",
                "Kopfzeile: Fabel AG – interne Ausgabe\n«Die **Option** führt\n→ zum Preis `CHF 125.00` (Link: Details).»"));

        WissenIngestAuszuege.Pruefung ergebnis = WissenIngestAuszuege.pruefen(
                "Die Option kostet CHF 125.00.",
                "\"Die Option führt -> zum Preis CHF 125.00\"", dokument, grenzen(20, 0.5));

        assertThat(ergebnis.belegt()).isTrue();
        assertThat(ergebnis.seiten()).containsExactly(2);
    }

    @Test
    void verlangtInhaltlicheUeberlappungZahlenUndEigennamen() {
        Seitentext dokument = new Seitentext(List.of(
                "Die fiktive Firma Fabel AG bietet eine Zusatzoption zum Preis von CHF 125.00 pro Gerät."));
        WissenIngestGrenzen grenzen = grenzen(20, 0.5);

        assertThat(WissenIngestAuszuege.pruefen("Die Stadthalle wird nächsten Frühling eröffnet.",
                "Die fiktive Firma Fabel AG bietet eine Zusatzoption zum Preis von CHF 125.00 pro Gerät.",
                dokument, grenzen).belegt()).isFalse();
        assertThat(WissenIngestAuszuege.pruefen("Fabel AG bietet eine Zusatzoption für CHF 125.00.",
                "bietet eine Zusatzoption zum Preis von CHF 125.00 pro Gerät.", dokument, grenzen).belegt()).isFalse();
        assertThat(WissenIngestAuszuege.pruefen("Fabel AG bietet eine Zusatzoption für CHF 125.00.",
                "Die fiktive Firma Fabel AG bietet eine Zusatzoption zum Preis von",
                dokument, grenzen).belegt()).isFalse();
    }

    @Test
    void verwendetDieKonfigurierteMindestueberlappung() {
        Seitentext dokument = new Seitentext(List.of(
                "Die Regel bleibt aktiv, bis neue Fristen starten und dokumentiert werden."));
        String aussage = "Die Regel bleibt aktiv, bis Änderungen beginnen.";
        String auszug = "Die Regel bleibt aktiv, bis neue Fristen starten und dokumentiert werden.";

        assertThat(WissenIngestAuszuege.pruefen(aussage, auszug, dokument, grenzen(20, 0.5)).belegt()).isTrue();
        assertThat(WissenIngestAuszuege.pruefen(aussage, auszug, dokument, grenzen(20, 0.75)).belegt()).isFalse();
    }

    @Test
    void vergleichtSchweizerTausendertrennerUndBewahrtDezimalpunkte() {
        Seitentext dokument = new Seitentext(List.of(
                "Total Festpreis 9'725 CHF wird verrechnet.",
                "Projekt umfasst 1 234 567 Einheiten.",
                "Der Zuschlag beträgt 1.5 Prozent."));
        WissenIngestAuszuege.Pruefer pruefer = WissenIngestAuszuege.pruefer(dokument, 0.3);
        WissenIngestGrenzen grenzen = grenzen(20, 0.5);

        assertThat(pruefer.pruefen("Total 9'725 CHF", "Total Festpreis 9'725 CHF wird verrechnet.",
                grenzen).belegt()).isTrue();
        assertThat(pruefer.pruefen("Projekt umfasst 1 234 567 Einheiten.",
                "Projekt umfasst 1234567 Einheiten.", grenzen).belegt()).isTrue();
        assertThat(pruefer.pruefen("Der Zuschlag beträgt 1.5 Prozent.",
                "Der Zuschlag beträgt 1.5 Prozent.", grenzen).belegt()).isTrue();
    }

    private static WissenIngestGrenzen grenzen(int mindestZeichen, double ueberlappung) {
        return new WissenIngestGrenzen(30_000, 15, 60_000, 20_000, 12_000, 600_000, 1_000_000,
                mindestZeichen, ueberlappung);
    }

    private static WissenIngestGrenzen grenzen(int mindestZeichen, double ueberlappung,
                                               double randzeilenMindestAnteil) {
        return new WissenIngestGrenzen(30_000, 15, 60_000, 20_000, 12_000, 600_000, 1_000_000,
                mindestZeichen, ueberlappung, randzeilenMindestAnteil);
    }

    @Test
    void zerlegtSeitenMitMarkernInBegrenzteCodepunktAbschnitte() {
        List<WissenIngestSegmentierer.Abschnitt> abschnitte = WissenIngestSegmentierer.zerlegen(
                new Seitentext(List.of("a😀bc", "de")), 1, 2, 2);

        assertThat(abschnitte).extracting(WissenIngestSegmentierer.Abschnitt::nummer)
                .containsExactly(0, 1, 2);
        assertThat(abschnitte).extracting(WissenIngestSegmentierer.Abschnitt::seite)
                .containsExactly(1, 1, 2);
        assertThat(abschnitte).allSatisfy(abschnitt -> {
            String text = abschnitt.text().substring(abschnitt.text().indexOf('\n') + 1);
            assertThat(text.codePointCount(0, text.length())).isLessThanOrEqualTo(2);
        });
    }
}
