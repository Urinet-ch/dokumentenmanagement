package ch.urinet.archiv.wissen;

import ch.urinet.archiv.ablage.TestAblage;
import ch.urinet.archiv.dokument.Testdokumente;
import ch.urinet.archiv.gemeinsam.Testanmeldung;
import ch.urinet.archiv.ki.KiWireMock;
import ch.urinet.archiv.verarbeitung.application.AuftragArbeiter;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agroal.api.AgroalDataSource;
import io.cucumber.java.After;
import io.cucumber.java.de.Angenommen;
import io.cucumber.java.de.Dann;
import io.cucumber.java.de.Wenn;
import jakarta.inject.Inject;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/** Ende-zu-Ende-Lint mit synthetischen Dokumenten und WireMock. */
public class WissenLintKiSchritte {
    @Inject AgroalDataSource datenbank;
    @Inject AuftragArbeiter arbeiter;

    private final List<UUID> dokumente = new ArrayList<>();
    private final List<UUID> seiten = new ArrayList<>();
    private final Map<String, String> dateiversionen = new LinkedHashMap<>();
    private String praefix;
    private UUID lintId;
    private UUID normal;
    private UUID intern;
    private UUID normalDokument;
    private Integer vorherigesLintBudget;
    private boolean budgetGeaendert;
    private List<String> dokumentVorher;
    private List<String> dateiVorher;
    private List<String> versionenVorher;
    private long letzterProtokollEintrag;
    private final String paarTag = UUID.randomUUID().toString();

    @Angenommen("zwei verknüpfbare Lint-Seiten mit Stufen NORMAL und INTERN und eine PERSONAL-Hülle bestehen")
    public void gemischtesPaar() throws SQLException {
        fixture(false, true);
    }

    @Angenommen("zwei verknüpfbare Lint-Seiten mit Stufe NORMAL bestehen")
    public void gleichesPaar() throws SQLException {
        fixture(true, false);
    }

    private void fixture(boolean gleich, boolean personal) throws SQLException {
        praefix = "lint-cucumber/" + UUID.randomUUID() + "/";
        for (String datei : List.of("original", "arbeitsfassung", "pdfa")) {
            dateiversionen.put(datei, TestAblage.ablegen("urinet", praefix + datei,
                    datei.getBytes(StandardCharsets.UTF_8)));
        }
        normalDokument = dokument("NORMAL");
        UUID zweitesDokument = dokument(gleich ? "NORMAL" : "INTERN");
        normal = seite("normal", "NORMAL", "q-a", normalDokument, "NORMAL_MARKER");
        intern = seite("intern", gleich ? "NORMAL" : "INTERN", "q-b", zweitesDokument, "INTERN_MARKER");
        if (personal) {
            UUID personalDokument = dokument("PERSONAL");
            seite("personal", "INTERN", "q-p", personalDokument, "PERSONAL_MARKER");
        }
        sql("update m_urinet.dokument set s3_pdfa = ?, s3_version_pdfa = ? where id = ?",
                praefix + "pdfa", dateiversionen.get("pdfa"), normalDokument);
        for (String rolle : List.of("ORIGINAL", "ARBEITSFASSUNG")) {
            String name = rolle.equals("ORIGINAL") ? "original" : "arbeitsfassung";
            sql("""
                    insert into m_urinet.dokument_datei (dokument_id, nr, rolle, sha256, s3_schluessel, s3_version)
                    values (?, 1, ?, ?, ?, ?)
                    """, normalDokument, rolle, normalDokument.toString().replace("-", "").repeat(2),
                    praefix + name, dateiversionen.get(name));
        }
        dokumentVorher = zeilen("select row_to_json(d)::text from m_urinet.dokument d where id = ?", normalDokument);
        dateiVorher = zeilen("""
                select row_to_json(d)::text from m_urinet.dokument_datei d where dokument_id = ? order by rolle
                """, normalDokument);
        versionenVorher = TestAblage.versionen("urinet", praefix);
        letzterProtokollEintrag = Long.parseLong(zeilen(
                "select coalesce(max(id), 0)::text from m_urinet.zugriff_protokoll").getFirst());
    }

