package ch.urinet.archiv.suche.application;

import ch.urinet.archiv.gemeinsam.domain.FachFehler;
import ch.urinet.archiv.ki.application.EmbeddingPort;
import ch.urinet.archiv.ki.application.KiPort;
import ch.urinet.archiv.ki.application.KiVerbrauchPort;
import ch.urinet.archiv.ki.application.RerankerPort;
import ch.urinet.archiv.sicherheit.domain.Benutzer;
import ch.urinet.archiv.sicherheit.domain.Rolle;
import ch.urinet.archiv.sicherheit.domain.Sichtregel;
import ch.urinet.archiv.suche.domain.Abschnitttreffer;
import ch.urinet.archiv.suche.domain.AuszugTeil;
import ch.urinet.archiv.suche.domain.Fundstelle;
import ch.urinet.archiv.suche.domain.Hybridergebnis;
import ch.urinet.archiv.suche.domain.Hybridtreffer;
import ch.urinet.archiv.suche.domain.Sortierung;
import ch.urinet.archiv.suche.domain.Suchanfrage;
import ch.urinet.archiv.suche.domain.Suchergebnis;
import ch.urinet.archiv.suche.domain.Suchtreffer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link HybridSuche} mit Fake-Ports (Spec 02, Aufgabe 6.4; Req 2.2, 2.3, 2.8): Ablauf und Reihenfolge des Rerankers,
 * Belegnummer zuerst, Rückfall bei Modellausfällen, Datenbankfehler bleiben Fehler, kein Reranker ohne Kandidaten,
 * Zeilen {@code ki_verbrauch}, nie ein Dateiname an den Reranker. Die Fakes der Modelle prüfen bei jedem Aufruf, dass
 * keine Transaktion offen ist, die Fakes der Datenbank, dass eine offen ist.
 */
class HybridSucheTest {

    private static final Sichtregel REGEL = Sichtregel.fuer(new Benutzer("fabian", Rolle.INHABER));
    private static final float[] VEKTOR = {1f, 2f};
    private static final KiPort.Verbrauch EMBEDDING = new KiPort.Verbrauch("bge_multilingual_gemma2", 10, 0);

    private final FakeTransaktion transaktion = new FakeTransaktion();
    private final FakeVolltext volltext = new FakeVolltext();
    private final FakeVektor vektor = new FakeVektor();
    private final FakeEmbedding embedding = new FakeEmbedding();
    private final FakeReranker reranker = new FakeReranker();
    private final FakeVerbrauch verbrauch = new FakeVerbrauch();
    private HybridSuche suche;

    /** Dokumente der Fakes: Titel und Volltext je ID; der Dateiname steht nur im Treffer. */
    private final Map<UUID, String> titel = new LinkedHashMap<>();

    @BeforeEach
    void aufbauen() {
        suche = new HybridSuche(volltext, vektor, embedding, reranker, verbrauch, transaktion);
    }

    @Test
    void reihenfolgeDesRerankersMitBelegnummerZuerstUndFundstelle() {
        UUID beleg = dokument("Beleg");
        UUID nurVolltext = dokument("Nur Volltext");
        UUID beide = dokument("Beide");
        UUID nurVektor = dokument("Nur Vektor");
        volltext.treffer = List.of(treffer(beleg, "UVG-1"), treffer(nurVolltext, null), treffer(beide, null));
        vektor.abschnitte = List.of(abschnitt(nurVektor, 2, 3, 4), abschnitt(beide, 1, null, null));

        Hybridergebnis ergebnis = suche.suchen(anfrage("UVG-1"), REGEL, true, 30);

        assertThat(ergebnis.degradiert()).isFalse();
        // RRF ohne Belegnummer: beide (1/63 + 1/62), nurVektor (1/61), nurVolltext (1/62); der Reranker kehrt um
        assertThat(reranker.texte).containsExactly("Beide\n\nAbschnitt", "Nur Vektor\n\nAbschnitt",
                "Nur Volltext\n\nVolltext");
        assertThat(ids(ergebnis)).containsExactly(beleg, nurVolltext, nurVektor, beide);
        assertThat(ergebnis.treffer()).extracting(Hybridtreffer::fundstelle)
                .containsExactly(null, null, new Fundstelle(2, 3, 4), new Fundstelle(1, null, null));
        // reiner Vektortreffer mit nachgeladenen Details, Volltexttreffer mit ihrem Auszug
        assertThat(vektor.nachgeladen).containsExactly(nurVektor);
        assertThat(ergebnis.treffer().get(1).treffer().auszug()).containsExactly(new AuszugTeil("Volltext", true));
        assertThat(reranker.topN).isEqualTo(3);
        assertThat(volltext.anfrage.groesse()).isEqualTo(HybridSuche.VOLLTEXT);
        assertThat(volltext.anfrage.sortierung()).isEqualTo(Sortierung.RELEVANZ);
        assertThat(vektor.anzahl).isEqualTo(HybridSuche.VEKTOR);
    }

