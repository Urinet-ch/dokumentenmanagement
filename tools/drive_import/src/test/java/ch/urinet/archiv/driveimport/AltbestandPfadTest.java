package ch.urinet.archiv.driveimport;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class AltbestandPfadTest {

    @TempDir
    Path tmp;

    @Test
    void erkenntReiheJahrUndAusgabeAusPfadUndDateiname() {
        Path wurzel = tmp.resolve("Altbestand");
        AltbestandPfad.Erkennung erkennung = AltbestandPfad.erkennen(wurzel,
                wurzel.resolve("Bulletin/2026/Ausgabe-3.pdf"));

        assertThat(erkennung.grund()).isNull();
        assertThat(erkennung.pfad()).isEqualTo(new AltbestandPfad("Bulletin", "BULLETIN", 2026, 3));
    }

    @Test
    void jahrZuerstImDateinamenUndReihenUnabhaengigeGrossschreibung() {
        Path wurzel = tmp.resolve("Altbestand");

        assertThat(AltbestandPfad.erkennen(wurzel, wurzel.resolve("mErKbLaTt/2025-12.pdf")).pfad())
                .isEqualTo(new AltbestandPfad("mErKbLaTt", "MERKBLATT", 2025, 12));
    }

    @Test
    void dateistammMitDemJahrIstKeineAusgabenummer() {
        Path wurzel = tmp.resolve("Altbestand");

        assertThat(AltbestandPfad.erkennen(wurzel, wurzel.resolve("Bulletin/2026/2026.pdf")).pfad())
                .isEqualTo(new AltbestandPfad("Bulletin", "BULLETIN", 2026, null));
    }

    @Test
    void prueftGrenzenUndDateinamenvariantenDerAusgabenummer() {
        Path wurzel = tmp.resolve("Altbestand");

        assertThat(pfad(wurzel, "Bulletin/2026/0.pdf").ausgabe()).isNull();
        assertThat(pfad(wurzel, "Bulletin/2026/999.pdf").ausgabe()).isEqualTo(999);
        assertThat(pfad(wurzel, "Bulletin/2026/1000.pdf").ausgabe()).isNull();
        assertThat(pfad(wurzel, "Bulletin/2026/10000.pdf").ausgabe()).isNull();
        assertThat(pfad(wurzel, "Bulletin/2026/10001.pdf").ausgabe()).isNull();
        assertThat(pfad(wurzel, "Bulletin/2026/Ausgabe_3-2026.pdf").ausgabe()).isEqualTo(3);
        assertThat(pfad(wurzel, "Bulletin/2026/3-2026").ausgabe()).isEqualTo(3);
    }

    @Test
    void lehntUnbekannteReihenUndMehrdeutigeJahreAb() {
        Path wurzel = tmp.resolve("Altbestand");

        assertThat(AltbestandPfad.erkennen(wurzel, wurzel.resolve("Unbekannt/2026/1.pdf")).grund())
                .contains("Reihe nicht erkannt");
        assertThat(AltbestandPfad.erkennen(wurzel, wurzel.resolve(" Bulletin/2026/1.pdf")).grund())
                .contains("Reihe nicht erkannt");
        assertThat(AltbestandPfad.erkennen(wurzel, wurzel.resolve("Bulletin/2025/2026/1.pdf")).grund())
                .contains("Jahr mehrdeutig");
    }

    private AltbestandPfad pfad(Path wurzel, String relativ) {
        AltbestandPfad.Erkennung erkennung = AltbestandPfad.erkennen(wurzel, wurzel.resolve(relativ));
        assertThat(erkennung.grund()).isNull();
        return erkennung.pfad();
    }
}