    private UUID dokument(String stufe) throws SQLException {
        UUID id = Testdokumente.anlegen(datenbank, new Testdokumente.Angaben(
                "urinet", "WISSENSQUELLE", stufe, true, "probe", null, "ARCHIVIERT"));
        dokumente.add(id);
        sql("update m_urinet.dokument set seiten = 1, volltext = 'Synthetischer Volltext' where id = ?", id);
        return id;
    }

    private UUID seite(String name, String stufe, String schluessel, UUID dokument, String marker) throws SQLException {
        UUID id = UUID.randomUUID();
        seiten.add(id);
        sql("""
                insert into m_urinet.wissensseite
                    (id, pfad, typ, titel, beschreibung, tags, status, vertraulichkeit, vertraulichkeit_mindest,
                     inhalt, version, erzeugt_von, erzeugt_am, freigegeben_von, freigegeben_am)
                values (?, ?, 'Konzept', ?, 'Synthetische Beschreibung', array['lintprobe', ?],
                        'AKTIV', ?, ?, ?, 1, 'probe', now(), 'probe', now())
                """, id, "konzepte/lint-" + name + "-" + id, marker, paarTag, stufe, stufe,
                "Aussage " + marker + ". [^" + schluessel + "]");
        sql("""
                insert into m_urinet.wissensseite_quelle (seite_id, schluessel, dokument_id, seite_von)
                values (?, ?, ?, 1)
                """, id, schluessel, dokument);
        return id;
    }

    @Angenommen("WireMock erkennt den Lint-Widerspruch und liefert Seitenänderungen")
    public void widerspruch() {
        antworten(100, 50);
    }

    private void antworten(int ein, int aus) {
        String paar = """
                {"widersprueche":[{"seite":"%s","schluessel":"q-a","gegenseite":"%s",
                "gegenschluessel":"q-b","text":"Angaben unterscheiden sich."}],"veraltet":[],"links":[]}
                """.formatted(normal, intern);
        String aenderung = """
                {"titel":"Probe","beschreibung":"Synthetische Beschreibung","tags":["lintprobe","synthetisch"],
                "inhalt":"Aussage. [^q-b]\\n\\n> ⚠️ Widerspruch: Angaben unterscheiden sich. [^q-a] [^q-b]",
                "widersprueche":[{"bestehend":"q-b","neu":"q-a","text":"Unterschied"}],
                "entfernte_aussagen":[],"begruendung":"Prüfung"}
                """;
        KiWireMock.zuruecksetzen();
        KiWireMock.wissenFolge(List.of(paar, aenderung, aenderung), 0, ein, aus, paarTag);
    }

    @Angenommen("die Lint-KI liefert zweimal eine ungültige Paarantwort")
    public void ungueltigeAntworten() {
        KiWireMock.zuruecksetzen();
        KiWireMock.wissenFolge(List.of("ungültig", "ungültig"), 0, 100, 50, paarTag);
    }

    @Angenommen("das Lint-Laufbudget erlaubt zwei KI-Aufrufe und danach keinen weiteren")
    public void budgetBegrenzen() throws SQLException {
        try (Connection c = datenbank.getConnection();
             var p = c.prepareStatement("select lint_budget_tokens from m_urinet.wissens_einstellung where id = 1");
             var r = p.executeQuery()) {
            assertThat(r.next()).isTrue();
            vorherigesLintBudget = (Integer) r.getObject(1);
        }
        budgetGeaendert = true;
        sql("update m_urinet.wissens_einstellung set lint_budget_tokens = 14500 where id = 1");
        antworten(7000, 0);
    }

    @Wenn("der Inhaber den Lint für das Seitenpaar startet")
    public void starten() {
        var antwort = given().auth().oauth2(Testanmeldung.token("inhaber-urinet"))
                .when().post("/api/v1/wissensbasis/lint");
        assertThat(antwort.statusCode()).isEqualTo(202);
        lintId = UUID.fromString(antwort.jsonPath().getString("lintId"));
    }

    @Wenn("nach dem Paarauftrag wird die normale Quelle PERSONAL")
    public void persoenlichNachPaar() throws Exception {
        long ende = System.nanoTime() + TimeUnit.SECONDS.toNanos(45);
        while (kiAnfragen().isEmpty() && System.nanoTime() < ende) {
            einenTakt();
        }
        assertThat(kiAnfragen()).hasSize(1);
        sql("update m_urinet.dokument set vertraulichkeit = 'PERSONAL' where id = ?", normalDokument);
    }

