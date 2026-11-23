package ch.urinet.archiv.gemeinsam;

import ch.urinet.archiv.dokument.Testdokumente;
import ch.urinet.archiv.ki.KiWireMock;
import ch.urinet.archiv.ki.domain.Vektortext;
import ch.urinet.archiv.mandant.application.MandantBucket;
import ch.urinet.archiv.mandant.application.MandantProvisionierung;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agroal.api.AgroalDataSource;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Manuell über tools/last/wissenssuche.sh, nie in CI. Die HTTP-Zeit einschliesslich Client ist eine
 * konservative Obergrenze für die Serverzeit; Modellantworten kommen ausschliesslich von WireMock.
 */
@QuarkusTest
@Tag("start-3")
@TestProfile(WissenssucheLeistungIT.Isoliert.class)
@EnabledIfSystemProperty(named = "archiv.wissen-leistung", matches = "true")
class WissenssucheLeistungIT {
    private static final int SEITEN = 2_000;
    private static final int WARM = 20;
    private static final int SUCHEN = 200;
    private static final List<String> BEGRIFFE = List.of("Vertrag", "Versicherung", "Rechnung", "Wartung",
            "Fahrzeug", "Schulung", "Lieferung", "Beratung", "Material", "Telefon");

    @Inject MandantProvisionierung provisionierung;
    @Inject Instance<AgroalDataSource> datenbank;
    @TestHTTPResource URI basis;

