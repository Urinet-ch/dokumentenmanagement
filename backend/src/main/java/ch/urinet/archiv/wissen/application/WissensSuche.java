package ch.urinet.archiv.wissen.application;

import ch.urinet.archiv.sicherheit.application.Berechtigung;
import ch.urinet.archiv.sicherheit.domain.Aktion;
import ch.urinet.archiv.sicherheit.domain.Benutzer;
import ch.urinet.archiv.sicherheit.domain.Sichtregel;
import ch.urinet.archiv.suche.application.HybridSuche;
import ch.urinet.archiv.ki.application.KiVerbrauchPort;
import ch.urinet.archiv.suche.domain.Rrf;
import ch.urinet.archiv.wissen.domain.Seitentyp;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

@ApplicationScoped
@Transactional(Transactional.TxType.NEVER)
public class WissensSuche {
    public record Ergebnis(List<WissenLesenRepository.IndexZeile> treffer, boolean degradiert) { }
    private final WissensSuchePort port;
    private final HybridSuche modelle;
    private final WissensSucheTransaktion transaktion;
    private final Berechtigung rechte;
    private final WissenModellBudget budget;

    public WissensSuche(WissensSuchePort port, HybridSuche modelle, WissensSucheTransaktion transaktion,
                       Berechtigung rechte, WissenModellBudget budget) {
        this.port = port;
        this.modelle = modelle;
        this.transaktion = transaktion;
        this.rechte = rechte;
        this.budget = budget;
    }

    public Ergebnis suchen(Benutzer benutzer, String frage, Seitentyp typ, String tag, UUID dokumentId, int k,
                           boolean hybrid) {
        rechte.wissensseitenAktion(benutzer, Aktion.A15, true);
        var filter = new WissensSuchePort.Filter(Sichtregel.fuer(benutzer), typ, tag, dokumentId);
        return rangieren(frage, () -> filter, k, hybrid);
    }

    Ergebnis rangieren(String frage, Supplier<WissensSuchePort.Filter> kontext, int k, boolean hybrid) {
        if (frage == null || frage.isBlank() || k < 1 || k > HybridSuche.RERANKER) {
            throw new IllegalArgumentException("Wissenssuche verlangt einen Suchbegriff und 1 bis 30 Treffer");
        }
        var volltext = transaktion.ausfuehren(() -> port.volltext(frage, kontext.get(), HybridSuche.VOLLTEXT));
        if (!hybrid) return volltextErgebnis(volltext, kontext, k, false);
        budget.embeddingPruefen();
        var vektor = modelle.frageEinbetten(frage, KiVerbrauchPort.WISSEN_EMBEDDING);
        if (vektor.isEmpty()) return rueckfall(volltext, kontext, k);
        var abschnitte = transaktion.ausfuehren(() -> port.vektor(vektor.get(), kontext.get(), HybridSuche.VEKTOR));
        var ids = Rrf.ordnen(ids(volltext), ids(abschnitte)).stream().limit(HybridSuche.RERANKER).toList();
        var sichtbar = transaktion.ausfuehren(() -> port.sichtbar(ids, kontext.get())).stream()
                .collect(Collectors.toMap(t -> t.seite().id(), Function.identity(), (a, b) -> a, LinkedHashMap::new));
        var kandidaten = ids.stream().filter(sichtbar::containsKey).map(sichtbar::get).toList();
        if (kandidaten.isEmpty()) return new Ergebnis(List.of(), false);
        var ausVektor = abschnitte.stream().collect(Collectors.toMap(t -> t.seite().id(), Function.identity(),
                (a, b) -> a));
        var texte = kandidaten.stream().map(t -> {
            var abschnitt = ausVektor.get(t.seite().id());
            return abschnitt != null && abschnitt.version() == t.version() ? abschnitt.text() : t.text();
        }).toList();
        budget.rerankerPruefen();
        var sortiert = modelle.texteSortieren(frage, texte, KiVerbrauchPort.WISSEN_EMBEDDING);
        if (sortiert.isEmpty()) return rueckfall(volltext, kontext, k);
        var beste = sortiert.get().indizes().stream().limit(k).map(i -> kandidaten.get(i).seite().id()).toList();
        var erneut = transaktion.ausfuehren(() -> port.sichtbar(beste, kontext.get())).stream()
                .collect(Collectors.toMap(t -> t.seite().id(), WissensSuchePort.Treffer::seite));
        return new Ergebnis(beste.stream().filter(erneut::containsKey).map(erneut::get).toList(), false);
    }

    private Ergebnis rueckfall(List<WissensSuchePort.Treffer> volltext, Supplier<WissensSuchePort.Filter> kontext, int k) {
        return volltextErgebnis(volltext, kontext, k, true);
    }

    private Ergebnis volltextErgebnis(List<WissensSuchePort.Treffer> volltext, Supplier<WissensSuchePort.Filter> kontext, int k,
                                     boolean degradiert) {
        var erlaubt = transaktion.ausfuehren(() -> port.sichtbar(ids(volltext), kontext.get())).stream()
                .collect(Collectors.toMap(t -> t.seite().id(), WissensSuchePort.Treffer::seite));
        return new Ergebnis(volltext.stream().map(t -> t.seite().id()).filter(erlaubt::containsKey)
                .limit(k).map(erlaubt::get).toList(), degradiert);
    }

    private static List<UUID> ids(List<WissensSuchePort.Treffer> treffer) {
        return treffer.stream().map(t -> t.seite().id()).distinct().toList();
    }
}