    @Dann("der Lint verarbeitet die Teilaufträge mit höchstens einem KI-Aufruf je Auftrag")
    public void verarbeiten() throws Exception {
        long ende = System.nanoTime() + TimeUnit.SECONDS.toNanos(45);
        while (System.nanoTime() < ende) {
            List<String> beendet = zeilen("select (beendet is not null)::text from m_urinet.wissens_lint where id = ?",
                    lintId);
            assertThat(beendet).hasSize(1);
            if ("true".equals(beendet.getFirst())) {
                return;
            }
            einenTakt();
        }
        throw new AssertionError("Lint wurde nicht abgeschlossen");
    }

    private void einenTakt() throws Exception {
        int vorher = kiAnfragen().size();
        var laeufe = arbeiter.abholen();
        CompletableFuture.allOf(laeufe.toArray(CompletableFuture[]::new)).get(30, TimeUnit.SECONDS);
        assertThat(kiAnfragen().size() - vorher).isBetween(0, 1);
        if (laeufe.isEmpty()) {
            Thread.sleep(100);
        }
    }

    @Dann("nur die interne Seite erhält einen Lint-Entwurf und PERSONAL erreicht die KI nicht")
    public void nurIntern() throws SQLException {
        assertThat(kiAnfragen()).hasSize(2);
        assertThat(String.join("\n", kiAnfragen()))
                .contains("NORMAL_MARKER", "INTERN_MARKER").doesNotContain("PERSONAL_MARKER");
        assertThat(zeilen("""
                select a.seite_id::text || ':' || a.vertraulichkeit_kontext || ':' || a.basis_version::text
                from m_urinet.wissens_aenderung a
                join m_urinet.wissens_lint l on l.entwurf_id = a.entwurf_id where l.id = ?
                """, lintId)).containsExactly(intern + ":INTERN:1");
        assertThat(zeilen("""
                select b->>'code' from m_urinet.wissens_lint l,
                    jsonb_array_elements(l.befunde) b
                where l.id = ? and b->>'seiteId' = ?
                """, lintId, normal.toString())).contains("WIDERSPRUCH");
    }

    @Dann("die Originale, Arbeitsfassung, PDF-A, Volltext und Metadaten bleiben unverändert")
    public void unveraendert() throws SQLException {
        assertThat(zeilen("select row_to_json(d)::text from m_urinet.dokument d where id = ?", normalDokument))
                .isEqualTo(dokumentVorher);
        assertThat(zeilen("""
                select row_to_json(d)::text from m_urinet.dokument_datei d where dokument_id = ? order by rolle
                """, normalDokument)).isEqualTo(dateiVorher);
        assertThat(TestAblage.versionen("urinet", praefix)).containsExactlyInAnyOrderElementsOf(versionenVorher);
        dateiversionen.forEach((name, version) -> assertThat(TestAblage.lesen("urinet", praefix + name, version))
                .isEqualTo(name.getBytes(StandardCharsets.UTF_8)));
    }

    @Dann("Bericht und Protokolle enthalten keine Seiten- oder Dokumentinhalte")
    public void protokollOhneInhalte() throws Exception {
        List<String> bericht = zeilen("select befunde::text from m_urinet.wissens_lint where id = ?", lintId);
        List<String> log = zeilen("""
                select row_to_json(w)::text from m_urinet.wissens_log w
                join m_urinet.wissens_lint l on l.entwurf_id = w.entwurf_id
                where l.id = ? and w.aktion = 'LINT'
                """, lintId);
        List<String> zugriff = zeilen("""
                select row_to_json(p)::text from m_urinet.zugriff_protokoll p
                where p.id > ? and p.aktion = 'WISSEN_LINT'
                """, letzterProtokollEintrag);
        assertThat(bericht).hasSize(1);
        assertThat(log).hasSize(1);
        assertThat(zugriff).hasSize(1);
        assertThat(String.join("\n", bericht) + String.join("\n", log) + String.join("\n", zugriff))
                .doesNotContain("NORMAL_MARKER", "INTERN_MARKER", "PERSONAL_MARKER",
                        "Synthetischer Volltext", "Angaben unterscheiden sich.");
        var zahlen = new ObjectMapper().readTree(log.getFirst()).path("angaben");
        assertThat(zahlen.isObject()).isTrue();
        zahlen.fields().forEachRemaining(e -> assertThat(e.getValue().isIntegralNumber()).isTrue());
    }

