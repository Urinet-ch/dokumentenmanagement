package ch.urinet.archiv.wissen;

import ch.urinet.archiv.wissen.domain.Quelleneintrag;
import ch.urinet.archiv.wissen.domain.Seitentyp;
import ch.urinet.archiv.wissen.domain.WissensGraph;
import ch.urinet.archiv.wissen.domain.WissensLint;
import ch.urinet.archiv.wissen.domain.WissensPruefung;
import ch.urinet.archiv.wissen.domain.WissensStufe;
import ch.urinet.archiv.wissen.domain.Wissensseite;
import io.cucumber.java.de.Angenommen;
import io.cucumber.java.de.Dann;
import io.cucumber.java.de.Wenn;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

public class WissenLintRegelnSchritte {
    private static final UUID A = UUID.fromString("00000000-0000-0000-0000-000000000101");
    private static final UUID B = UUID.fromString("00000000-0000-0000-0000-000000000102");
    private static final UUID C = UUID.fromString("00000000-0000-0000-0000-000000000103");
    private static final UUID ALT = UUID.fromString("00000000-0000-0000-0000-000000000201");
    private static final UUID NEU = UUID.fromString("00000000-0000-0000-0000-000000000202");

    private List<Wissensseite> seiten;
    private WissensGraph graph;
    private Map<UUID, WissensPruefung.DokumentQuelle> dokumente;
    private Map<WissensLint.PaarId, Instant> historie;
    private WissensLint.Ergebnis ergebnis;

    @Angenommen("drei verwandte Lint-Seiten sind verschieden lange oder noch nie geprüft")
    public void paare() {
        seiten = List.of(seite(A, "konzepte/a", Seitentyp.KONZEPT, ALT, null, "Aussage. [^q]"),
                seite(B, "konzepte/b", Seitentyp.KONZEPT, ALT, null, "Aussage. [^q]"),
                seite(C, "konzepte/c", Seitentyp.KONZEPT, ALT, null, "Aussage. [^q]"));
        graph = new WissensGraph(Map.of(A, Set.of(ALT), B, Set.of(ALT), C, Set.of(ALT)), Map.of(),
                Map.of(ALT, WissensStufe.NORMAL),
                Map.of(A, WissensStufe.NORMAL, B, WissensStufe.NORMAL, C, WissensStufe.NORMAL));
        dokumente = Map.of(ALT, quelle(2025));
        historie = Map.of(new WissensLint.PaarId(A, B), Instant.parse("2026-01-02T00:00:00Z"),
                new WissensLint.PaarId(A, C), Instant.parse("2026-01-01T00:00:00Z"));
    }

    @Wenn("der Lint höchstens zwei Seitenpaare auswählt")
    public void zweiAuswaehlen() {
        ergebnis = WissensLint.pruefen(seiten.stream().map(WissensLint.Seite::new).toList(), graph, dokumente,
                historie, LocalDate.of(2026, 11, 20), 20_000, 2);
    }

    @Dann("kommen das ungeprüfte und danach das am längsten nicht geprüfte Paar")
    public void reihenfolge() {
        assertThat(ergebnis.paare()).extracting(WissensLint.Paar::id)
                .containsExactly(new WissensLint.PaarId(B, C), new WissensLint.PaarId(A, C));
    }

    @Angenommen("eine neuere Wissensquelle verweist auf eine Seite mit älterem Quelldokument")
    public void neuereQuelle() {
        seiten = List.of(seite(A, "konzepte/a", Seitentyp.KONZEPT, ALT, null, "Aussage. [^q]"),
                seite(C, "quellen/2026-01-neu", Seitentyp.QUELLE, NEU, NEU,
                        "## Zusammenfassung\n\nAussage. [^q]\n\n## Kernaussagen\n\nAussage. [^q]\n\n"
                                + "## Berührte Seiten\n\n- [A](/konzepte/a)"));
        graph = new WissensGraph(Map.of(A, Set.of(ALT), C, Set.of(NEU)), Map.of(),
                Map.of(ALT, WissensStufe.NORMAL, NEU, WissensStufe.NORMAL),
                Map.of(A, WissensStufe.NORMAL, C, WissensStufe.NORMAL));
        dokumente = Map.of(ALT, quelle(2025), NEU, quelle(2026));
        historie = Map.of();
    }

    @Dann("erkennt der Lint die ältere Aussage als Veraltet-Kandidaten für die neuere Quelle")
    public void kandidat() {
        assertThat(ergebnis.paare()).singleElement().satisfies(paar ->
                assertThat(paar.veraltet()).containsExactly(new WissensLint.VeraltetKandidat(A, "q", NEU)));
    }

    private static Wissensseite seite(UUID id, String pfad, Seitentyp typ, UUID dokument, UUID wissensquelle,
                                      String inhalt) {
        return new Wissensseite(id, pfad, typ, "Probe", "Probe", List.of("eins", "zwei"),
                Wissensseite.Status.AKTIV, null, List.of(new Quelleneintrag("q", dokument, 1, 1, null, null)),
                Wissensseite.Vertraulichkeit.NORMAL, inhalt, 1, "probe", Instant.EPOCH, "probe", Instant.EPOCH,
                false, false, wissensquelle);
    }

    private static WissensPruefung.DokumentQuelle quelle(int jahr) {
        return new WissensPruefung.DokumentQuelle(true, true, false, 1, LocalDate.of(jahr, 1, 1), Optional.empty());
    }
}