    @Test
    void hoechstensDreissigAnDenRerankerUndHoechstensKZurueck() {
        List<Suchtreffer> treffer = new ArrayList<>();
        List<Abschnitttreffer> abschnitte = new ArrayList<>();
        IntStream.range(0, 50).forEach(i -> treffer.add(treffer(dokument("V" + i), null)));
        IntStream.range(0, 50).forEach(i -> abschnitte.add(abschnitt(dokument("A" + i), 1, 1, 1)));
        volltext.treffer = treffer;
        vektor.abschnitte = abschnitte;

        Hybridergebnis ergebnis = suche.suchen(anfrage("Versicherung"), REGEL, false, 10);

        assertThat(reranker.texte).hasSize(30);
        assertThat(reranker.topN).isEqualTo(30);
        assertThat(ergebnis.treffer()).hasSize(10);
    }

    static Stream<Arguments> rueckfall() {
        KiPort.Verbrauch ohne = new KiPort.Verbrauch("m", 0, 0);
        RerankerPort.Antwort gut = null;
        return Stream.of(
                Arguments.of("Embedding Ausfall", new EmbeddingPort.Ausfall("HttpException", ohne), gut, 0),
                Arguments.of("Embedding Zeit", new EmbeddingPort.Ausfall("HttpTimeoutException", ohne), gut, 0),
                Arguments.of("Embedding ungültig mit Tokens",
                        new EmbeddingPort.Ausfall("falsche Dimension", EMBEDDING), gut, 1),
                Arguments.of("Embedding ohne Zugang", new EmbeddingPort.OhneZugang(), gut, 0),
                Arguments.of("Embedding Stub", new EmbeddingPort.OhneVektoren(), gut, 0),
                Arguments.of("Reranker Ausfall", null, new RerankerPort.Ausfall("HTTP-Status 500", ohne), 1),
                Arguments.of("Reranker Zeit", null, new RerankerPort.Ausfall("HttpTimeoutException", ohne), 1),
                Arguments.of("Reranker ungültig", null,
                        new RerankerPort.Ausfall("ungültige Indizes", new KiPort.Verbrauch("r", 7, 0)), 2),
                Arguments.of("Reranker ohne Zugang", null, new RerankerPort.OhneZugang(), 1),
                Arguments.of("Reranker Stub", null, new RerankerPort.OhneSortierung(), 1));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource
    void rueckfall(String fall, EmbeddingPort.Antwort embeddingAntwort, RerankerPort.Antwort rerankerAntwort,
                   int zeilen) {
        List<Suchtreffer> treffer = IntStream.range(0, 40).mapToObj(i -> treffer(dokument("V" + i), null)).toList();
        volltext.treffer = treffer;
        vektor.abschnitte = List.of(abschnitt(dokument("A"), 1, 1, 1));
        if (embeddingAntwort != null) {
            embedding.antwort = embeddingAntwort;
        }
        reranker.antwort = rerankerAntwort;
        Hybridergebnis ergebnis = suche.suchen(anfrage("Versicherung"), REGEL, true, 30);

        assertThat(ergebnis.degradiert()).as(fall).isTrue();
        assertThat(ergebnis.treffer()).as(fall).extracting(Hybridtreffer::treffer)
                .containsExactlyElementsOf(treffer.subList(0, 30));
        assertThat(ergebnis.treffer()).as(fall).allMatch(t -> t.fundstelle() == null);
        assertThat(verbrauch.zeilen).as(fall).hasSize(zeilen).allMatch(z -> z.zweck().equals("SUCHE"));
    }

    @Test
    void ohneKandidatenKeinAufrufDesRerankers() {
        Hybridergebnis ergebnis = suche.suchen(anfrage("nichts"), REGEL, true, 30);

        assertThat(ergebnis.degradiert()).isFalse();
        assertThat(ergebnis.treffer()).isEmpty();
        assertThat(reranker.aufrufe).isZero();
        assertThat(embedding.aufrufe).isEqualTo(1);
    }

    @Test
    void nurEineBelegnummerOhneAufrufDesRerankers() {
        UUID beleg = dokument("Beleg");
        volltext.treffer = List.of(treffer(beleg, "2026-001"));

        Hybridergebnis ergebnis = suche.suchen(anfrage("2026-001"), REGEL, true, 30);

        assertThat(ids(ergebnis)).containsExactly(beleg);
        assertThat(ergebnis.degradiert()).isFalse();
        assertThat(reranker.aufrufe).isZero();
    }

    @Test
    void jederModellaufrufMitAntwortIstEineZeileSuche() {
        volltext.treffer = List.of(treffer(dokument("V"), null));
        vektor.abschnitte = List.of(abschnitt(dokument("A"), 1, 1, 1));

        suche.suchen(anfrage("Versicherung"), REGEL, true, 30);

        assertThat(verbrauch.zeilen).containsExactly(new Zeile(KiVerbrauchPort.SUCHE, EMBEDDING),
                new Zeile(KiVerbrauchPort.SUCHE, new KiPort.Verbrauch("reranker", 2 * 5, 0)));
    }

    @Test
    void derRerankerErhaeltNieDenDateinamen() {
        UUID id = dokument("Police");
        volltext.treffer = List.of(new Suchtreffer(id, "VERSICHERUNG", "Police", null, Instant.EPOCH, "ARCHIVIERT",
                "NORMAL", List.of(), null, null, null, null, null, null, null,
                List.of(new AuszugTeil("Zebrafink_Police.pdf", true)), "Zebrafink_Police.pdf"));
        vektor.abschnitte = List.of(abschnitt(dokument("A"), 1, 1, 1));

        suche.suchen(anfrage("Zebrafink"), REGEL, true, 30);

        assertThat(reranker.texte).hasSize(2).noneMatch(t -> t.contains("Zebrafink"));
        assertThat(reranker.frage).isEqualTo("Zebrafink");
    }

    @Test
    void keinModellaufrufInEinerOffenenTransaktion() {
        volltext.treffer = List.of(treffer(dokument("V"), null));
        vektor.abschnitte = List.of(abschnitt(dokument("A"), 1, 1, 1));

        suche.suchen(anfrage("Versicherung"), REGEL, true, 30);

        assertThat(embedding.aufrufe).isEqualTo(1);
        assertThat(reranker.aufrufe).isEqualTo(1);
        assertThat(transaktion.anzahl).as("Volltext, Vektor, Texte, Details").isEqualTo(4);
        // Gegenprobe: in einer offenen Transaktion schlägt der Fake an
        assertThatThrownBy(() -> transaktion.ausfuehren(() -> embedding.einbettenFrage("x")))
                .isInstanceOf(AssertionError.class);
    }

    @Test
    void einFehlerImVolltextBleibtEinFehler() {
        volltext.fehler = true;
        assertThatThrownBy(() -> suche.suchen(anfrage("Versicherung"), REGEL, true, 30))
                .isInstanceOf(IllegalStateException.class);
        assertThat(embedding.aufrufe).isZero();
    }

    @Test
    void einFehlerImVektorteilBleibtEinFehler() {
        vektor.fehler = true;

        assertThatThrownBy(() -> suche.suchen(anfrage("Versicherung"), REGEL, true, 30))
                .isInstanceOf(IllegalStateException.class);
        assertThat(embedding.aufrufe).isEqualTo(1);
        assertThat(reranker.aufrufe).isZero();
    }

    @Test
    void einFehlerBeimLadenDerVolltexttexteBleibtEinFehler() {
        volltext.treffer = List.of(treffer(dokument("Volltext"), null));
        vektor.textFehler = true;

        assertThatThrownBy(() -> suche.suchen(anfrage("Versicherung"), REGEL, true, 30))
                .isInstanceOf(IllegalStateException.class);
        assertThat(reranker.aufrufe).isZero();
    }

    @Test
    void einFehlerBeimLadenDerVektordetailsBleibtEinFehler() {
        vektor.abschnitte = List.of(abschnitt(dokument("Vektor"), 1, 1, 1));
        vektor.detailsFehler = true;

        assertThatThrownBy(() -> suche.suchen(anfrage("Versicherung"), REGEL, true, 30))
                .isInstanceOf(IllegalStateException.class);
        assertThat(reranker.aufrufe).isEqualTo(1);
    }

    @Test
    void ohneSuchbegriffUngueltig() {
        assertThatThrownBy(() -> suche.suchen(anfrage(" "), REGEL, true, 30)).isInstanceOf(FachFehler.class);
        assertThatThrownBy(() -> suche.suchen(anfrage("x"), REGEL, true, 31))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private UUID dokument(String name) {
        UUID id = UUID.randomUUID();
        titel.put(id, name);
        return id;
    }

    private Suchtreffer treffer(UUID id, String belegnummer) {
        return new Suchtreffer(id, "VERSICHERUNG", titel.get(id), null, Instant.EPOCH, "ARCHIVIERT", "NORMAL",
                List.of(), null, null, null, belegnummer, null, null, null, List.of(new AuszugTeil("Volltext", true)),
                titel.get(id) + ".pdf");
    }

    private Abschnitttreffer abschnitt(UUID id, int nr, Integer von, Integer bis) {
        return new Abschnitttreffer(id, nr, von, bis, 0.1, titel.get(id) + "\n\nAbschnitt", "Abschnitt");
    }

    private static Suchanfrage anfrage(String begriff) {
        return new Suchanfrage(begriff, null, null, null, null, null, null, null, List.of(), null, null, null, null, 0,
                Suchanfrage.GROESSE_STANDARD);
    }

    private static List<UUID> ids(Hybridergebnis ergebnis) {
        return ergebnis.treffer().stream().map(t -> t.treffer().id()).toList();
    }

    record Zeile(String zweck, KiPort.Verbrauch verbrauch) {
    }

    static final class FakeTransaktion extends SucheTransaktion {
        boolean offen;
        int anzahl;

        @Override
        public <T> T ausfuehren(Supplier<T> arbeit) {
            assertThat(offen).as("keine verschachtelte Transaktion").isFalse();
            offen = true;
            anzahl++;
            try {
                return arbeit.get();
            } finally {
                offen = false;
            }
        }
    }

    final class FakeVolltext implements SuchePort {
        List<Suchtreffer> treffer = List.of();
        Suchanfrage anfrage;
        boolean fehler;

        @Override
        public Suchergebnis suchen(Suchanfrage a, Sichtregel regel, boolean mitDateiname) {
            assertThat(transaktion.offen).isTrue();
            if (fehler) {
                throw new IllegalStateException("Volltext");
            }
            anfrage = a;
            return new Suchergebnis(treffer, treffer.size());
        }
    }

    final class FakeVektor implements VektorSuchePort {
        List<Abschnitttreffer> abschnitte = List.of();
        List<UUID> nachgeladen = List.of();
        int anzahl;
        boolean fehler;
        boolean textFehler;
        boolean detailsFehler;

        @Override
        public List<Abschnitttreffer> naechste(float[] v, Suchanfrage a, Sichtregel regel, int n) {
            assertThat(transaktion.offen).isTrue();
            if (fehler) {
                throw new IllegalStateException("Datenbank");
            }
            assertThat(v).isEqualTo(VEKTOR);
            anzahl = n;
            return abschnitte;
        }

        @Override
        public Map<UUID, String> texte(Collection<UUID> ids, Suchanfrage a, Sichtregel regel, int zeichen) {
            assertThat(transaktion.offen).isTrue();
            if (textFehler) {
                throw new IllegalStateException("Texte");
            }
            assertThat(zeichen).isEqualTo(3000);
            Map<UUID, String> texte = new LinkedHashMap<>();
            ids.forEach(id -> texte.put(id, titel.get(id) + "\n\nVolltext"));
            return texte;
        }

        @Override
        public List<Suchtreffer> details(List<Abschnitttreffer> liste, Suchanfrage a, Sichtregel regel,
                                         boolean mitDateiname) {
            assertThat(transaktion.offen).isTrue();
            if (detailsFehler) {
                throw new IllegalStateException("Details");
            }
            nachgeladen = liste.stream().map(Abschnitttreffer::dokumentId).toList();
            return liste.stream().map(x -> new Suchtreffer(x.dokumentId(), null, titel.get(x.dokumentId()), null,
                    Instant.EPOCH, "ARCHIVIERT", "NORMAL", List.of(), null, null, null, null, null, null, null,
                    List.of(), null)).toList();
        }
    }

    final class FakeEmbedding implements EmbeddingPort {
        EmbeddingPort.Antwort antwort = new Vektoren(List.of(VEKTOR), EMBEDDING);
        int aufrufe;

        @Override
        public Antwort einbetten(List<String> texte) {
            throw new AssertionError("EMBEDDING gehört nicht zur Suche");
        }

        @Override
        public Antwort einbettenFrage(String frage) {
            if (transaktion.offen) {
                throw new AssertionError("Embedding in einer offenen Transaktion");
            }
            aufrufe++;
            return antwort;
        }
    }

    final class FakeReranker implements RerankerPort {
        RerankerPort.Antwort antwort;
        List<String> texte = List.of();
        String frage;
        int topN;
        int aufrufe;

        @Override
        public Antwort sortieren(String f, List<String> t, int n) {
            if (transaktion.offen) {
                throw new AssertionError("Reranker in einer offenen Transaktion");
            }
            aufrufe++;
            frage = f;
            texte = List.copyOf(t);
            topN = n;
            if (antwort != null) {
                return antwort;
            }
            List<Integer> indizes = IntStream.range(0, n).map(i -> t.size() - 1 - i).boxed().toList();
            return new Sortiert(indizes, indizes.stream().map(i -> 1.0).toList(),
                    new KiPort.Verbrauch("reranker", 5L * t.size(), 0));
        }
    }

    static final class FakeVerbrauch implements KiVerbrauchPort {
        final List<Zeile> zeilen = new ArrayList<>();

        @Override
        public void erfassen(String zweck, KiPort.Verbrauch eintrag) {
            zeilen.add(new Zeile(zweck, eintrag));
        }
    }
}
