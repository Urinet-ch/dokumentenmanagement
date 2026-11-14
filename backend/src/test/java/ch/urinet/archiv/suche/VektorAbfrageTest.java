package ch.urinet.archiv.suche;

import ch.urinet.archiv.dokument.Testdokumente;
import ch.urinet.archiv.ki.application.EmbeddingPort;
import ch.urinet.archiv.ki.domain.Vektortext;
import ch.urinet.archiv.mandant.application.MandantKontext;
import ch.urinet.archiv.mandant.application.MandantProvisionierung;
import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.sicherheit.domain.Benutzer;
import ch.urinet.archiv.sicherheit.domain.Rolle;
import ch.urinet.archiv.sicherheit.domain.Sichtregel;
import ch.urinet.archiv.suche.application.VektorSuchePort;
import ch.urinet.archiv.suche.domain.Abschnitttreffer;
import ch.urinet.archiv.suche.domain.Suchanfrage;
import io.agroal.api.AgroalDataSource;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Vektor-Abfrage der Hybridsuche gegen PostgreSQL mit pgvector als Dev Service (Spec 02, Aufgabe 6.4): Die Grenze von
 * 50 Abschnitten wird über HNSW erreicht (nicht nur {@code hnsw.ef_search} = 40), Sicht und Filter gelten vor der Grenze,
 * und je Dokument zählt der beste Abschnitt. Die Planer-Schalter zwingen die Abfrage über den HNSW-Index, sonst
 * sortierte PostgreSQL die wenigen Testzeilen ohne Index. Synthetische Daten; jeder Lauf filtert auf seinen Tag.
 */
@QuarkusTest
class VektorAbfrageTest {

    private static final String MANDANT = "urinet";
    private static final Sichtregel INHABER = Sichtregel.fuer(new Benutzer("fabian", Rolle.INHABER));
    private static final Sichtregel MITARBEITER = Sichtregel.fuer(new Benutzer("joel", Rolle.MITARBEITER));

    @Inject
    VektorSuchePort port;

    @Inject
    MandantKontext kontext;

    @Inject
    MandantProvisionierung provisionierung;

    @Inject
    Instance<AgroalDataSource> datenbank;

    @Inject
    EntityManager em;

    private static final float[] FRAGE = vektor(0, new Random(1));

    private final String lauf = "lauf" + Long.toHexString(new Random().nextLong());
    private final Random zufall = new Random(66);
    private final List<UUID> ids = new ArrayList<>();

    @BeforeEach
    void mandant() throws SQLException {
        try (Connection verbindung = datenbank.get().getConnection();
             PreparedStatement abfrage = verbindung.prepareStatement("select 1 from public.mandant where code = ?")) {
            abfrage.setString(1, MANDANT);
            try (ResultSet zeile = abfrage.executeQuery()) {
                if (!zeile.next()) {
                    provisionierung.anlegen(MANDANT, null, null);
                }
            }
        }
    }

    @AfterEach
    void aufraeumen() throws SQLException {
        Testdokumente.loeschen(datenbank.get(), MANDANT, ids);
    }

    @Test
    void mitMehrAlsFuenfzigPassendenAbschnittenKommenGenauFuenfzig() throws Exception {
        for (int i = 0; i < 70; i++) {
            dokument("NORMAL", "OFFERTE", 0.02 * (i + 1));
        }
        List<Abschnitttreffer> treffer = naechste(INHABER, null);
        assertThat(treffer).hasSize(50);
        assertThat(treffer).extracting(Abschnitttreffer::dokumentId).doesNotHaveDuplicates()
                .containsExactlyElementsOf(ids.subList(0, 50));
        // Gegenprobe ohne die Einstellungen des Adapters: HNSW mit Standard ef_search liefert höchstens 40
        assertThat(ohneEinstellungen()).isLessThanOrEqualTo(40);
    }

