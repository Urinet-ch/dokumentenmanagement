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
    void lehntUnbekannteReihenUndMehrdeutigeJahreAb() {
        Path wurzel = tmp.resolve("Altbestand");

        assertThat(AltbestandPfad.erkennen(wurzel, wurzel.resolve("Unbekannt/2026/1.pdf")).grund())
                .contains("Reihe nicht erkannt");
        assertThat(AltbestandPfad.erkennen(wurzel, wurzel.resolve(" Bulletin/2026/1.pdf")).grund())
                .contains("Reihe nicht erkannt");
        assertThat(AltbestandPfad.erkennen(wurzel, wurzel.resolve("Bulletin/2025/2026/1.pdf")).grund())
                .contains("Jahr mehrdeutig");
    }
}
