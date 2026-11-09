package ch.urinet.archiv.spike.golden;

import ch.urinet.archiv.spike.golden.GoldenPipeline.BelegErgebnis;
import ch.urinet.archiv.spike.golden.MetadatenVorschlag.Sicherheit;
import ch.urinet.archiv.spike.golden.Vergleich.Feld;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Req 0.9: Fehler ab Schwelle (>=) und kleinste Schwelle mit höchstens einem Fehler. */
class BerichtTest {

    static BelegErgebnis ergebnis(String id, Map<Feld, Double> sicherheit, Feld... falsch) {
        return ergebnis(id, "QUITTUNG", GoldenPipeline.Eingabe.TEXT, false, sicherheit, falsch);
    }

    static BelegErgebnis ergebnis(String id, String kategorie, GoldenPipeline.Eingabe eingabe, boolean umgestuft,
                                  Map<Feld, Double> sicherheit, Feld... falsch) {
        Map<Feld, Boolean> richtig = new EnumMap<>(Feld.class);
        for (Feld f : Feld.values()) {
            richtig.put(f, !List.of(falsch).contains(f));
        }
        Nachbearbeitung.Ergebnis v = new Nachbearbeitung.Ergebnis("QUITTUNG", null, null, null, null, null, null, null,
                null, null, false, sicherheit, "AUSGANGSRECHNUNG", 0.9, umgestuft);
        Erwartung e = new Erwartung(id, null, false, "abstimmen", kategorie, "QUITTUNG", null, null, null, null, List.of());
        return new BelegErgebnis(e, v, richtig, false, 1, 100, 50, false, 10, 20, null, eingabe);
    }

    static Map<Feld, Double> s(double wert) {
        Map<Feld, Double> m = new EnumMap<>(Feld.class);
        for (Feld f : Feld.values()) {
            m.put(f, wert);
        }
        return m;
    }

    @Test
    void schwelleUndKennzahlen() {
        List<BelegErgebnis> r = List.of(
                ergebnis("a", s(0.95), Feld.DATUM),
                ergebnis("b", s(0.80), Feld.PARTNER),
                ergebnis("c", s(0.60), Feld.BETRAG),
                ergebnis("d", s(0.99)));
        assertThat(Bericht.fehlerUeberSchwelle(r, 0.8)).isEqualTo(2);
        assertThat(Bericht.fehlerUeberSchwelle(r, 0.81)).isEqualTo(1);
        assertThat(Bericht.kleinsteSchwelle(r)).isEqualByComparingTo(new BigDecimal("0.81"));
        Bericht.Kennzahlen k = Bericht.berechne(r, 0.8, 0.20, 0.75);
        assertThat(k.gateRichtig()).isEqualTo(1);
        assertThat(k.richtig().get(Feld.ART)).isEqualTo(4);
        assertThat(k.fehlschlaege()).containsExactly("a: DATUM", "b: PARTNER", "c: BETRAG");
        assertThat(k.tokensEin()).isEqualTo(400);
    }

    @Test
    void keineSchwelleWennZweiFehlerMitSicherheitEins() {
        List<BelegErgebnis> r = List.of(ergebnis("a", s(1.0), Feld.DATUM), ergebnis("b", s(1.0), Feld.ART));
        assertThat(Bericht.kleinsteSchwelle(r)).isNull();
        assertThat(Bericht.kleinsteSchwelle(List.of(ergebnis("a", s(1.0), Feld.DATUM)))).isEqualByComparingTo("0");
    }

    @Test
    void sicherheitDefaultsNichtNoetig() {
        assertThat(new Sicherheit(null, null, null, null, null, null, null, null, null, null).art()).isNull();
    }

    @Test
    void vollberichtEnthaeltRoheArtUndSicherheit(@TempDir Path tmp) throws Exception {
        List<BelegErgebnis> r = List.of(ergebnis("a", s(0.95), Feld.ART));
        Bericht.Lauf lauf = new Bericht.Lauf(Modus.ABSTIMMEN, false, "t", "v3-x", "m", 0.8, 0, 0, 0.2, 0.75);
        Bericht.schreibe(tmp, lauf, r, Bericht.berechne(r, 0.8, 0.2, 0.75));
        JsonNode beleg = new ObjectMapper().readTree(tmp.resolve("ergebnisse.json").toFile()).path("belege").get(0);
        assertThat(beleg.path("artRoh").asText()).isEqualTo("AUSGANGSRECHNUNG");
        assertThat(beleg.path("artRohSicherheit").asDouble()).isEqualTo(0.9);
        assertThat(beleg.path("vorschlag").path("art").asText()).isEqualTo("QUITTUNG");
        assertThat(beleg.path("eingabe").asText()).isEqualTo("TEXT");
    }

