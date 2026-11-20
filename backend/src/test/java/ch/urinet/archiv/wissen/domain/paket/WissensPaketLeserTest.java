package ch.urinet.archiv.wissen.domain.paket;

import ch.urinet.archiv.wissen.domain.Seitentyp;
import ch.urinet.archiv.wissen.domain.WissensPruefung;
import ch.urinet.archiv.wissen.domain.Wissensseite;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

import static ch.urinet.archiv.wissen.domain.paket.WissensPaketBeispiele.signatur;
import static ch.urinet.archiv.wissen.domain.paket.WissensPaketBeispiele.zip;
import static org.assertj.core.api.Assertions.*;

class WissensPaketLeserTest {
    private static final String KOPF = """
            paket_format: 1
            mandant: urinet
            erzeugt_von: archiv/export
            erzeugt_mit: lokal
            kontext_stufe: NORMAL
            erzeugt_am: '2026-11-18T14:05:00Z'
            herkunft: {instanz: lokal}
            """;
    private static final String SEITE = """
            ---
            type: Konzept
            title: Test
            description: Beschreibung
            tags: [test]
            stale_after: null
            basis_version: 3
            status: stable
            sources:
              - id: q-test
                resource: 'urn:urinet-archiv:urinet:dokument:1'
                sha256: 'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa'
                seite: '1-2'
                auszug: Test
                title: Titel
                last_modified: null
              - id: q-seite
                resource: /quellen/test.md
            ---
            Inhalt.
            """;
    private final WissensPaketLeser leser = new WissensPaketLeser();

