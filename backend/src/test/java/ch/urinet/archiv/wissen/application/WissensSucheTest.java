package ch.urinet.archiv.wissen.application;

import ch.urinet.archiv.ki.application.EmbeddingPort;
import ch.urinet.archiv.ki.application.KiPort;
import ch.urinet.archiv.ki.application.RerankerPort;
import ch.urinet.archiv.gemeinsam.domain.FachFehler;
import ch.urinet.archiv.gemeinsam.domain.Fehlercode;
import ch.urinet.archiv.sicherheit.application.Berechtigung;
import ch.urinet.archiv.sicherheit.domain.Benutzer;
import ch.urinet.archiv.sicherheit.domain.Rolle;
import ch.urinet.archiv.suche.application.HybridSuche;
import ch.urinet.archiv.suche.application.SucheTransaktion;
import ch.urinet.archiv.wissen.domain.Seitentyp;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WissensSucheTest {
    private static final Benutzer BENUTZER = new Benutzer("benutzer", Rolle.INHABER);
    private static final KiPort.Verbrauch VERBRAUCH = new KiPort.Verbrauch("test", 1, 0);
    private final Transaktion transaktion = new Transaktion();
    private final Port port = new Port();
    private final Embedding embedding = new Embedding();
    private final Reranker reranker = new Reranker();
    private final Budget budget = new Budget();
    private final List<String> zwecke = new ArrayList<>();
    private final WissensSuche suche = new WissensSuche(port,
            new HybridSuche(null, null, embedding, reranker, (zweck, verbrauch) -> zwecke.add(zweck),
                    new SucheTransaktion()),
            transaktion, new Berechtigung(null, null), budget);

    @Test
    void vereinigtBeideListenUndVerwendetRerankerReihenfolge() {
        var a = seite("A", 1);
        var b = seite("B", 1);
        var c = seite("C", 1);
        port.volltext = List.of(a, b);
        port.vektor = List.of(b, c);
        port.aktuell = List.of(a, b, c);
        var ergebnis = suchen(true);
        assertThat(reranker.texte).containsExactly("B", "A", "C");
        assertThat(ergebnis.treffer()).extracting(WissenLesenRepository.IndexZeile::titel)
                .containsExactly("C", "A", "B");
        assertThat(ergebnis.degradiert()).isFalse();
        assertThat(zwecke).containsExactly("WISSEN_EMBEDDING", "WISSEN_EMBEDDING");
    }

    @Test
    void entferntUnsichtbareVorRerankerUndPrueftVorRueckgabeErneut() {
        var a = seite("A", 1);
        var b = seite("B", 1);
        var geheim = seite("geheim", 1);
        port.volltext = List.of(a, b, geheim);
        port.vektor = List.of(geheim, a);
        port.aktuell = List.of(a, b);
        reranker.nachAufruf = () -> port.aktuell = List.of(a);
        assertThat(suchen(true).treffer()).extracting(WissenLesenRepository.IndexZeile::titel)
                .containsExactly("A");
        assertThat(reranker.texte).doesNotContain("geheim");
    }

    @Test
    void verwendetKeinenAbschnittEinerUeberholtenVersion() {
        var alt = seite("alter Abschnitt", 1);
        var aktuell = new WissensSuchePort.Treffer(alt.seite(), 2, "aktueller Inhalt");
        port.volltext = List.of(alt);
        port.vektor = List.of(alt);
        port.aktuell = List.of(aktuell);
        suchen(true);
        assertThat(reranker.texte).containsExactly("aktueller Inhalt");
    }

    @Test
    void stubUndRerankerAusfallFallenAufErneutSichtbarenVolltextZurueck() {
        var a = seite("A", 1);
        var geheim = seite("geheim", 1);
        port.volltext = List.of(a, geheim);
        port.aktuell = List.of(a);
        embedding.antwort = new EmbeddingPort.OhneVektoren();
        assertThat(suchen(true).degradiert()).isTrue();
        assertThat(reranker.aufrufe).isZero();
        embedding.antwort = new EmbeddingPort.Vektoren(List.of(new float[]{1}), VERBRAUCH);
        reranker.ausfall = true;
        var ergebnis = suchen(true);
        assertThat(ergebnis.degradiert()).isTrue();
        assertThat(ergebnis.treffer()).containsExactly(a.seite());
    }

    @Test
    void expliziterVolltextRuftKeineModelleAufUndBleibtUnveraendertRangiert() {
        var a = seite("A", 1);
        var b = seite("B", 1);
        port.volltext = List.of(a, b);
        port.aktuell = List.of(a, b);
        var ergebnis = suchen(false);
        assertThat(ergebnis.treffer()).containsExactly(a.seite(), b.seite());
        assertThat(ergebnis.degradiert()).isFalse();
        assertThat(embedding.aufrufe).isZero();
        assertThat(reranker.aufrufe).isZero();
    }

    @Test
    void datenbankfehlerWirdNichtAlsErfolgreicherRueckfallVersteckt() {
        port.fehler = true;
        assertThatThrownBy(() -> suchen(true)).isInstanceOf(IllegalStateException.class).hasMessage("Datenbank");
        assertThat(embedding.aufrufe).isZero();
    }

    @Test
    void erschoepftesBudgetIstEinFehlerUndKeinErfolgreicherRueckfall() {
        budget.erschoepft = true;
        assertThatThrownBy(() -> suchen(true)).isInstanceOf(FachFehler.class);
        assertThat(embedding.aufrufe).isZero();
        assertThat(reranker.aufrufe).isZero();
    }

    private WissensSuche.Ergebnis suchen(boolean hybrid) {
        return suche.suchen(BENUTZER, "Frage", null, null, null, 30, hybrid);
    }

    private static WissensSuchePort.Treffer seite(String titel, int version) {
        return new WissensSuchePort.Treffer(new WissenLesenRepository.IndexZeile(UUID.randomUUID(),
                Seitentyp.KONZEPT, "konzepte/test", titel, "Beschreibung", "AKTIV", false), version, titel);
    }

    private static class Transaktion extends WissensSucheTransaktion {
        boolean aktiv;
        @Override public <T> T ausfuehren(Supplier<T> arbeit) {
            assertThat(aktiv).isFalse();
            aktiv = true;
            try { return arbeit.get(); } finally { aktiv = false; }
        }
    }

    private class Budget extends WissenModellBudget {
        boolean erschoepft;
        Budget() { super(null, null); }
        @Override public void pruefen() {
            assertThat(transaktion.aktiv).isFalse();
            if (erschoepft) throw new FachFehler(Fehlercode.BUDGET);
        }
    }

    private class Port implements WissensSuchePort {
        List<Treffer> volltext = List.of(), vektor = List.of(), aktuell = List.of();
        boolean fehler;
        @Override public List<Treffer> volltext(String frage, Filter filter, int groesse) {
            assertThat(transaktion.aktiv).isTrue();
            assertThat(groesse).isEqualTo(50);
            if (fehler) throw new IllegalStateException("Datenbank");
            return volltext;
        }
        @Override public List<Treffer> vektor(float[] frage, Filter filter, int groesse) {
            assertThat(transaktion.aktiv).isTrue();
            assertThat(groesse).isEqualTo(50);
            return vektor;
        }
        @Override public List<Treffer> sichtbar(List<UUID> ids, Filter filter) {
            assertThat(transaktion.aktiv).isTrue();
            return aktuell.stream().filter(t -> ids.contains(t.seite().id())).toList();
        }
    }

    private class Embedding implements EmbeddingPort {
        Antwort antwort = new Vektoren(List.of(new float[]{1}), VERBRAUCH);
        int aufrufe;
        @Override public Antwort einbetten(List<String> texte) { throw new AssertionError("keine Seiten einbetten"); }
        @Override public Antwort einbettenFrage(String frage) {
            assertThat(transaktion.aktiv).isFalse();
            aufrufe++;
            return antwort;
        }
    }

    private class Reranker implements RerankerPort {
        List<String> texte = new ArrayList<>();
        int aufrufe;
        boolean ausfall;
        Runnable nachAufruf = () -> { };
        @Override public Antwort sortieren(String frage, List<String> kandidaten, int topN) {
            assertThat(transaktion.aktiv).isFalse();
            texte = List.copyOf(kandidaten);
            aufrufe++;
            nachAufruf.run();
            if (ausfall) return new Ausfall("Zeitgrenze", VERBRAUCH);
            return new Sortiert(IntStream.range(0, topN).map(i -> topN - 1 - i).boxed().toList(),
                    IntStream.range(0, topN).mapToObj(i -> 1.0).toList(), VERBRAUCH);
        }
    }
}
