package ch.urinet.archiv.ki.domain;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class ChunkerTest {

    private static final String KOPF = "Rechnung 2026-003 – Ausgangsrechnung – FC Beispiel";

    private static Chunker.Quelle quelle(String volltext, Integer seiten, boolean pdfaFehlt) {
        return new Chunker.Quelle("Rechnung 2026-003", "Ausgangsrechnung", "FC Beispiel", volltext, seiten, pdfaFehlt);
    }

    /** {@code anzahl} Wörter «w<i>» mit je 9 Zeichen ohne Leerzeichen. */
    private static String woerter(int von, int anzahl) {
        return IntStream.range(von, von + anzahl).mapToObj(i -> "w%08d".formatted(i)).collect(Collectors.joining(" "));
    }

    private static String inhalt(Chunk chunk) {
        assertThat(chunk.text()).startsWith(KOPF + "\n\n");
        return chunk.text().substring(KOPF.length() + 2);
    }

    @Test
    void kurzerTextEinAbschnittMitKopfzeile() {
        List<Chunk> chunks = Chunker.zerlegen(quelle("Erste Zeile\nzweite   Zeile\n\nNeuer Absatz", null, false));
        assertThat(chunks).containsExactly(new Chunk(1, KOPF + "\n\nErste Zeile zweite Zeile\n\nNeuer Absatz", null, null));
    }

    @Test
    void kopfzeileOhneLeereTeile() {
        Chunker.Quelle ohnePartner = new Chunker.Quelle("Titel", " ", null, "Text", null, false);
        assertThat(Chunker.zerlegen(ohnePartner)).extracting(Chunk::text).containsExactly("Titel\n\nText");
    }

    @Test
    void ohneTextNurKopfzeileOhneBeidesNichts() {
        assertThat(Chunker.zerlegen(quelle(null, null, false))).containsExactly(new Chunk(1, KOPF, null, null));
        assertThat(Chunker.zerlegen(quelle(" \n\f ", 1, false))).containsExactly(new Chunk(1, KOPF, null, null));
        assertThat(Chunker.zerlegen(new Chunker.Quelle(null, null, null, "", null, false))).isEmpty();
    }

    @Test
    void langerTextHoechstens3000ZeichenMitUeberlappungAnWortgrenze() {
        // 30 Absätze à 10 Wörter (99 Zeichen)
        String text = IntStream.range(0, 30).mapToObj(i -> woerter(i * 10, 10)).collect(Collectors.joining("\n\n"));
        List<Chunk> chunks = Chunker.zerlegen(quelle(text, null, false));

        assertThat(chunks).hasSizeGreaterThan(1);
        assertThat(chunks).extracting(Chunk::nr).containsExactlyElementsOf(IntStream.rangeClosed(1, chunks.size()).boxed().toList());
        for (int i = 0; i < chunks.size(); i++) {
            String inhalt = inhalt(chunks.get(i));
            assertThat(inhalt.length()).isLessThanOrEqualTo(Chunker.MAX_ZEICHEN);
            assertThat(inhalt).doesNotStartWith(" ").doesNotEndWith(" ");
            if (i > 0) {
                String vorher = inhalt(chunks.get(i - 1));
                // Überlappung: der Anfang des Abschnitts ist das Ende des vorigen, höchstens 400 Zeichen, ganze Wörter
                String ueberlappung = vorher.substring(vorher.length() - Chunker.UEBERLAPPUNG);
                String gemeinsam = ueberlappung.substring(ueberlappung.indexOf('w'));
                assertThat(inhalt).startsWith(gemeinsam);
                assertThat(gemeinsam.length()).isBetween(Chunker.UEBERLAPPUNG - 120, Chunker.UEBERLAPPUNG);
            }
        }
        // jedes Wort kommt vor, das letzte im letzten Abschnitt
        String alle = chunks.stream().map(ChunkerTest::inhalt).collect(Collectors.joining(" "));
        IntStream.range(0, 300).forEach(i -> assertThat(alle).contains("w%08d".formatted(i)));
        assertThat(inhalt(chunks.getLast())).endsWith("w%08d".formatted(299));
    }

    @Test
    void langerAbsatzWirdAnLeerraumGeteilt() {
        List<Chunk> chunks = Chunker.zerlegen(quelle(woerter(0, 1000), null, false));

        assertThat(chunks).hasSizeGreaterThan(3);
        chunks.forEach(chunk -> {
            assertThat(inhalt(chunk).length()).isLessThanOrEqualTo(Chunker.MAX_ZEICHEN);
            assertThat(inhalt(chunk)).matches("w\\d{8}( w\\d{8}|\\n\\nw\\d{8})*");
        });
    }

    @Test
    void wortOhneLeerraumWirdHartGeteiltOhneSurrogatZuTrennen() {
        String ohneLeerraum = "a".repeat(Chunker.MAX_ABSATZ - 1) + "😀" + "b".repeat(100);
        List<Chunk> chunks = Chunker.zerlegen(quelle(ohneLeerraum, null, false));

        String zusammen = chunks.stream().map(ChunkerTest::inhalt).collect(Collectors.joining());
        assertThat(zusammen).contains("😀");
        chunks.forEach(chunk -> {
            String inhalt = inhalt(chunk);
            assertThat(inhalt.length()).isLessThanOrEqualTo(Chunker.MAX_ZEICHEN);
            assertThat(Character.isHighSurrogate(inhalt.charAt(inhalt.length() - 1))).isFalse();
            assertThat(Character.isLowSurrogate(inhalt.charAt(0))).isFalse();
        });
    }

    @Test
    void seitenbereichAusSeitentrennern() {
        String seite = IntStream.range(0, 4).mapToObj(i -> woerter(i * 50, 50)).collect(Collectors.joining("\n\n"));
        String text = seite + "\f" + seite.replace('w', 'x') + "\f" + "Letzte Seite\f";
        List<Chunk> chunks = Chunker.zerlegen(quelle(text, 3, false));

        assertThat(chunks.getFirst().seiteVon()).isEqualTo(1);
        assertThat(chunks.getLast().seiteBis()).isEqualTo(3);
        assertThat(chunks).allSatisfy(chunk -> {
            assertThat(chunk.seiteVon()).isNotNull();
            assertThat(chunk.seiteVon()).isLessThanOrEqualTo(chunk.seiteBis());
        });
        Chunk mitX = chunks.stream().filter(c -> inhalt(c).startsWith("x")).findFirst().orElseThrow();
        assertThat(mitX.seiteVon()).isEqualTo(2);
    }

    @Test
    void ohneSeitenbereichBeiPdfaFehltOderAbweichenderSeitenzahl() {
        String text = "Seite eins\fSeite zwei\f";
        assertThat(Chunker.zerlegen(quelle(text, 2, true))).singleElement()
                .satisfies(c -> assertThat(c.seiteVon()).isNull())
                .satisfies(c -> assertThat(inhalt(c)).isEqualTo("Seite eins\n\nSeite zwei"));
        assertThat(Chunker.zerlegen(quelle(text, 3, false))).singleElement()
                .satisfies(c -> assertThat(c.seiteVon()).isNull());
        assertThat(Chunker.zerlegen(quelle(text, 2, false))).singleElement()
                .satisfies(c -> assertThat(c.seiteVon()).isEqualTo(1))
                .satisfies(c -> assertThat(c.seiteBis()).isEqualTo(2));
    }
}