    public static class Isoliert implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.ofEntries(
                    Map.entry("quarkus.devservices.enabled", "false"),
                    Map.entry("quarkus.compose.devservices.enabled", "false"),
                    Map.entry("quarkus.datasource.devservices.enabled", "false"),
                    Map.entry("quarkus.http.host", "127.0.0.1"),
                    Map.entry("archiv.ablage.stub", "true"),
                    Map.entry("quarkus.s3.aws.credentials.type", "anonymous"),
                    Map.entry("quarkus.s3.endpoint-override", "http://127.0.0.1:1"),
                    Map.entry("archiv.ki.embedding-stub", "false"),
                    Map.entry("archiv.ki.reranker-stub", "false"),
                    Map.entry("infomaniak_ai_token", ""),
                    Map.entry("archiv.rolle", "api"),
                    Map.entry("archiv.worker.intervall", "off"));
        }

        @Override
        public Set<Class<?>> getEnabledAlternatives() {
            return Set.of(BucketAttrappe.class);
        }

        @Override
        public boolean disableGlobalTestResources() {
            return true;
        }

        @Override
        public List<TestResourceEntry> testResources() {
            return List.of(new TestResourceEntry(FakeKeycloak.class), new TestResourceEntry(KiWireMock.class));
        }
    }

    @Alternative
    @ApplicationScoped
    public static class BucketAttrappe implements MandantBucket {
        @Override
        public void einrichten(String bucket) {
        }

        @Override
        public boolean entfernenWennLeer(String bucket) {
            return true;
        }
    }

    @Test
    void hybriderHttpPfadMitZweitausendSeiten() throws Exception {
        String jdbc = ConfigProvider.getConfig().getValue("quarkus.datasource.jdbc.url", String.class);
        assertThat(jdbc).matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/wissen_leistung");
        try (Connection c = datenbank.get().getConnection(); Statement s = c.createStatement();
             var zeilen = s.executeQuery("select count(*) from public.mandant")) {
            assertThat(zeilen.next()).isTrue();
            assertThat(zeilen.getInt(1)).as("Leere Wegwerf-Datenbank").isZero();
        }
        provisionierung.anlegen("urinet", null, null);
        UUID normal = Testdokumente.anlegen(datenbank.get(),
                new Testdokumente.Angaben("urinet", "PROJEKTDOKUMENT", "NORMAL", true, "probe", null, "ARCHIVIERT"));
        UUID intern = Testdokumente.anlegen(datenbank.get(),
                new Testdokumente.Angaben("urinet", "PROJEKTDOKUMENT", "INTERN", true, "probe", null, "ARCHIVIERT"));
        Testdokumente.alsWissensquelleMarkieren(datenbank.get(), "urinet", normal);
        Testdokumente.alsWissensquelleMarkieren(datenbank.get(), "urinet", intern);
        Set<String> interneSeiten = anlegen(normal, intern);
        try (Connection c = datenbank.get().getConnection(); Statement s = c.createStatement();
             var zeilen = s.executeQuery("select count(*) from m_urinet.wissensseite")) {
            assertThat(zeilen.next()).isTrue();
            assertThat(zeilen.getInt(1)).as("Tatsächlich gespeicherte synthetische Wissensseiten").isEqualTo(SEITEN);
        }
        KiWireMock.zuruecksetzen();
        String issuer = ConfigProvider.getConfig().getValue("quarkus.oidc.auth-server-url", String.class);
        HttpClient client = HttpClient.newHttpClient();
        ObjectMapper json = new ObjectMapper();
        long[] laufzeiten = new long[SUCHEN];
        long treffer = 0;
        for (int i = 0; i < WARM + SUCHEN; i++) {
            String token = FakeKeycloak.signiertesJwt(FakeKeycloak.schluesselDerRessource(), issuer);
            URI uri = basis.resolve("/api/v1/wissensseiten?modus=hybrid&q=" + BEGRIFFE.get(i % BEGRIFFE.size()));
            HttpRequest anfrage = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(30))
                    .header("Authorization", "Bearer " + token).GET().build();
            long start = System.nanoTime();
            HttpResponse<String> antwort = client.send(anfrage, HttpResponse.BodyHandlers.ofString());
            long dauer = System.nanoTime() - start;
            assertThat(antwort.statusCode()).isEqualTo(200);
            var inhalt = json.readTree(antwort.body());
            assertThat(inhalt.path("degradiert").asBoolean(true)).isFalse();
            assertThat(inhalt.path("treffer").size()).isPositive();
            inhalt.path("treffer").forEach(t -> assertThat(interneSeiten).doesNotContain(t.path("id").asText()));
            if (i >= WARM) {
                laufzeiten[i - WARM] = dauer;
                treffer += inhalt.path("treffer").size();
            }
        }
        assertThat(KiWireMock.client().find(postRequestedFor(urlPathEqualTo("/v1/embeddings"))))
                .hasSize(WARM + SUCHEN);
        assertThat(KiWireMock.client().find(postRequestedFor(urlPathEqualTo(KiWireMock.RERANKER_PFAD))))
                .hasSize(WARM + SUCHEN);
        Arrays.sort(laufzeiten);
        double p50 = laufzeiten[(SUCHEN * 50 + 99) / 100 - 1] / 1_000_000.0;
        double p95 = laufzeiten[(SUCHEN * 95 + 99) / 100 - 1] / 1_000_000.0;
        double max = laufzeiten[SUCHEN - 1] / 1_000_000.0;
        System.out.printf(Locale.ROOT, "Wissenssuche HTTP: Seiten=%d Aufwaermen=%d Suchen=%d p50=%.3f ms p95=%.3f ms "
                + "max=%.3f ms mittlereTreffer=%.1f%n", SEITEN, WARM, SUCHEN, p50, p95, max,
                (double) treffer / SUCHEN);
        assertThat(p95).as("p95 der HTTP-Obergrenze in ms").isLessThanOrEqualTo(1_000);
    }

    private Set<String> anlegen(UUID normal, UUID intern) throws Exception {
        Set<String> interneSeiten = new HashSet<>();
        try (Connection c = datenbank.get().getConnection()) {
            c.setAutoCommit(false);
            try (PreparedStatement seite = c.prepareStatement("insert into m_urinet.wissensseite "
                    + "(id,pfad,typ,titel,beschreibung,vertraulichkeit,vertraulichkeit_mindest,inhalt,version,"
                    + "erzeugt_von,erzeugt_am,freigegeben_von,freigegeben_am) "
                    + "values (?,?,'Konzept',?,'Synthetische Beschreibung',?,?,?,1,'probe',now(),'probe',now())");
                 PreparedStatement version = c.prepareStatement("insert into m_urinet.wissensseite_version "
                         + "(seite_id,version,daten,inhalt,erzeugt_von,freigegeben_von,zeit) "
                         + "values (?,1,'{}',?,'probe','probe',now())");
                 PreparedStatement chunk = c.prepareStatement("insert into m_urinet.wissensseite_chunk "
                         + "(seite_id,version,nr,text,embedding) values (?,1,1,?,?::public.halfvec)");
                 PreparedStatement quelle = c.prepareStatement("insert into m_urinet.wissensseite_quelle "
                         + "(seite_id,schluessel,dokument_id,seite_von) values (?,'q1',?,1)");
                 PreparedStatement huelle = c.prepareStatement("insert into m_urinet.wissensseite_dokument "
                         + "(seite_id,dokument_id) values (?,?)")) {
                for (int i = 0; i < SEITEN; i++) {
                    UUID id = UUID.randomUUID();
                    String wort = BEGRIFFE.get(i % BEGRIFFE.size());
                    String text = (wort + " synthetische Angaben zur Wissensseite. ").repeat(35);
                    boolean istIntern = (i / 10) % 10 == 0;
                    String stufe = istIntern ? "INTERN" : "NORMAL";
                    UUID dokument = istIntern ? intern : normal;
                    if (istIntern) interneSeiten.add(id.toString());
                    seite.setObject(1, id);
                    seite.setString(2, "konzepte/last-" + i);
                    seite.setString(3, wort + " " + i);
                    seite.setString(4, stufe);
                    seite.setString(5, stufe);
                    seite.setString(6, text);
                    seite.addBatch();
                    version.setObject(1, id);
                    version.setString(2, text);
                    version.addBatch();
                    chunk.setObject(1, id);
                    chunk.setString(2, text);
                    chunk.setString(3, Vektortext.text(KiWireMock.vektor(wort)));
                    chunk.addBatch();
                    quelle.setObject(1, id);
                    quelle.setObject(2, dokument);
                    quelle.addBatch();
                    huelle.setObject(1, id);
                    huelle.setObject(2, dokument);
                    huelle.addBatch();
                    if (i % 100 == 99) {
                        seite.executeBatch();
                        version.executeBatch();
                        chunk.executeBatch();
                        quelle.executeBatch();
                        huelle.executeBatch();
                        c.commit();
                    }
                }
            }
            c.setAutoCommit(true);
            try (Statement s = c.createStatement()) {
                s.execute("vacuum analyze m_urinet.wissensseite");
                s.execute("vacuum analyze m_urinet.wissensseite_chunk");
                s.execute("vacuum analyze m_urinet.wissensseite_quelle");
                s.execute("vacuum analyze m_urinet.wissensseite_dokument");
            }
        }
        return interneSeiten;
    }
}
