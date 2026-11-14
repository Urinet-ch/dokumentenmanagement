package ch.urinet.archiv.suche.application;

import ch.urinet.archiv.gemeinsam.domain.FachFehler;
import ch.urinet.archiv.gemeinsam.domain.Fehlercode;
import ch.urinet.archiv.ki.application.EmbeddingPort;
import ch.urinet.archiv.ki.application.KiPort;
import ch.urinet.archiv.ki.application.KiVerbrauchPort;
import ch.urinet.archiv.ki.application.RerankerPort;
import ch.urinet.archiv.sicherheit.domain.Benutzer;
import ch.urinet.archiv.sicherheit.domain.Sichtregel;
import ch.urinet.archiv.suche.domain.Abschnitttreffer;
import ch.urinet.archiv.suche.domain.Hybridergebnis;
import ch.urinet.archiv.suche.domain.Hybridtreffer;
import ch.urinet.archiv.suche.domain.Rrf;
import ch.urinet.archiv.suche.domain.Sortierung;
import ch.urinet.archiv.suche.domain.Suchanfrage;
import ch.urinet.archiv.suche.domain.Suchtreffer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Hybridsuche (Spec 02, Req 2.2, 2.3, 2.5, 2.7, 2.8; design.md «Embeddings und Hybridsuche», Schritte 1–5): die besten
 * {@value #VOLLTEXT} Volltexttreffer über {@link SuchePort} (Rang wie in der Volltextsuche, exakte Belegnummer zuerst),
 * die {@value #VEKTOR} nächsten Abschnitte zur eingebetteten Frage ({@link VektorSuchePort}), Zusammenführung mit
 * {@link Rrf}, die besten {@value #RERANKER} an den Reranker, zurück die besten {@code k} in seiner Reihenfolge. Exakte
 * Belegnummern bleiben zuerst stehen und gehen nicht an den Reranker.
 * <p>
 * Rückfall (Req 2.3): Liefern Embedding oder Reranker nichts Brauchbares (Ausfall, Zeitüberschreitung, kein Token,
 * Dev-Stub) oder scheitert ein Datenbankteil nach dem Volltext, sind es die besten {@code k} Volltexttreffer ohne
 * Fundstelle mit {@code degradiert}; ein Fehler im Volltext bleibt ein Fehler. Jeder Rückfall ist ein Log-Eintrag mit
 * Teil und Klassennamen, nie mit Suchbegriff oder Texten (Req 16.7).
 * <p>
 * Die Modelle laufen ausserhalb jeder Transaktion ({@code NEVER}), die Datenbankteile in kurzen eigenen
 * ({@link SucheTransaktion}). Jeder Aufruf eines Modells mit Antwort ist eine Zeile {@code ki_verbrauch} mit Zweck
 * {@link KiVerbrauchPort#SUCHE}, ohne Limit (Req 2.8, entschieden von Fabian am 14.11.2026). Die Suche wird nicht
 * protokolliert. Wiederverwendbar für Chat (Welle 7) und {@code /public/v1} (Spec 03, Aufgabe 11.13) mit
 * {@code mitDateiname = false}.
 */
@ApplicationScoped
@Transactional(Transactional.TxType.NEVER)
public class HybridSuche {

    public static final int VOLLTEXT = 50;
    public static final int VEKTOR = 50;
    public static final int RERANKER = 30;
    /** Treffer in der PWA (Req 2.7). */
    public static final int PWA = 30;
    /** Zeichen des Volltexts im Text für den Reranker bei Treffern nur aus dem Volltext. */
    public static final int TEXT_ZEICHEN = 3000;

    private static final Logger LOG = Logger.getLogger(HybridSuche.class);

    private final SuchePort volltext;
    private final VektorSuchePort vektor;
    private final EmbeddingPort embedding;
    private final RerankerPort reranker;
    private final KiVerbrauchPort verbrauch;
    private final SucheTransaktion transaktion;

    public HybridSuche(SuchePort volltext, VektorSuchePort vektor, EmbeddingPort embedding, RerankerPort reranker,
                       KiVerbrauchPort verbrauch, SucheTransaktion transaktion) {
        this.volltext = volltext;
        this.vektor = vektor;
        this.embedding = embedding;
        this.reranker = reranker;
        this.verbrauch = verbrauch;
        this.transaktion = transaktion;
    }

    /** Suche der PWA: mit Dateinamen in Auszug und Treffer (Req 14.8, 14.9), höchstens {@value #PWA} Treffer. */
    public Hybridergebnis suchen(Benutzer benutzer, Suchanfrage anfrage) {
        return suchen(anfrage, Sichtregel.fuer(benutzer), true, PWA);
    }

    /**
     * @param anfrage      Suchbegriff (Pflicht) und Filter; Sortierung, Seite und Grösse gelten nicht
     * @param mitDateiname wie {@link SuchePort}; der Reranker erhält den Dateinamen nie (Spec 01, Req 3.13)
     * @param k            1 bis {@value #RERANKER}
     */
    public Hybridergebnis suchen(Suchanfrage anfrage, Sichtregel regel, boolean mitDateiname, int k) {
        if (anfrage.begriff() == null) {
            throw new FachFehler(Fehlercode.ANFRAGE_UNGUELTIG);
        }
        if (k < 1 || k > RERANKER) {
            throw new IllegalArgumentException("k von 1 bis " + RERANKER);
        }
        Suchanfrage besteVolltext = new Suchanfrage(anfrage.begriff(), anfrage.art(), anfrage.partner(),
                anfrage.datumVon(), anfrage.datumBis(), anfrage.betragVon(), anfrage.betragBis(), anfrage.richtung(),
                anfrage.tags(), anfrage.status(), anfrage.geschaeftsjahr(), anfrage.belegnummer(), Sortierung.RELEVANZ,
                0, VOLLTEXT);
        List<Suchtreffer> treffer = transaktion.ausfuehren(
                () -> volltext.suchen(besteVolltext, regel, mitDateiname)).treffer();
        return hybrid(anfrage, regel, mitDateiname, k, treffer)
                .map(t -> new Hybridergebnis(t, false))
                .orElseGet(() -> new Hybridergebnis(treffer.stream().limit(k).map(t -> new Hybridtreffer(t, null))
                        .toList(), true));
    }

    /** Schritte 2–4; leer heisst Rückfall auf den Volltext (schon geloggt). */
    private Optional<List<Hybridtreffer>> hybrid(Suchanfrage anfrage, Sichtregel regel, boolean mitDateiname, int k,
                                                 List<Suchtreffer> volltextTreffer) {
        Optional<float[]> frage = frage(anfrage.begriff());
        if (frage.isEmpty()) {
            return Optional.empty();
        }
        List<Abschnitttreffer> abschnitte;
        try {
            abschnitte = transaktion.ausfuehren(() -> vektor.naechste(frage.get(), anfrage, regel, VEKTOR));
        } catch (RuntimeException e) {
            return rueckfall("Vektor-Abfrage", grund(e));
        }
        Map<UUID, Suchtreffer> ausVolltext = volltextTreffer.stream()
                .collect(Collectors.toMap(Suchtreffer::id, Function.identity(), (a, b) -> a, LinkedHashMap::new));
        Map<UUID, Abschnitttreffer> ausVektor = abschnitte.stream()
                .collect(Collectors.toMap(Abschnitttreffer::dokumentId, Function.identity(), (a, b) -> a,
                        LinkedHashMap::new));
        // exakte Belegnummer (Req 14.7) zuerst wie im Volltext, ohne Reranker
        Set<UUID> belegnummern = volltextTreffer.stream()
                .filter(t -> anfrage.begriff().equals(t.belegnummer())).map(Suchtreffer::id)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        List<UUID> kandidaten = Rrf.auswahl(Rrf.ordnen(List.copyOf(ausVolltext.keySet()),
                List.copyOf(ausVektor.keySet())), belegnummern, RERANKER);

        List<UUID> nurVolltext = kandidaten.stream().filter(id -> !ausVektor.containsKey(id)).toList();
        Map<UUID, String> volltextTexte;
        try {
            volltextTexte = nurVolltext.isEmpty() ? Map.of()
                    : transaktion.ausfuehren(() -> vektor.texte(nurVolltext, anfrage, regel, TEXT_ZEICHEN));
        } catch (RuntimeException e) {
            return rueckfall("Texte für den Reranker", grund(e));
        }
        List<UUID> gesendet = new ArrayList<>();
        List<String> texte = new ArrayList<>();
        for (UUID id : kandidaten) {
            String text = ausVektor.containsKey(id) ? ausVektor.get(id).text() : volltextTexte.get(id);
            if (text != null) {
                gesendet.add(id);
                texte.add(text);
            }
        }
        List<UUID> reihenfolge = new ArrayList<>(belegnummern);
        if (!gesendet.isEmpty()) {
            Optional<List<Integer>> sortiert = sortieren(anfrage.begriff(), texte);
            if (sortiert.isEmpty()) {
                return Optional.empty();
            }
            sortiert.get().forEach(i -> reihenfolge.add(gesendet.get(i)));
        }
        List<UUID> beste = reihenfolge.stream().limit(k).toList();

        List<Abschnitttreffer> nachladen = beste.stream().filter(id -> !ausVolltext.containsKey(id))
                .map(ausVektor::get).toList();
        Map<UUID, Suchtreffer> details = new HashMap<>();
        if (!nachladen.isEmpty()) {
            try {
                transaktion.ausfuehren(() -> vektor.details(nachladen, anfrage, regel, mitDateiname))
                        .forEach(t -> details.put(t.id(), t));
            } catch (RuntimeException e) {
                return rueckfall("Details der Vektortreffer", grund(e));
            }
        }
        List<Hybridtreffer> ergebnis = new ArrayList<>();
        for (UUID id : beste) {
            Suchtreffer t = ausVolltext.containsKey(id) ? ausVolltext.get(id) : details.get(id);
            if (t != null) {
                Abschnitttreffer abschnitt = ausVektor.get(id);
                ergebnis.add(new Hybridtreffer(t, abschnitt == null ? null : abschnitt.fundstelle()));
            }
        }
        return Optional.of(List.copyOf(ergebnis));
    }

    /** Schritt 2a: die Frage einbetten; leer heisst Rückfall. */
    private Optional<float[]> frage(String begriff) {
        return switch (embedding.einbettenFrage(begriff)) {
            case EmbeddingPort.Vektoren v -> {
                erfassen(v.verbrauch());
                yield v.vektoren().size() == 1 ? Optional.of(v.vektoren().getFirst())
                        : rueckfall("Embedding", "falsche Anzahl Vektoren");
            }
            case EmbeddingPort.Ausfall a -> {
                erfassenMitTokens(a.verbrauch());
                yield rueckfall("Embedding", a.grund());
            }
            case EmbeddingPort.OhneVektoren o -> ohne("Embedding", "Dev-Stub");
            case EmbeddingPort.OhneZugang o -> ohne("Embedding", "kein Token");
        };
    }

    /** Schritt 4: Indizes in {@code texte} in der Reihenfolge des Rerankers; leer heisst Rückfall. */
    private Optional<List<Integer>> sortieren(String begriff, List<String> texte) {
        return switch (reranker.sortieren(begriff, texte, texte.size())) {
            case RerankerPort.Sortiert s -> {
                erfassen(s.verbrauch());
                yield Optional.of(s.indizes());
            }
            case RerankerPort.Ausfall a -> {
                erfassenMitTokens(a.verbrauch());
                yield rueckfall("Reranker", a.grund());
            }
            case RerankerPort.OhneSortierung o -> ohne("Reranker", "Dev-Stub");
            case RerankerPort.OhneZugang o -> ohne("Reranker", "kein Token");
        };
    }

    private static <T> Optional<T> rueckfall(String teil, String grund) {
        LOG.warnf("Hybridsuche nur im Volltext (degradiert): %s ohne Ergebnis (%s)", teil, grund);
        return Optional.empty();
    }

    /** Rückfall ohne Störung (Dev-Stub, kein Token): erwartet, darum nur INFO. */
    private static <T> Optional<T> ohne(String teil, String grund) {
        LOG.infof("Hybridsuche nur im Volltext (degradiert): %s ohne Ergebnis (%s)", teil, grund);
        return Optional.empty();
    }

    /** Nur Klassennamen, nie die Meldung (sie kann Teile der Abfrage enthalten, Req 16.7). */
    private static String grund(Throwable t) {
        Throwable wurzel = t;
        while (wurzel.getCause() != null && wurzel.getCause() != wurzel) {
            wurzel = wurzel.getCause();
        }
        return wurzel == t ? t.getClass().getSimpleName()
                : t.getClass().getSimpleName() + " (" + wurzel.getClass().getSimpleName() + ")";
    }

    /** Ungültige Antwort mit Tokens: eine Zeile; ohne Antwort (Zeitüberschreitung, Verbindung) keine. */
    private void erfassenMitTokens(KiPort.Verbrauch eintrag) {
        if (eintrag != null && (eintrag.tokensEin() > 0 || eintrag.tokensAus() > 0)) {
            erfassen(eintrag);
        }
    }

    private void erfassen(KiPort.Verbrauch eintrag) {
        try {
            verbrauch.erfassen(KiVerbrauchPort.SUCHE, eintrag);
        } catch (RuntimeException e) {
            LOG.warnf("KI-Verbrauch der Suche nicht gespeichert (%s)", e.getClass().getName());
        }
    }
}