    @Dann("nach dem PERSONAL-Wechsel erfolgt kein weiterer KI-Aufruf und keine Änderung")
    public void personalSchutz() throws SQLException {
        assertThat(kiAnfragen()).hasSize(1);
        assertThat(zeilen("""
                select a.id::text from m_urinet.wissens_aenderung a
                join m_urinet.wissens_lint l on l.entwurf_id = a.entwurf_id where l.id = ?
                """, lintId)).isEmpty();
    }

    @Dann("der Lint fragt einmal nach und beendet ohne Änderung mit Hinweis")
    public void nachfrage() throws SQLException {
        assertThat(kiAnfragen()).hasSize(2);
        assertThat(kiAnfragen()).anyMatch(anfrage -> anfrage.contains("vorige Antwort war ungültig"));
        assertThat(zeilen("""
                select e.unvollstaendig::text || ':' || (e.hinweis is not null)::text
                from m_urinet.wissens_lint l join m_urinet.wissens_entwurf e on e.id = l.entwurf_id where l.id = ?
                """, lintId)).containsExactly("true:true");
        assertThat(zeilen("""
                select a.id::text from m_urinet.wissens_aenderung a
                join m_urinet.wissens_lint l on l.entwurf_id = a.entwurf_id where l.id = ?
                """, lintId)).isEmpty();
    }

    @Dann("das erreichte Lint-Budget hinterlässt die bisherige Änderung als unvollständigen Entwurf")
    public void budgetErreicht() throws SQLException {
        assertThat(kiAnfragen()).hasSize(2);
        assertThat(kiAnfragen().stream().filter(a -> a.contains("wissen_ingest_lint_paar"))).hasSize(1);
        assertThat(kiAnfragen().stream().filter(a -> a.contains("wissen_ingest_seite"))).hasSize(1);
        assertThat(zeilen("""
                select e.status || ':' || e.unvollstaendig::text || ':' || count(a.id)::text
                from m_urinet.wissens_lint l join m_urinet.wissens_entwurf e on e.id = l.entwurf_id
                left join m_urinet.wissens_aenderung a on a.entwurf_id = e.id
                where l.id = ? group by e.status, e.unvollstaendig
                """, lintId)).containsExactly("OFFEN:true:1");
        assertThat(zeilen("""
                select count(*)::text from m_urinet.ki_verbrauch v
                join m_urinet.wissens_lint l on l.entwurf_id = v.ingest_entwurf_id
                where l.id = ? and v.zweck = 'WISSEN_LINT'
                """, lintId)).containsExactly("2");
    }

    private List<String> kiAnfragen() {
        return KiWireMock.wissenAnfragen().stream()
                .filter(anfrage -> seiten.stream().anyMatch(id -> anfrage.contains(id.toString())))
                .toList();
    }

    private void sql(String sql, Object... argumente) throws SQLException {
        try (Connection c = datenbank.getConnection(); var p = c.prepareStatement(sql)) {
            for (int i = 0; i < argumente.length; i++) {
                p.setObject(i + 1, argumente[i]);
            }
            p.executeUpdate();
        }
    }

    private List<String> zeilen(String sql, Object... argumente) throws SQLException {
        try (Connection c = datenbank.getConnection(); var p = c.prepareStatement(sql)) {
            for (int i = 0; i < argumente.length; i++) {
                p.setObject(i + 1, argumente[i]);
            }
            try (var r = p.executeQuery()) {
                List<String> result = new ArrayList<>();
                while (r.next()) {
                    result.add(r.getString(1));
                }
                return result;
            }
        }
    }

    @After
    public void aufraeumen() throws SQLException {
        TestWissenLint.loeschen(datenbank, lintId);
        for (UUID seite : seiten) {
            sql("delete from m_urinet.wissensseite where id = ?", seite);
        }
        Testdokumente.loeschen(datenbank, "urinet", dokumente);
        if (praefix != null) {
            TestAblage.loeschen("urinet", praefix, key -> key.startsWith(praefix));
        }
        if (budgetGeaendert) {
            sql("update m_urinet.wissens_einstellung set lint_budget_tokens = ? where id = 1", vorherigesLintBudget);
        }
    }
}