    @Test
    void ordnerNameMitSekundenUndLimit() {
        LocalDateTime t = LocalDateTime.of(2026, 11, 9, 14, 45, 7);
        assertThat(Bericht.ordnerName(t, Modus.ABSTIMMEN, false, 0)).isEqualTo("2026-11-09-144507-abstimmen");
        assertThat(Bericht.ordnerName(t, Modus.ABSTIMMEN, false, 1)).isEqualTo("2026-11-09-144507-abstimmen-limit1");
        assertThat(Bericht.ordnerName(t, Modus.ABSTIMMEN, true, 3)).isEqualTo("2026-11-09-144507-abstimmen-k1-limit3");
    }

    @Test
    void fehlenderBerichteOrdnerWirdAngelegt(@TempDir Path tmp) throws Exception {
        Path ordner = Bericht.legeLaufordnerAn(tmp.resolve("berichte"), "lauf");
        assertThat(ordner).isDirectory().isEqualTo(tmp.resolve("berichte/lauf"));
    }

    @Test
    void vorhandenerLaufordnerBrichtAbUndBleibtUnveraendert(@TempDir Path tmp) throws Exception {
        Path alt = Files.createDirectories(tmp.resolve("berichte/lauf"));
        Files.writeString(alt.resolve("ergebnisse.json"), "alt");
        assertThatThrownBy(() -> Bericht.legeLaufordnerAn(tmp.resolve("berichte"), "lauf"))
                .isInstanceOf(FileAlreadyExistsException.class);
        List<BelegErgebnis> r = List.of(ergebnis("a", s(0.9)));
        Bericht.Lauf lauf = new Bericht.Lauf(Modus.ABSTIMMEN, false, "t", "v5-x", "m", 0.8, 0, 0, 0.2, 0.75);
        assertThatThrownBy(() -> Bericht.schreibe(alt, lauf, r, Bericht.berechne(r, 0.8, 0.2, 0.75)))
                .isInstanceOf(FileAlreadyExistsException.class);
        assertThat(alt.resolve("ergebnisse.json")).hasContent("alt");
    }

    @Test
    void bildUndTextGetrenntKontrolleFotosArtRohUndUmstufungen() {
        List<BelegErgebnis> r = List.of(
                ergebnis("foto", "HANDY_FOTO", GoldenPipeline.Eingabe.BILD, false, s(0.9)),
                ergebnis("foto-text", "HANDY_FOTO", GoldenPipeline.Eingabe.TEXT, false, s(0.9)),
                ergebnis("pdf", "QUITTUNG", GoldenPipeline.Eingabe.TEXT, true, s(0.9)));
        Bericht.Kennzahlen k = Bericht.berechne(r, 0.8, 0.20, 0.75);
        assertThat(k.belegeBild()).isEqualTo(1);
        assertThat(k.tokensEinBild()).isEqualTo(100);
        assertThat(k.tokensAusBild()).isEqualTo(50);
        assertThat(k.kostenBild() + k.kostenText()).isCloseTo(k.kostenChf(), org.assertj.core.data.Offset.offset(1e-12));
        assertThat(k.fotosNichtAlsBild()).containsExactly("foto-text");
        assertThat(k.artRohRichtig()).isZero();
        assertThat(k.umstufungen()).containsExactly("pdf: AUSGANGSRECHNUNG → QUITTUNG");
        String md = Bericht.kennzahlenMarkdown(new Bericht.Lauf(Modus.ABSTIMMEN, false, "t", "v5-x", "m", 0.8, 0, 0, 0.2, 0.75), k);
        assertThat(md).contains("Abweichung bei foto-text").contains("Umstufungen: 1").contains("Art roh (Modell) richtig: 0/3");
    }
}
