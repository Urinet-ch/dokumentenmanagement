package ch.urinet.archiv.ablage.domain;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Gespeicherte Pfade zurück in Ablageort und Schlüssel lesen (Download, Req 14.6). */
class AblagePfadTest {

    private static final UUID ID = UUID.fromString("0b6f3c1e-8a52-4c9e-9f3a-2d7e5b1c4a90");

    @Test
    void liestJedenPfadEinerDokumentdateiZurueck() {
        for (DateiSchluessel schluessel : List.of(DateiSchluessel.pdfa(2026, ID),
                DateiSchluessel.original(2026, ID, 2, "heic"), DateiSchluessel.arbeitsfassung(2025, ID, 12))) {
            for (Ablageort ort : List.of(Ablageort.EINGANG, Ablageort.ARCHIV)) {
                assertThat(AblagePfad.lesen(schluessel.pfad(ort))).isEqualTo(new AblagePfad(ort, schluessel));
            }
        }
    }

    @Test
    void anderePfadeSindKeineDokumentdatei() {
        for (String pfad : List.of("manifest/2026/01.json", "vorschau/2026/" + ID + "/1.jpg",
                "archiv/2026/" + ID + "/pdfa.pdf/../x", "archiv/2026/" + ID.toString().toUpperCase() + "/pdfa.pdf",
                "../archiv/2026/" + ID + "/pdfa.pdf", "archiv/2026/" + ID + "/1-original.PDF")) {
            assertThatThrownBy(() -> AblagePfad.lesen(pfad)).as(pfad).isInstanceOf(IllegalArgumentException.class);
        }
    }
}