    @Test
    void naeherLiegendeUnsichtbareOderHerausgefilterteAbschnitteVerdraengenDieSichtbarenNicht() throws Exception {
        for (int i = 0; i < 60; i++) {
            dokument("INTERN", "OFFERTE", 0.05 + 0.001 * i);
            dokument("NORMAL", "VERTRAG", 0.05 + 0.001 * i);
        }
        List<UUID> sichtbar = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            sichtbar.add(dokument("NORMAL", "OFFERTE", 0.5 + 0.02 * i));
        }
        assertThat(naechste(MITARBEITER, "OFFERTE")).extracting(Abschnitttreffer::dokumentId)
                .containsExactlyElementsOf(sichtbar);
    }

    @Test
    void jeDokumentZaehltDerBesteAbschnitt() throws Exception {
        UUID mehrere = dokument("NORMAL", "OFFERTE", 0.3);
        abschnitt(mehrere, 2, 0.05, 2, 3);
        abschnitt(mehrere, 3, 0.2, null, null);
        UUID einer = dokument("NORMAL", "OFFERTE", 0.1);
        List<Abschnitttreffer> treffer = naechste(INHABER, null);
        assertThat(treffer).extracting(Abschnitttreffer::dokumentId).containsExactly(mehrere, einer);
        assertThat(treffer.getFirst().nr()).isEqualTo(2);
        assertThat(treffer.getFirst().seiteVon()).isEqualTo(2);
        assertThat(treffer.getFirst().seiteBis()).isEqualTo(3);
        assertThat(treffer.getFirst().text()).startsWith("Probe-Offerte – Offerte – Muster AG\n\n");
        assertThat(treffer.getFirst().inhalt()).isEqualTo("Abschnitt 2");
    }

    private List<Abschnitttreffer> naechste(Sichtregel regel, String art) throws Exception {
        Suchanfrage anfrage = new Suchanfrage("Frage", art, null, null, null, null, null, null, List.of(lauf), null,
                null, null, null, 0, Suchanfrage.GROESSE_STANDARD);
        return imMandanten(() -> {
            planerUeberHnsw();
            return port.naechste(FRAGE, anfrage, regel, 50);
        });
    }

    /** Dieselbe Abfrage über HNSW ohne {@code hnsw.ef_search} und {@code iterative_scan} des Adapters. */
    private int ohneEinstellungen() throws Exception {
        return imMandanten(() -> {
            planerUeberHnsw();
            String sql = "select count(*) from (select c.id from chunk c join dokument d on d.id = c.dokument_id "
                    + "where c.embedding is not null and d.tags @> cast(array[:lauf] as text[]) "
                    + "order by c.embedding operator(public.<=>) cast(:vektor as public.halfvec) limit 50) t";
            return ((Number) em.createNativeQuery(sql).setParameter("lauf", lauf)
                    .setParameter("vektor", Vektortext.text(FRAGE)).getSingleResult()).intValue();
        });
    }

    private void planerUeberHnsw() {
        for (String schalter : List.of("enable_seqscan", "enable_sort", "enable_bitmapscan")) {
            em.createNativeQuery("select set_config('" + schalter + "', 'off', true)").getSingleResult();
        }
    }

    private <T> T imMandanten(Callable<T> arbeit) throws Exception {
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            return worker.submit(() -> kontext.mit(new MandantCode(MANDANT),
                    () -> QuarkusTransaction.requiringNew().call(arbeit))).get(60, TimeUnit.SECONDS);
        } finally {
            worker.shutdown();
        }
    }

    /** Dokument mit einem Abschnitt im Abstand {@code t} (Winkel zur Frage wächst mit {@code t}). */
    private UUID dokument(String vertraulichkeit, String art, double t) throws SQLException {
        UUID id = UUID.randomUUID();
        try (Connection verbindung = datenbank.get().getConnection(); PreparedStatement einfuegen = verbindung
                .prepareStatement("insert into " + new MandantCode(MANDANT).schema() + ".dokument (id, art, titel, "
                        + "partner_name, quelle, tags, vertraulichkeit, erstellt_von, status, bestaetigt, "
                        + "bestaetigt_von, volltext) values (?, ?, 'Probe-Offerte', 'Muster AG', 'UPLOAD', ?, ?, "
                        + "'probe', 'ARCHIVIERT', now(), 'probe', 'Abschnitt 1')")) {
            einfuegen.setObject(1, id);
            einfuegen.setString(2, art);
            einfuegen.setArray(3, verbindung.createArrayOf("text", new Object[] {lauf}));
            einfuegen.setString(4, vertraulichkeit);
            einfuegen.executeUpdate();
        }
        ids.add(id);
        abschnitt(id, 1, t, 1, 1);
        return id;
    }

    private void abschnitt(UUID id, int nr, double t, Integer von, Integer bis) throws SQLException {
        try (Connection verbindung = datenbank.get().getConnection(); PreparedStatement einfuegen = verbindung
                .prepareStatement("insert into " + new MandantCode(MANDANT).schema() + ".chunk (dokument_id, nr, "
                        + "text, seite_von, seite_bis, embedding) values (?, ?, ?, ?, ?, ?::public.halfvec)")) {
            einfuegen.setObject(1, id);
            einfuegen.setInt(2, nr);
            einfuegen.setString(3, "Probe-Offerte – Offerte – Muster AG\n\nAbschnitt " + nr);
            einfuegen.setObject(4, von, java.sql.Types.INTEGER);
            einfuegen.setObject(5, bis, java.sql.Types.INTEGER);
            einfuegen.setString(6, Vektortext.text(vektor(t, zufall)));
            einfuegen.executeUpdate();
        }
    }

    /**
     * {@code e0 + t·n} mit einer zufälligen Richtung {@code n} senkrecht zu {@code e0} (Länge 1): Der Kosinus-Abstand zur
     * Frage {@code e0} wächst streng mit {@code t ≥ 0}. Die zufälligen Richtungen halten den HNSW-Graphen verbunden wie
     * bei echten Embeddings; Punkte auf einer Linie erreichte die Suche im Graphen nicht alle.
     */
    static float[] vektor(double t, Random zufall) {
        float[] v = new float[EmbeddingPort.DIMENSION];
        double laenge = 0;
        for (int i = 1; i < v.length; i++) {
            v[i] = (float) zufall.nextGaussian();
            laenge += v[i] * v[i];
        }
        double faktor = t / Math.sqrt(laenge);
        for (int i = 1; i < v.length; i++) {
            v[i] = (float) (v[i] * faktor);
        }
        v[0] = 1f;
        return v;
    }
}
