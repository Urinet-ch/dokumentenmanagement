package ch.urinet.archiv.wissen.adapter.out.okf;

import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.wissen.domain.Quelleneintrag;
import ch.urinet.archiv.wissen.domain.Seitentyp;
import ch.urinet.archiv.wissen.domain.Wissensseite;
import org.commonmark.node.Link;
import org.commonmark.node.Node;
import org.commonmark.parser.Parser;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.events.AliasEvent;
import org.yaml.snakeyaml.events.NodeEvent;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIOException;

class OkfKonformitaetTest {
    private static final Instant ZEIT = Instant.parse("2026-01-02T03:04:05Z");
    private static final UUID DOKUMENT = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID QUELLSEITE = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID KONZEPT = UUID.fromString("00000000-0000-0000-0000-000000000003");
    private static final String HASH = "a".repeat(64);

    @Test
    void paketEnthaeltOkfFrontmatterQuellenLinksUndImportangaben() throws Exception {
        Quelleneintrag dokument = new Quelleneintrag("q1", DOKUMENT, 2, 4, "Wörtlicher Auszug", null);
        Quelleneintrag seite = new Quelleneintrag("q2", null, null, null, null, QUELLSEITE);
        Wissensseite quellseite = seite(QUELLSEITE, "quellen/probe", Seitentyp.QUELLE, "Quelle",
                "Aussage.[^q1]", List.of(dokument), DOKUMENT, Wissensseite.Vertraulichkeit.INTERN);
        Wissensseite konzept = seite(KONZEPT, "konzepte/probe", Seitentyp.KONZEPT, "Konzept",
                "[Quelle](/quellen/probe).[^q2]\n`[Code](/quellen/probe)`", List.of(seite), null,
                Wissensseite.Vertraulichkeit.NORMAL);
        OkfSchreiber.Dokument dokumentDaten = new OkfSchreiber.Dokument(DOKUMENT, HASH, "Dokument",
                LocalDate.of(2026, 1, 1));
        OkfSchreiber.Seitenverweis verweis = new OkfSchreiber.Seitenverweis(QUELLSEITE, quellseite.pfad(),
                quellseite.titel(), ZEIT);
        List<OkfSchreiber.Seite> seiten = List.of(
                new OkfSchreiber.Seite(quellseite, Map.of(DOKUMENT, dokumentDaten), Map.of()),
                new OkfSchreiber.Seite(konzept, Map.of(), Map.of(QUELLSEITE, verweis)));
        ByteArrayOutputStream ausgabe = new ByteArrayOutputStream();

        OkfSchreiber.schreiben(paket(Set.of(quellseite.pfad(), konzept.pfad()), "lokal"), seiten,
                List.of(new OkfSchreiber.Logzeile(ZEIT, "Ingest", "Quelle ergänzt")), ausgabe);

        Map<String, String> dateien = zip(ausgabe.toByteArray());
        assertThat(dateien.keySet()).containsExactlyInAnyOrder(
                "quellen/probe.md", "konzepte/probe.md", "paket.yaml", "index.md", "log.md");
        Map<?, ?> paket = yaml(dateien.get("paket.yaml"));
        assertThat(paket.get("paket_format")).isEqualTo(1);
        assertThat(paket.get("mandant")).isEqualTo("urinet");
        assertThat(paket.get("erzeugt_mit")).isEqualTo("lokal");
        assertThat(paket.get("kontext_stufe")).isEqualTo("INTERN");
        assertThat(paket.get("herkunft")).isEqualTo(Map.of("instanz", "probe"));
        Map<?, ?> quellkopf = kopf(dateien.get("quellen/probe.md"));
        assertThat(quellkopf.get("type")).isEqualTo("Quelle");
        assertThat(quellkopf.get("basis_version")).isEqualTo(2);
        assertThat(quellkopf.get("erzeugt_von")).isEqualTo("urinet-archiv");
        List<?> quellen = (List<?>) quellkopf.get("sources");
        assertThat(quellen).hasSize(1);
        Map<?, ?> q = (Map<?, ?>) quellen.getFirst();
        assertThat(q.get("resource")).isEqualTo("urn:urinet-archiv:urinet:dokument:" + DOKUMENT + "#page=2");
        assertThat(q.get("sha256")).isEqualTo(HASH);
        assertThat(q.get("seite")).isEqualTo("2-4");
        assertThat(q.get("auszug")).isEqualTo("Wörtlicher Auszug");
        assertThat(q.get("wissensquelle")).isEqualTo(true);
        Map<?, ?> konzeptkopf = kopf(dateien.get("konzepte/probe.md"));
        assertThat(((Map<?, ?>) ((List<?>) konzeptkopf.get("sources")).getFirst()).get("resource"))
                .isEqualTo("/quellen/probe.md");
        assertThat(dateien.get("konzepte/probe.md"))
                .contains("[Quelle](/quellen/probe.md).[^q2]", "`[Code](/quellen/probe)`");
        assertThat(kopf(dateien.get("index.md")).get("okf_version")).isEqualTo("0.2");
        assertThat(dateien.get("index.md")).contains("[Quelle](/quellen/probe.md)");
        assertThat(dateien.get("log.md")).contains("# Update Log", "## 2026-01-02", "* **Ingest**: Quelle ergänzt");
        for (String dateiname : List.of("quellen/probe.md", "konzepte/probe.md")) {
            assertThat(kopf(dateien.get(dateiname)).get("type")).isInstanceOfSatisfying(
                    String.class, typ -> assertThat(typ).isNotBlank());
        }
        for (String dateiname : List.of("index.md", "quellen/probe.md", "konzepte/probe.md")) {
            linksPruefen(dateien.get(dateiname), dateien.keySet());
        }
        for (String datei : List.of(dateien.get("paket.yaml"), frontmatter(dateien.get("quellen/probe.md")),
                frontmatter(dateien.get("konzepte/probe.md")), frontmatter(dateien.get("index.md")))) {
            assertThat(new Yaml().parse(new StringReader(datei)))
                    .noneMatch(event -> event instanceof AliasEvent
                            || event instanceof NodeEvent node && node.getAnchor() != null);
        }
    }