    @Test void exporterFormatUndHinweise() throws Exception {
        WissensPaket paket = leser.lesen(zip(Map.of("paket.yaml", KOPF, "konzepte/test.md", SEITE,
                "index.md", "ignoriert", "log.md", "ignoriert")));
        assertThat(paket.kopf().instanz()).isEqualTo("lokal");
        assertThat(paket.seiten()).singleElement().satisfies(s -> {
            assertThat(s.pfad()).isEqualTo("konzepte/test");
            assertThat(s.staleAfter()).isNull();
            assertThat(s.basisVersion()).isEqualTo(3);
            assertThat(s.sources()).hasSize(2);
            assertThat(s.sources().getFirst().seite()).isEqualTo("1-2");
        });
        assertThat(paket.hinweise()).extracting(WissensPaket.Hinweis::code)
                .containsExactlyInAnyOrder(WissensPaket.HinweisCode.FELD_IGNORIERT,
                        WissensPaket.HinweisCode.DATEI_IGNORIERT, WissensPaket.HinweisCode.DATEI_IGNORIERT);
        assertThatThrownBy(() -> paket.seiten().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThat(leser.lesen(zip(Map.of("paket.yaml", KOPF))).seiten()).isEmpty();
    }

    @ParameterizedTest
    @MethodSource("beispiele")
    void gemeinsameBeispiele(WissensPaketBeispiele.Beispiel beispiel) {
        if (beispiel.readerCode() != null) {
            assertCode(beispiel.zip(), WissensPaketException.Code.valueOf(beispiel.readerCode()));
            return;
        }
        WissensPaket paket = leser.lesen(beispiel.zip());
        if (beispiel.name().equals("hinweise")) {
            assertThat(paket.hinweise()).containsExactlyInAnyOrder(
                    new WissensPaket.Hinweis(WissensPaket.HinweisCode.DATEI_IGNORIERT, "index.md"),
                    new WissensPaket.Hinweis(WissensPaket.HinweisCode.DATEI_IGNORIERT, "log.md"),
                    new WissensPaket.Hinweis(WissensPaket.HinweisCode.FELD_IGNORIERT, "konzepte/test.md"),
                    new WissensPaket.Hinweis(WissensPaket.HinweisCode.FELD_IGNORIERT, "konzepte/test.md"),
                    new WissensPaket.Hinweis(WissensPaket.HinweisCode.FELD_IGNORIERT, "konzepte/test.md"),
                    new WissensPaket.Hinweis(WissensPaket.HinweisCode.FELD_UNBEKANNT, "konzepte/test.md"),
                    new WissensPaket.Hinweis(WissensPaket.HinweisCode.FELD_UNBEKANNT, "paket.yaml"));
        } else if (beispiel.name().equals("sha-und-urn")) {
            PaketQuelle quelle = paket.seiten().getFirst().sources().getFirst();
            assertThat(quelle.sha256()).isEqualTo("a".repeat(64));
            assertThat(quelle.resource()).isNull();
        }
        List<String> codes = paket.seiten().stream().flatMap(s -> {
            Wissensseite seite = new Wissensseite(null, s.pfad(), Seitentyp.vonDatenbankwert(s.typ()).orElseThrow(),
                    s.titel(), s.beschreibung(), s.tags(), Wissensseite.Status.AKTIV, s.staleAfter(), List.of(),
                    Wissensseite.Vertraulichkeit.NORMAL, s.inhalt(), 1, null, null, null, null, false, false, null);
            return WissensPruefung.pruefen(seite, WissensPruefung.Kontext.leer()).befunde().stream();
        }).map(befund -> befund.code().name()).filter(code -> code.equals("LINK_UNGUELTIG")).distinct().toList();
        assertThat(codes).containsExactlyInAnyOrderElementsOf(beispiel.befunde());
    }

    static List<WissensPaketBeispiele.Beispiel> beispiele() { return WissensPaketBeispiele.alle(); }

    @ParameterizedTest
    @ValueSource(strings = {"extra: !!str text\n", "extra: &a text\n", "extra: [*a]\n",
            "<<: {x: 1}\n", "---\nx: 1\n", "extra: [[[[[x]]]]]\n", "mandant: fremd\n"})
    void yamlStrikteTeilmenge(String zusatz) throws Exception {
        assertCode(zip(Map.of("paket.yaml", KOPF + zusatz)), WissensPaketException.Code.YAML);
    }

    @Test void cloudGrenze() throws Exception {
        assertCode(zip(Map.of("paket.yaml", KOPF.replace("lokal", "cloud")
                .replace("NORMAL", "INTERN"))), WissensPaketException.Code.CLOUD_STUFE);
    }

    @Test void byteGrenzenUndFrist() throws Exception {
        assertCode(zip(Map.of("paket.yaml", KOPF, "konzepte/test.md", "x".repeat(131073))),
                WissensPaketException.Code.GRENZE);
        assertThatThrownBy(() -> leser.lesen(zip(Map.of("paket.yaml", KOPF)), System.nanoTime() - 1))
                .isInstanceOfSatisfying(WissensPaketException.class,
                        e -> assertThat(e.code()).isEqualTo(WissensPaketException.Code.ZEIT));
    }

    @Test void numerischePruefsummeUndUnbekannteFelder() throws Exception {
        String hash = "0".repeat(63) + "1";
        String seite = SEITE.replace("'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa'", hash)
                .replace("status: stable", "status: stable\nunbekannt: wert");
        WissensPaket paket = leser.lesen(zip(Map.of("paket.yaml", KOPF, "konzepte/test.md", seite)));
        assertThat(paket.seiten().getFirst().sources().getFirst().sha256()).isEqualTo(hash);
        assertThat(paket.hinweise()).extracting(WissensPaket.Hinweis::code)
                .contains(WissensPaket.HinweisCode.FELD_UNBEKANNT);
    }

    @Test void doppelteNamenUndIndividuelleGrenzen() throws Exception {
        byte[] zip = zip(Map.of("paket.yaml", KOPF, "konzepte/aaa.md", SEITE, "konzepte/bbb.md", SEITE));
        byte[] alt = "konzepte/bbb.md".getBytes(StandardCharsets.UTF_8);
        byte[] neu = "konzepte/aaa.md".getBytes(StandardCharsets.UTF_8);
        for (int i = 0; i <= zip.length - alt.length; i++) {
            if (java.util.Arrays.equals(zip, i, i + alt.length, alt, 0, alt.length))
                System.arraycopy(neu, 0, zip, i, neu.length);
        }
        assertCode(zip, WissensPaketException.Code.DUPLIKAT);
        byte[] gueltig = zip(Map.of("paket.yaml", KOPF));
        PaketGrenzen g = new PaketGrenzen(gueltig.length - 1, 200_000, 131_072, 16_384,
                200, 205, 4, 100, 5, Duration.ofSeconds(60));
        assertThatThrownBy(() -> new WissensPaketLeser(g).lesen(gueltig))
                .isInstanceOfSatisfying(WissensPaketException.class,
                        e -> assertThat(e.code()).isEqualTo(WissensPaketException.Code.DATEI_ZU_GROSS));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "id: x\n", "id: x\nresource: /andere/test.md\n", "id: x\nresource: /konzepte/../x.md\n",
            "id: x\nresource: urn:urinet-archiv:urinet:dokument:1\n",
            "id: x\nsha256: abc\n", "id: x\nsha256: aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\nresource: /konzepte/x.md\n",
            "id: x\nresource: /konzepte/x.md\nseite: 1\n",
            "id: x\nsha256: aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\nseite: '0'\n",
            "id: x\nsha256: aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\nseite: '5-2'\n",
            "id: x\nsha256: aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\nseite: '1-x'\n"})
    void ungueltigeQuellenform(String quelle) throws Exception {
        String frontmatter = "---\ntype: Konzept\ntitle: Titel\ndescription: Text\ntags: []\nstale_after: null\n"
                + "sources:\n  - " + quelle.replace("\n", "\n    ") + "\n---\nText\n";
        assertCode(zip(Map.of("paket.yaml", KOPF, "konzepte/test.md", frontmatter)),
                WissensPaketException.Code.FORMAT);
    }

