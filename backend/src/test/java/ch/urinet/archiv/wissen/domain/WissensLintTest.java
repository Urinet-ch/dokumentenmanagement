package ch.urinet.archiv.wissen.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class WissensLintTest {
    private static final UUID A = UUID.fromString("00000000-0000-0000-0000-000000000101");
    private static final UUID B = UUID.fromString("00000000-0000-0000-0000-000000000102");
    private static final UUID Q = UUID.fromString("00000000-0000-0000-0000-000000000103");
    private static final UUID ALT = UUID.fromString("00000000-0000-0000-0000-000000000201");
    private static final UUID NEU = UUID.fromString("00000000-0000-0000-0000-000000000202");

    @Test
    void negativePaargrenzeUndNichtAufsteigendePaarIdsWerdenAbgelehnt() {
        assertThatIllegalArgumentException().isThrownBy(() -> pruefen(List.of(), Map.of(), -1));
        assertThatIllegalArgumentException().isThrownBy(() -> new WissensLint.PaarId(A, A));
        assertThatIllegalArgumentException().isThrownBy(() -> new WissensLint.PaarId(B, A));
    }

    @Test
    void eingehendeDokumentquellenFehlendeLinksUndQuerverweise() {
        Wissensseite a = seite(A, "konzepte/a", Seitentyp.KONZEPT, List.of("eins", "zwei"),
                "Text.[^q] [Fehlt](/konzepte/fehlend)", List.of(dokument(ALT)), null, false, null);
        Wissensseite b = seite(B, "konzepte/b", Seitentyp.KONZEPT, List.of("eins", "zwei"),
                "Text.[^q]", List.of(dokument(ALT)), LocalDate.of(2026, 11, 20), true, null);
        Wissensseite q = seite(Q, "quellen/2026-01-q", Seitentyp.QUELLE, List.of(),
                "## Zusammenfassung\n\nText.[^q]\n\n## Kernaussagen\n\nText.[^q]\n\n## Berührte Seiten",
                List.of(dokument(ALT)), null, false, ALT);
        var result = pruefen(List.of(a, b, q), Map.of(ALT, quelle(2026, false)), 30);

        assertThat(result.befunde()).contains(
                new WissensLint.Befund(A, "LINK_FEHLT", null),
                new WissensLint.Befund(A, "VERWAIST", null),
                new WissensLint.Befund(B, "VERALTET_AB", null),
                new WissensLint.Befund(B, "QUELLE_PRUEFEN", null),
                new WissensLint.Befund(A, "QUERVERWEIS_FEHLT", B),
                new WissensLint.Befund(Q, "QUELLE_OHNE_BERUEHRTE_SEITEN", null));
        assertThat(result.befunde()).doesNotContain(new WissensLint.Befund(Q, "VERWAIST", null));
    }

    @Test
    void neuereQuelleUndPersonalHuelleWerdenVorAuswahlBeruecksichtigt() {
        Wissensseite a = seite(A, "konzepte/a", Seitentyp.KONZEPT, List.of(),
                "Text.[^q]", List.of(dokument(ALT)), null, false, null);
        Wissensseite q = seite(Q, "quellen/2026-01-q", Seitentyp.QUELLE, List.of(),
                "## Zusammenfassung\n\nText.[^q]\n\n## Kernaussagen\n\nText.[^q]\n\n"
                        + "## Berührte Seiten\n\n- [A](/konzepte/a)",
                List.of(dokument(NEU)), null, false, NEU);
        var normal = pruefen(List.of(a, q), Map.of(ALT, quelle(2025, false), NEU, quelle(2026, false)), 30);
        assertThat(normal.paare()).singleElement().satisfies(paar ->
                assertThat(paar.veraltet()).containsExactly(new WissensLint.VeraltetKandidat(A, "q", NEU)));
        var personal = pruefen(List.of(a, q), Map.of(ALT, quelle(2025, true), NEU, quelle(2026, false)), 30);
        assertThat(personal.paare()).isEmpty();
        assertThat(personal.befunde()).contains(new WissensLint.Befund(A, "QUELLE_PERSONAL", null));
    }

    @Test
    void quelldokumentDerQuellseiteZaehltAuchOhneEigenenQuelleneintrag() {
        Wissensseite a = seite(A, "konzepte/a", Seitentyp.KONZEPT, List.of(),
                "Text.[^q]", List.of(dokument(ALT)), null, false, null);
        Wissensseite q = seite(Q, "quellen/2026-01-q", Seitentyp.QUELLE, List.of(),
                "[A](/konzepte/a)", List.of(), null, false, NEU);
        var normal = pruefen(List.of(a, q), Map.of(ALT, quelle(2025, false), NEU, quelle(2026, false)), 30);
        assertThat(normal.paare()).singleElement().satisfies(paar ->
                assertThat(paar.veraltet()).containsExactly(new WissensLint.VeraltetKandidat(A, "q", NEU)));
        var personal = pruefen(List.of(a, q), Map.of(ALT, quelle(2025, false), NEU, quelle(2026, true)), 30);
        assertThat(personal.paare()).isEmpty();
    }

    @Test
    void fehlenderQuerverweisGiltInBeidenRichtungenUndPaareSindBegrenzt() {
        Wissensseite a = seite(A, "konzepte/a", Seitentyp.KONZEPT, List.of("eins", "zwei"),
                "Text.[^q]", List.of(dokument(ALT)), null, false, null);
        Wissensseite b = seite(B, "konzepte/b", Seitentyp.KONZEPT, List.of("eins", "zwei"),
                "[A](/konzepte/a) [^q]", List.of(dokument(ALT)), null, false, null);
        var result = pruefen(List.of(a, b), Map.of(ALT, quelle(2025, false)), 0);
        assertThat(result.paare()).isEmpty();
        assertThat(result.befunde()).noneMatch(befund -> befund.code().equals("QUERVERWEIS_FEHLT"));
    }

    @Test
    void ungeprueftePaareKommenVorFrueherGeprueftenAuchBeiAndererEingabereihenfolge() {
        Wissensseite a = seite(A, "konzepte/a", Seitentyp.KONZEPT, List.of(),
                "[B](/konzepte/b) [^q]", List.of(dokument(ALT)), null, false, null);
        Wissensseite b = seite(B, "konzepte/b", Seitentyp.KONZEPT, List.of(),
                "Text.[^q]", List.of(dokument(ALT)), null, false, null);
        Wissensseite q = seite(Q, "konzepte/c", Seitentyp.KONZEPT, List.of(),
                "Text.[^q]", List.of(dokument(ALT)), null, false, null);
        WissensGraph graph = new WissensGraph(Map.of(A, Set.of(ALT), B, Set.of(ALT), Q, Set.of(ALT)),
                Map.of(), Map.of(ALT, WissensStufe.NORMAL),
                Map.of(A, WissensStufe.NORMAL, B, WissensStufe.NORMAL, Q, WissensStufe.NORMAL));
        var geprueft = Map.of(new WissensLint.PaarId(A, Q), Instant.EPOCH,
                new WissensLint.PaarId(B, Q), Instant.EPOCH);

        var result = WissensLint.pruefen(List.of(new WissensLint.Seite(q), new WissensLint.Seite(b),
                        new WissensLint.Seite(a)), graph, Map.of(ALT, quelle(2025, false)), geprueft,
                LocalDate.of(2026, 11, 20), 20_000, 1);
        assertThat(result.paare()).singleElement().satisfies(paar ->
                assertThat(paar.id()).isEqualTo(new WissensLint.PaarId(A, B)));
    }

    private static WissensLint.Ergebnis pruefen(List<Wissensseite> seiten,
                                                Map<UUID, WissensPruefung.DokumentQuelle> dokumente, int limit) {
        Map<UUID, Set<UUID>> huellen = seiten.stream().collect(java.util.stream.Collectors.toMap(
                Wissensseite::id, s -> s.quellen().stream().map(Quelleneintrag::dokumentId)
                        .filter(java.util.Objects::nonNull).collect(java.util.stream.Collectors.toSet())));
        Map<UUID, WissensStufe> mindest = seiten.stream().collect(java.util.stream.Collectors.toMap(
                Wissensseite::id, s -> WissensStufe.NORMAL));
        WissensGraph graph = new WissensGraph(huellen, Map.of(), Map.of(ALT, WissensStufe.NORMAL,
                NEU, WissensStufe.INTERN), mindest);
        return WissensLint.pruefen(seiten.stream().map(WissensLint.Seite::new).toList(),
                graph, dokumente, Map.of(), LocalDate.of(2026, 11, 20), 20_000, limit);
    }

    private static WissensPruefung.DokumentQuelle quelle(int jahr, boolean personal) {
        return new WissensPruefung.DokumentQuelle(true, true, personal, 1, LocalDate.of(jahr, 1, 1),
                Optional.of(ch.urinet.archiv.gemeinsam.domain.Seitentext.von("Text.\f", 1).orElseThrow()));
    }

    private static Quelleneintrag dokument(UUID id) {
        return new Quelleneintrag("q", id, 1, 1, null, null);
    }

    private static Wissensseite seite(UUID id, String pfad, Seitentyp typ, List<String> tags, String inhalt,
                                       List<Quelleneintrag> quellen, LocalDate veraltetAb, boolean quellePruefen,
                                       UUID wissensquelleId) {
        return new Wissensseite(id, pfad, typ, "Titel", "Beschreibung.", tags, Wissensseite.Status.AKTIV,
                veraltetAb, quellen, Wissensseite.Vertraulichkeit.NORMAL, inhalt, 1, "human:test",
                Instant.EPOCH, "human:test", Instant.EPOCH, quellePruefen, false, wissensquelleId);
    }
}
