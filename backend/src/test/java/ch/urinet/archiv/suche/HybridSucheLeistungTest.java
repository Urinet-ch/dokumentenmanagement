package ch.urinet.archiv.suche;

import ch.urinet.archiv.dokument.Testdokumente;
import ch.urinet.archiv.ki.application.EmbeddingPort;
import ch.urinet.archiv.ki.application.KiPort;
import ch.urinet.archiv.ki.application.RerankerPort;
import ch.urinet.archiv.ki.domain.Vektortext;
import ch.urinet.archiv.mandant.application.MandantKontext;
import ch.urinet.archiv.mandant.application.MandantProvisionierung;
import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.sicherheit.domain.Benutzer;
import ch.urinet.archiv.sicherheit.domain.Rolle;
import ch.urinet.archiv.sicherheit.domain.Sichtregel;
import ch.urinet.archiv.suche.application.HybridSuche;
import ch.urinet.archiv.suche.application.SuchePort;
import ch.urinet.archiv.suche.application.SucheTransaktion;
import ch.urinet.archiv.suche.application.VektorSuchePort;
import ch.urinet.archiv.suche.domain.Hybridergebnis;
import ch.urinet.archiv.suche.domain.Suchanfrage;
import io.agroal.api.AgroalDataSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Grössenordnung der Datenbankteile der Hybridsuche (Spec 02, Aufgabe 6.4, Req 2.6 nur als Teilnachweis): Volltext,
 * Vektor-Abfrage über HNSW, Texte und Details mit 5'000 synthetischen archivierten Dokumenten zu je 1–3 Abschnitten
 * mit deterministischen Zufallsvektoren ({@value EmbeddingPort#DIMENSION} Dimensionen). Embedding und Reranker sind
 * Fakes ohne Latenz. Nur auf Wunsch ({@code -Darchiv.hybrid-leistung=true}), weil das Anlegen des Index Minuten dauert;
 * die Grenze ist locker, die Messung p95 ≤ 2 s mit 50'000 Dokumenten folgt auf Stufe B. Messwert in design.md.
 */
@QuarkusTest
@EnabledIfSystemProperty(named = "archiv.hybrid-leistung", matches = "true")
class HybridSucheLeistungTest {

    private static final int DOKUMENTE = 5_000;
    private static final int AUFWAERMEN = 20;
    private static final int SUCHEN = 200;
    private static final String MANDANT = "urinet";
    private static final String[] WOERTER = {"Rechnung", "Lieferung", "Versicherung", "Prämie", "Police", "Offerte",
            "Vertrag", "Miete", "Lohn", "Spesen", "Reparatur", "Wartung", "Server", "Hosting", "Domain", "Telefon",
            "Fahrzeug", "Treibstoff", "Schulung", "Beratung", "Material", "Lizenz", "Porto", "Mahnung", "Gutschrift"};
    private static final String[] VERTRAULICHKEIT = {"NORMAL", "NORMAL", "NORMAL", "INTERN", "PERSONAL"};

    @Inject
    SuchePort volltext;

    @Inject
    VektorSuchePort vektor;

    @Inject
    SucheTransaktion transaktion;

    @Inject
    MandantKontext kontext;

    @Inject
    MandantProvisionierung provisionierung;

    @Inject
    Instance<AgroalDataSource> datenbank;

    private final Random zufall = new Random(64);
    private final List<UUID> ids = new ArrayList<>();
    private final String lauf = "lauf" + Long.toHexString(new Random().nextLong());

    @BeforeEach
    void anlegen() throws SQLException {
        String schema = new MandantCode(MANDANT).schema();
        try (Connection verbindung = datenbank.get().getConnection()) {
            try (PreparedStatement abfrage = verbindung.prepareStatement("select 1 from public.mandant where code = ?")) {
                abfrage.setString(1, MANDANT);
                try (ResultSet zeile = abfrage.executeQuery()) {
                    if (!zeile.next()) {
                        provisionierung.anlegen(MANDANT, null, null);
                    }
                }
            }
            verbindung.setAutoCommit(false);
            try (PreparedStatement dokument = verbindung.prepareStatement("insert into " + schema + ".dokument (id, "
                    + "art, titel, quelle, partner_name, tags, vertraulichkeit, person_benutzer, erstellt_von, status, "
                    + "bestaetigt, bestaetigt_von, volltext) values (?, 'OFFERTE', ?, 'UPLOAD', 'Muster AG', ?, ?, ?, "
                    + "'probe', 'ARCHIVIERT', now(), 'probe', ?)");
                 PreparedStatement chunk = verbindung.prepareStatement("insert into " + schema + ".chunk "
                         + "(dokument_id, nr, text, seite_von, seite_bis, embedding) values (?, ?, ?, ?, ?, "
                         + "?::public.halfvec)")) {
                for (int i = 0; i < DOKUMENTE; i++) {
                    UUID id = UUID.randomUUID();
                    ids.add(id);
                    String vertraulichkeit = VERTRAULICHKEIT[zufall.nextInt(VERTRAULICHKEIT.length)];
                    String text = text();
                    dokument.setObject(1, id);
                    dokument.setString(2, wort() + " " + wort() + " " + i);
                    dokument.setArray(3, verbindung.createArrayOf("text", new Object[] {lauf}));
                    dokument.setString(4, vertraulichkeit);
                    dokument.setString(5, "PERSONAL".equals(vertraulichkeit) ? "joel" : null);
                    dokument.setString(6, text);
                    dokument.addBatch();
                    int abschnitte = 1 + zufall.nextInt(3);
                    for (int nr = 1; nr <= abschnitte; nr++) {
                        chunk.setObject(1, id);
                        chunk.setInt(2, nr);
                        chunk.setString(3, "Kopf\n\n" + text.substring(0, Math.min(text.length(), 1_000)));
                        chunk.setInt(4, nr);
                        chunk.setInt(5, nr);
                        chunk.setString(6, Vektortext.text(zufallsvektor()));
                        chunk.addBatch();
                    }
                    if (i % 250 == 249) {
                        dokument.executeBatch();
                        chunk.executeBatch();
                        verbindung.commit();
                    }
                }
                dokument.executeBatch();
                chunk.executeBatch();
                verbindung.commit();
            } catch (SQLException e) {
                verbindung.rollback();
                throw e;
            } finally {
                verbindung.setAutoCommit(true);
            }
            try (Statement analysieren = verbindung.createStatement()) {
                analysieren.execute("analyze " + schema + ".dokument");
                analysieren.execute("analyze " + schema + ".chunk");
            }
        }
    }

    @AfterEach
    void aufraeumen() throws SQLException {
        Testdokumente.loeschen(datenbank.get(), MANDANT, ids);
    }

    @Test
    void datenbankteileMitFuenftausendDokumenten() throws Exception {
        HybridSuche suche = new HybridSuche(volltext, vektor, new Embedding(), new Reranker(), (zweck, v) -> {
        }, transaktion);
        List<Sichtregel> regeln = List.of(Sichtregel.fuer(new Benutzer("joel", Rolle.MITARBEITER)),
                Sichtregel.fuer(new Benutzer("fabian", Rolle.INHABER)));
        ExecutorService worker = Executors.newSingleThreadExecutor();
        long[] dauer = new long[SUCHEN];
        long treffer = 0;
        try {
            for (int i = 0; i < AUFWAERMEN; i++) {
                suchen(worker, suche, regeln.get(i % 2));
            }
            for (int i = 0; i < SUCHEN; i++) {
                long start = System.nanoTime();
                Hybridergebnis ergebnis = suchen(worker, suche, regeln.get(i % 2));
                dauer[i] = System.nanoTime() - start;
                assertThat(ergebnis.degradiert()).isFalse();
                treffer += ergebnis.treffer().size();
            }
        } finally {
            worker.shutdown();
        }
        Arrays.sort(dauer);
        long p50 = dauer[SUCHEN / 2] / 1_000_000;
        long p95 = dauer[SUCHEN * 95 / 100] / 1_000_000;
        long max = dauer[SUCHEN - 1] / 1_000_000;
        System.out.printf("Hybridsuche (Datenbankteile) mit %d Dokumenten: p50 %d ms, p95 %d ms, Max %d ms "
                + "(%d Suchen, im Mittel %d Treffer)%n", DOKUMENTE, p50, p95, max, SUCHEN, treffer / SUCHEN);
        assertThat(treffer).isPositive();
        assertThat(p95).as("p95 in ms").isLessThan(2_000);
    }

    private Hybridergebnis suchen(ExecutorService worker, HybridSuche suche, Sichtregel regel) throws Exception {
        Suchanfrage anfrage = new Suchanfrage(wort(), null, null, null, null, null, null, null, List.of(lauf), null,
                null, null, null, 0, Suchanfrage.GROESSE_STANDARD);
        return worker.submit(() -> kontext.mit(new MandantCode(MANDANT),
                () -> suche.suchen(anfrage, regel, true, HybridSuche.PWA))).get(60, TimeUnit.SECONDS);
    }

    private String wort() {
        return WOERTER[zufall.nextInt(WOERTER.length)];
    }

    private String text() {
        StringBuilder text = new StringBuilder();
        while (text.length() < 1_500) {
            text.append(wort()).append(zufall.nextInt(4) == 0 ? ". " : " ");
        }
        return text.toString();
    }

    private float[] zufallsvektor() {
        float[] v = new float[EmbeddingPort.DIMENSION];
        for (int i = 0; i < v.length; i++) {
            v[i] = (float) zufall.nextGaussian();
        }
        return v;
    }

    /** Frage als Zufallsvektor, ohne Request und ohne Latenz. */
    private final class Embedding implements EmbeddingPort {
        @Override
        public Antwort einbetten(List<String> texte) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Antwort einbettenFrage(String frage) {
            return new Vektoren(List.of(zufallsvektor()), new KiPort.Verbrauch("fake", 0, 0));
        }
    }

    /** Reihenfolge wie gesendet, ohne Request und ohne Latenz. */
    private static final class Reranker implements RerankerPort {
        @Override
        public Antwort sortieren(String frage, List<String> texte, int topN) {
            List<Integer> indizes = IntStream.range(0, topN).boxed().toList();
            return new Sortiert(indizes, indizes.stream().map(i -> 1.0).toList(), new KiPort.Verbrauch("fake", 0, 0));
        }
    }
}