    @Test
    void fehlendeLinkzieleOderFalschePaketherkunftBrechenAb() {
        Wissensseite seite = seite(KONZEPT, "konzepte/probe", Seitentyp.KONZEPT, "Konzept",
                "[Fehlt](/konzepte/fehlt)", List.of(), null, Wissensseite.Vertraulichkeit.NORMAL);
        OkfSchreiber.Seite daten = new OkfSchreiber.Seite(seite, Map.of(), Map.of());
        assertThatIllegalArgumentException().isThrownBy(() -> OkfSchreiber.schreiben(
                paket(Set.of(seite.pfad()), "lokal"), List.of(daten), List.of(), new ByteArrayOutputStream()));
        Wissensseite intern = seite(KONZEPT, "konzepte/probe", Seitentyp.KONZEPT, "Konzept",
                "Text", List.of(), null, Wissensseite.Vertraulichkeit.INTERN);
        assertThatIllegalArgumentException().isThrownBy(() -> OkfSchreiber.schreiben(
                paket(Set.of(intern.pfad()), "cloud"),
                List.of(new OkfSchreiber.Seite(intern, Map.of(), Map.of())), List.of(),
                new ByteArrayOutputStream()));
    }

    @Test
    void importgrenzenWerdenVorEinerUnbrauchbarenAusgabeErzwungen() {
        Set<String> zuViele = java.util.stream.IntStream.range(0, 201)
                .mapToObj(n -> "konzepte/seite-" + n).collect(java.util.stream.Collectors.toSet());
        assertThatIllegalArgumentException().isThrownBy(() -> paket(zuViele, "lokal"));

        Wissensseite langerTitel = seite(KONZEPT, "konzepte/probe", Seitentyp.KONZEPT, "x".repeat(17_000),
                "Text", List.of(), null, Wissensseite.Vertraulichkeit.NORMAL);
        assertThatIllegalArgumentException().isThrownBy(() -> OkfSchreiber.schreiben(
                paket(Set.of(langerTitel.pfad()), "lokal"),
                List.of(new OkfSchreiber.Seite(langerTitel, Map.of(), Map.of())), List.of(),
                new ByteArrayOutputStream()));

        assertThatIOException().isThrownBy(() -> OkfSchreiber.schreiben(
                paket(Set.of(), "lokal"), List.of(),
                List.of(new OkfSchreiber.Logzeile(ZEIT, "Ingest", "x".repeat(128 * 1024))),
                new ByteArrayOutputStream()));

        Wissensseite starkKomprimierbar = seite(KONZEPT, "konzepte/probe", Seitentyp.KONZEPT, "Konzept",
                "x".repeat(100_000), List.of(), null, Wissensseite.Vertraulichkeit.NORMAL);
        assertThatIOException().isThrownBy(() -> OkfSchreiber.schreiben(
                paket(Set.of(starkKomprimierbar.pfad()), "lokal"),
                List.of(new OkfSchreiber.Seite(starkKomprimierbar, Map.of(), Map.of())), List.of(),
                new ByteArrayOutputStream())).withMessageContaining("Kompressionsverhältnis");
    }

    private static Wissensseite seite(UUID id, String pfad, Seitentyp typ, String titel, String inhalt,
                                      List<Quelleneintrag> quellen, UUID wissensquelle,
                                      Wissensseite.Vertraulichkeit vertraulichkeit) {
        return new Wissensseite(id, pfad, typ, titel, "Beschreibung", List.of("probe"), Wissensseite.Status.AKTIV,
                null, quellen, vertraulichkeit, inhalt, 2, "human:probe", ZEIT, "probe", ZEIT, false, false,
                wissensquelle);
    }

    private static OkfSchreiber.Paket paket(Set<String> pfade, String erzeugtMit) {
        return new OkfSchreiber.Paket(new MandantCode("urinet"), "urinet-archiv", erzeugtMit, ZEIT, "probe", pfade);
    }

    private static Map<String, String> zip(byte[] daten) throws Exception {
        Map<String, String> dateien = new LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(daten), StandardCharsets.UTF_8)) {
            for (var eintrag = zip.getNextEntry(); eintrag != null; eintrag = zip.getNextEntry()) {
                dateien.put(eintrag.getName(), new String(zip.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        return dateien;
    }

    private static String frontmatter(String markdown) {
        int ende = markdown.indexOf("\n---\n", 4);
        assertThat(ende).isGreaterThanOrEqualTo(4);
        return markdown.substring(4, ende);
    }

    private static Map<?, ?> kopf(String markdown) {
        return yaml(frontmatter(markdown));
    }

    private static Map<?, ?> yaml(String text) {
        return new Yaml(new SafeConstructor(new LoaderOptions())).load(text);
    }

    private static void linksPruefen(String markdown, Set<String> dateien) {
        Deque<Node> offen = new ArrayDeque<>();
        offen.push(Parser.builder().build().parse(markdown));
        while (!offen.isEmpty()) {
            Node node = offen.pop();
            if (node instanceof Link link && link.getDestination().startsWith("/")) {
                assertThat(dateien).contains(link.getDestination().substring(1));
            }
            for (Node kind = node.getFirstChild(); kind != null; kind = kind.getNext()) {
                offen.push(kind);
            }
        }
    }
}
