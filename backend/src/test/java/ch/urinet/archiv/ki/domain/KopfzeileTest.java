package ch.urinet.archiv.ki.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Kopfzeile wie im Chunker (Spec 02, Aufgabe 6.4): Text für den Reranker und Inhalt ohne Kopfzeile für den Auszug. */
class KopfzeileTest {

    @Test
    void kopfzeileWieImChunker() {
        String kopf = Kopfzeile.von("Police  2026", "Versicherung", null);
        assertThat(kopf).isEqualTo("Police 2026 – Versicherung");
        Chunk chunk = Chunker.zerlegen(new Chunker.Quelle("Police  2026", "Versicherung", null, "UVG Prämie", null,
                false)).getFirst();
        assertThat(chunk.text()).isEqualTo(Kopfzeile.mitInhalt(kopf, "UVG Prämie"));
        assertThat(Kopfzeile.inhalt(chunk.text(), kopf)).isEqualTo("UVG Prämie");
    }

    @Test
    void ohneKopfzeileOderOhneInhalt() {
        assertThat(Kopfzeile.von(null, " ", null)).isEmpty();
        assertThat(Kopfzeile.mitInhalt("", "Text")).isEqualTo("Text");
        assertThat(Kopfzeile.mitInhalt("Kopf", "")).isEqualTo("Kopf");
        assertThat(Kopfzeile.inhalt("Text\n\nmehr", "")).isEqualTo("Text\n\nmehr");
        assertThat(Kopfzeile.inhalt("Kopf", "Kopf")).isEmpty();
        // geänderte Kopfzeile seit dem Einbetten: der ganze Text
        assertThat(Kopfzeile.inhalt("Alt\n\nText", "Neu")).isEqualTo("Alt\n\nText");
    }
}