    @Test void quellenNormalisierenUndAuszugDarfFehlen() throws Exception {
        String seite = SEITE.replace("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                        "ABCDEF0123456789".repeat(4))
                .replace("    auszug: Test\n", "");
        PaketQuelle quelle = leser.lesen(zip(Map.of("paket.yaml", KOPF, "konzepte/test.md", seite)))
                .seiten().getFirst().sources().getFirst();
        assertThat(quelle.sha256()).isEqualTo("abcdef0123456789".repeat(4));
        assertThat(quelle.resource()).isNull();
        assertThat(quelle.auszug()).isNull();
    }

    @Test void ungueltigerSeitentypUndErzeuger() throws Exception {
        assertCode(zip(Map.of("paket.yaml", KOPF, "konzepte/test.md", SEITE.replace("type: Konzept", "type: Sonstiges"))),
                WissensPaketException.Code.FORMAT);
        assertCode(zip(Map.of("paket.yaml", KOPF, "konzepte/test.md",
                SEITE.replace("status: stable", "erzeugt_von: 'ä'\nstatus: stable"))),
                WissensPaketException.Code.FORMAT);
        assertCode(zip(Map.of("paket.yaml", KOPF, "konzepte/test.md",
                SEITE.replace("status: stable", "erzeugt_von: '" + "x".repeat(101) + "'\nstatus: stable"))),
                WissensPaketException.Code.FORMAT);
    }

    @Test void tatsaechlicheBytesStattGefaelschterMetadaten() throws Exception {
        byte[] zip = zip(Map.of("konzepte/test.md", "x".repeat(131073)));
        int c = signatur(zip, 0x02014b50);
        zahl(zip, c + 24, 1);
        zahl(zip, c - 4, 1);
        assertCode(zip, WissensPaketException.Code.GRENZE);
    }

    @Test void exakteDateigrenze() throws Exception {
        String seite = SEITE + "x".repeat(131072 - SEITE.getBytes(StandardCharsets.UTF_8).length);
        assertThat(leser.lesen(zip(Map.of("paket.yaml", KOPF, "konzepte/test.md", seite), Deflater.NO_COMPRESSION))
                .seiten()).hasSize(1);
        assertCode(zip(Map.of("paket.yaml", KOPF, "konzepte/test.md", seite + "x"), Deflater.NO_COMPRESSION),
                WissensPaketException.Code.GRENZE);
    }

    @Test void exaktesKompressionsverhaeltnis() throws Exception {
        byte[] exakt = HexFormat.of().parseHex("4b4c1c05a360148c82a10100");
        byte[] darueber = HexFormat.of().parseHex("4b4c1c05a360148c82210200");
        assertThat(leser.lesen(zipMitKomprimiertemLog(exakt, 1200)).hinweise())
                .extracting(WissensPaket.Hinweis::code).contains(WissensPaket.HinweisCode.DATEI_IGNORIERT);
        assertCode(zipMitKomprimiertemLog(darueber, 1201), WissensPaketException.Code.ZIP_BOMBE);
    }

    @Test void metadatenMuessenLokalenEintraegenEntsprechen() throws Exception {
        for (int position : new int[] {6, 8, 30}) {
            byte[] daten = zip(Map.of("paket.yaml", KOPF));
            daten[position] ^= 1;
            assertCode(daten, WissensPaketException.Code.ZIP_UNGUELTIG);
        }
        byte[] offset = zip(Map.of("paket.yaml", KOPF));
        offset[signatur(offset, 0x02014b50) + 42] = 1;
        assertCode(offset, WissensPaketException.Code.ZIP_UNGUELTIG);
        byte[] versteckt = zip(Map.of("paket.yaml", KOPF));
        int ende = signatur(versteckt, 0x06054b50);
        versteckt[ende + 8] = 0;
        versteckt[ende + 10] = 0;
        assertCode(versteckt, WissensPaketException.Code.ZIP_UNGUELTIG);
    }

    @Test void grenzenDuerfenNichtGelockertWerden() {
        assertThatIllegalArgumentException().isThrownBy(() -> new PaketGrenzen(10 * 1024 * 1024 + 1,
                20 * 1024 * 1024, 131072, 16384, 200, 205, 4, 100, 5, Duration.ofSeconds(60)));
        assertThatIllegalArgumentException().isThrownBy(() -> new PaketGrenzen(10 * 1024 * 1024,
                20 * 1024 * 1024, 131072, 16384, 200, 205, 4, 100, 5, Duration.ofSeconds(61)));
    }

    private void assertCode(byte[] zip, WissensPaketException.Code code) {
        assertThatThrownBy(() -> leser.lesen(zip)).isInstanceOfSatisfying(WissensPaketException.class, e -> {
            assertThat(e.code()).isEqualTo(code);
            assertThat(e.getMessage()).isEqualTo(code.name());
        });
    }
    private static byte[] zipMitKomprimiertemLog(byte[] komprimiert, int laenge) {
        ByteArrayOutputStream lokal = new ByteArrayOutputStream();
        ByteArrayOutputStream zentral = new ByteArrayOutputStream();
        byte[] kopf = KOPF.getBytes(StandardCharsets.UTF_8);
        eintrag(lokal, zentral, "paket.yaml", kopf, kopf, 0);
        byte[] log = "a".repeat(laenge).getBytes(StandardCharsets.UTF_8);
        eintrag(lokal, zentral, "log.md", log, komprimiert, 8);
        int start = lokal.size();
        lokal.writeBytes(zentral.toByteArray());
        zahl(lokal, 0x06054b50, 4);
        zahl(lokal, 0, 2); zahl(lokal, 0, 2);
        zahl(lokal, 2, 2); zahl(lokal, 2, 2);
        zahl(lokal, zentral.size(), 4); zahl(lokal, start, 4); zahl(lokal, 0, 2);
        return lokal.toByteArray();
    }
    private static void eintrag(ByteArrayOutputStream lokal, ByteArrayOutputStream zentral, String name,
                                 byte[] inhalt, byte[] komprimiert, int methode) {
        byte[] namensBytes = name.getBytes(StandardCharsets.UTF_8);
        CRC32 crc = new CRC32();
        crc.update(inhalt);
        int offset = lokal.size();
        zahl(lokal, 0x04034b50, 4); zahl(lokal, 20, 2); zahl(lokal, 0, 2); zahl(lokal, methode, 2);
        zahl(lokal, 0, 2); zahl(lokal, 0, 2); zahl(lokal, crc.getValue(), 4);
        zahl(lokal, komprimiert.length, 4); zahl(lokal, inhalt.length, 4);
        zahl(lokal, namensBytes.length, 2); zahl(lokal, 0, 2);
        lokal.writeBytes(namensBytes); lokal.writeBytes(komprimiert);
        zahl(zentral, 0x02014b50, 4); zahl(zentral, 20, 2); zahl(zentral, 20, 2);
        zahl(zentral, 0, 2); zahl(zentral, methode, 2); zahl(zentral, 0, 2); zahl(zentral, 0, 2);
        zahl(zentral, crc.getValue(), 4); zahl(zentral, komprimiert.length, 4); zahl(zentral, inhalt.length, 4);
        zahl(zentral, namensBytes.length, 2); zahl(zentral, 0, 2); zahl(zentral, 0, 2);
        zahl(zentral, 0, 2); zahl(zentral, 0, 2); zahl(zentral, 0, 4); zahl(zentral, offset, 4);
        zentral.writeBytes(namensBytes);
    }
    private static void zahl(ByteArrayOutputStream out, long wert, int bytes) {
        for (int i = 0; i < bytes; i++) out.write((int) (wert >>> (8 * i)) & 255);
    }
    private static void zahl(byte[] daten, int offset, int zahl) {
        for (int i = 0; i < 4; i++) daten[offset + i] = (byte) (zahl >>> (8 * i));
    }
}
