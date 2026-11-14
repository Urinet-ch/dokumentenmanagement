package ch.urinet.archiv.ki.domain.chat;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Titel eines neuen Gesprächs: erste Frage, höchstens 80 Zeichen an der Wortgrenze mit «…». */
class GespraechstitelTest {

    @Test
    void kurzeFrageUnveraendertOhneLeerraumAmRand() {
        assertThat(Gespraechstitel.aus("  Wie hoch waren die Einnahmen 2025?\n")).isEqualTo("Wie hoch waren die Einnahmen 2025?");
    }

    @Test
    void leerraumWirdZusammengefasst() {
        assertThat(Gespraechstitel.aus("Offene\n\nPosten  im\tMärz")).isEqualTo("Offene Posten im März");
    }

    @Test
    void genau80ZeichenBleiben() {
        String frage = "a".repeat(80);
        assertThat(Gespraechstitel.aus(frage)).isEqualTo(frage);
    }

    @Test
    void laengereFrageAnDerWortgrenzeMitAuslassung() {
        String frage = "Welche Rechnungen von Müller & Söhne AG sind seit dem ersten Quartal noch offen und wann "
                + "werden sie fällig?";
        String titel = Gespraechstitel.aus(frage);
        assertThat(titel).isEqualTo("Welche Rechnungen von Müller & Söhne AG sind seit dem ersten Quartal noch offen…");
        assertThat(titel.codePointCount(0, titel.length())).isLessThanOrEqualTo(80);
    }

    @Test
    void schnittGenauVorEinemLeerzeichenBehaeltDasWort() {
        String frage = "b".repeat(79) + " weiter";
        assertThat(Gespraechstitel.aus(frage)).isEqualTo("b".repeat(79) + "…");
    }

    @Test
    void einWortOhneGrenzeWirdHartGekuerzt() {
        assertThat(Gespraechstitel.aus("x".repeat(100))).isEqualTo("x".repeat(79) + "…");
    }

    @Test
    void zaehltCodepunkteNichtUtf16Einheiten() {
        String frage = "😀".repeat(81);
        String titel = Gespraechstitel.aus(frage);
        assertThat(titel).isEqualTo("😀".repeat(79) + "…");
    }
}
