package ch.urinet.archiv.integration.application.webseite;

import ch.urinet.archiv.integration.domain.webseite.ListenFilter;
import ch.urinet.archiv.integration.domain.webseite.Lesezugriff;
import ch.urinet.archiv.integration.domain.webseite.WebseiteDokument;
import ch.urinet.archiv.integration.domain.webseite.WebseiteTreffer;
import ch.urinet.archiv.sicherheit.domain.Sichtregel;
import ch.urinet.archiv.suche.application.HybridSuche;
import ch.urinet.archiv.suche.domain.Hybridergebnis;
import ch.urinet.archiv.suche.domain.Hybridtreffer;
import ch.urinet.archiv.suche.domain.Sortierung;
import ch.urinet.archiv.suche.domain.Suchanfrage;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Hybridsuche der Webseiten-Schnittstelle (Spec 03, Aufgabe 11.2, Req 5.1, 5.7, 5.11): die {@link HybridSuche} ohne
 * Dateinamen ({@code mitDateiname = false}, Spec 01, Req 3.13; trägt der Index von Changeset 028) mit der Sicht
 * {@link Sichtregel#fuerWebseite}, die den festen Filter samt den Stufen der Anfrage in jede Teilabfrage (Volltext,
 * Vektor, Texte, Details) trägt. Die Felder der Treffer lädt danach {@link WebseitenLesePort} unter demselben Filter
 * noch einmal; der Auszug stammt aus dem Text des Dokuments, nie aus Partnername oder Tags. Höchstens
 * {@value HybridSuche#PWA} Treffer ohne Blättern.
 */
@ApplicationScoped
@Transactional(Transactional.TxType.NEVER)
public class WebseiteSuche {

    public record Ergebnis(List<WebseiteTreffer> treffer, boolean degradiert) {

        public Ergebnis {
            treffer = List.copyOf(treffer);
        }
    }

    private final HybridSuche suche;
    private final WebseitenLesePort port;

    public WebseiteSuche(HybridSuche suche, WebseitenLesePort port) {
        this.suche = suche;
        this.port = port;
    }

    /** {@code begriff} ist Pflicht (leer oder zu lang: 400 {@code ANFRAGE_UNGUELTIG}). */
    public Ergebnis suchen(Lesezugriff zugriff, String begriff, ListenFilter filter) {
        Suchanfrage anfrage = new Suchanfrage(begriff, filter.reihe(), null, null, null, null, null, null, List.of(),
                null, null, null, Sortierung.RELEVANZ, 0, HybridSuche.PWA);
        Sichtregel regel = Sichtregel.fuerWebseite(
                new Sichtregel.Webseite(zugriff.stufen(), filter.jahr(), filter.ausgabe()));
        Hybridergebnis ergebnis = suche.suchen(anfrage, regel, false, HybridSuche.PWA);
        List<UUID> ids = ergebnis.treffer().stream().map(t -> t.treffer().id()).toList();
        Map<UUID, Integer> seiten = new HashMap<>();
        for (Hybridtreffer t : ergebnis.treffer()) {
            if (t.fundstelle() != null && t.fundstelle().seiteVon() != null) {
                seiten.put(t.treffer().id(), t.fundstelle().seiteVon());
            }
        }
        List<WebseiteTreffer> treffer = new ArrayList<>();
        for (WebseiteDokument d : port.treffer(zugriff, ids, anfrage.begriff())) {
            treffer.add(new WebseiteTreffer(d, seiten.get(d.id()), treffer.size() + 1));
        }
        return new Ergebnis(treffer, ergebnis.degradiert());
    }
}
