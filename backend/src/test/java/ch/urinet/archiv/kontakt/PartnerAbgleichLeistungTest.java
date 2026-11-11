package ch.urinet.archiv.kontakt;

import ch.urinet.archiv.gemeinsam.domain.Normalisierer;
import ch.urinet.archiv.kontakt.application.PartnerAbgleich;
import ch.urinet.archiv.kontakt.domain.AbgleichErgebnis;
import ch.urinet.archiv.kontakt.domain.PartnerAngaben;
import ch.urinet.archiv.mandant.application.MandantKontext;
import ch.urinet.archiv.mandant.application.MandantProvisionierung;
import ch.urinet.archiv.mandant.domain.MandantCode;
import io.agroal.api.AgroalDataSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Laufzeit des Partner-Abgleichs mit 10'000 synthetischen Kontakten (Auftrag 3.1): Die Namensähnlichkeit rechnet die
 * Datenbank über den GIN-Index {@code kontakt_name_trgm_ix}. Die Grenze ist locker (Stufe A und CI-Runner sind klein,
 * tech.md: Leistungsziele erst auf Stufe B); der Messwert steht in der Ausgabe und in design.md.
 */
@QuarkusTest
class PartnerAbgleichLeistungTest {

    private static final int KONTAKTE = 10_000;
    private static final int ABGLEICHE = 100;
    private static final String MANDANT = "urinet";
    private static final String[] TEIL_A = {"Alpen", "Berg", "See", "Tal", "Wald", "Fluss", "Stern", "Sonnen", "Mond",
            "Brunnen", "Linden", "Eichen", "Rosen", "Falken", "Adler", "Hirsch", "Stein", "Gold", "Silber", "Feld"};
    private static final String[] TEIL_B = {"bau", "garage", "druck", "technik", "service", "handel", "werk", "haus",
            "sport", "reisen", "holz", "metall", "elektro", "optik", "bäckerei", "küchen", "transport", "treuhand",
            "malerei", "gärtnerei"};
    private static final String[] ORTE = {"Probedorf", "Musterhausen", "Beispielstadt", "Testwil", "Nirgendwo",
            "Seehalde", "Bergli", "Talacker", "Waldegg", "Feldmatt"};
    private static final String[] FORMEN = {"AG", "GmbH", "", "Genossenschaft", "Verein"};

    @Inject
    PartnerAbgleich abgleich;

    @Inject
    MandantKontext kontext;

    @Inject
    MandantProvisionierung provisionierung;

    @Inject
    Instance<AgroalDataSource> datenbank;

    private final Random zufall = new Random(31);
    private final List<String> namen = new ArrayList<>();

    @BeforeEach
    void kontakteAnlegen() throws SQLException {
        try (Connection verbindung = datenbank.get().getConnection()) {
            try (PreparedStatement abfrage = verbindung.prepareStatement("select 1 from public.mandant where code = ?")) {
                abfrage.setString(1, MANDANT);
                try (ResultSet zeile = abfrage.executeQuery()) {
                    if (!zeile.next()) {
                        provisionierung.anlegen(MANDANT, null, null);
                    }
                }
            }
            leeren(verbindung);
            try (PreparedStatement einfuegen = verbindung.prepareStatement("insert into " + tabelle()
                    + " (id, typ, name, name_normalisiert, email, iban, quelle) values (?, 'ORGANISATION', ?, ?, ?, ?, 'TEST')")) {
                for (int i = 0; i < KONTAKTE; i++) {
                    String name = name(i);
                    namen.add(name);
                    einfuegen.setObject(1, UUID.randomUUID());
                    einfuegen.setString(2, name);
                    einfuegen.setString(3, Normalisierer.name(name));
                    einfuegen.setString(4, "kontakt" + i + "@beispiel.test");
                    einfuegen.setString(5, String.format("CH00TEST%013d", i));
                    einfuegen.addBatch();
                    if (i % 1000 == 999) {
                        einfuegen.executeBatch();
                    }
                }
                einfuegen.executeBatch();
            }
            try (PreparedStatement analysieren = verbindung.prepareStatement("analyze " + tabelle())) {
                analysieren.execute();
            }
        }
    }

    @AfterEach
    void aufraeumen() throws SQLException {
        try (Connection verbindung = datenbank.get().getConnection()) {
            leeren(verbindung);
        }
    }

    @Test
    void abgleichMitZehntausendKontakten() throws Exception {
        List<PartnerAngaben> anfragen = new ArrayList<>();
        for (int i = 0; i < ABGLEICHE; i++) {
            String name = namen.get(zufall.nextInt(KONTAKTE));
            anfragen.add(switch (i % 4) {
                case 0 -> new PartnerAngaben(name, null, null, null);
                case 1 -> new PartnerAngaben(name.substring(0, name.length() - 2), null, null, null);
                case 2 -> new PartnerAngaben("Unbekannt Probe " + i, null, null, null);
                default -> new PartnerAngaben(name, "unbekannt" + i + "@beispiel.test", "CHE-000.000.000", null);
            });
        }
        ExecutorService worker = Executors.newSingleThreadExecutor();
        long[] dauer = new long[ABGLEICHE];
        try {
            // Aufwärmen (JIT, Verbindungen), nicht gemessen
            for (int i = 0; i < 10; i++) {
                abgleichen(worker, anfragen.get(i));
            }
            for (int i = 0; i < ABGLEICHE; i++) {
                long start = System.nanoTime();
                abgleichen(worker, anfragen.get(i));
                dauer[i] = System.nanoTime() - start;
            }
        } finally {
            worker.shutdown();
        }
        Arrays.sort(dauer);
        long median = dauer[ABGLEICHE / 2] / 1_000_000;
        long p95 = dauer[ABGLEICHE * 95 / 100] / 1_000_000;
        long max = dauer[ABGLEICHE - 1] / 1_000_000;
        System.out.printf("Partner-Abgleich mit %d Kontakten: Median %d ms, p95 %d ms, Max %d ms (%d Abgleiche)%n",
                KONTAKTE, median, p95, max, ABGLEICHE);
        assertThat(p95).as("p95 in ms").isLessThan(1_000);
        System.out.println("Plan der Ähnlichkeitssuche:\n" + plan(false));
        // bei 10'000 Zeilen wählt der Planer oft den Seq Scan; der Index muss aber für «%» nutzbar sein
        assertThat(plan(true)).contains("kontakt_name_trgm_ix");
    }

    private AbgleichErgebnis abgleichen(ExecutorService worker, PartnerAngaben angaben) throws Exception {
        return worker.submit(() -> kontext.mit(new MandantCode(MANDANT), () -> abgleich.abgleichen(angaben)))
                .get(30, TimeUnit.SECONDS);
    }

    private String plan(boolean ohneSeqScan) throws SQLException {
        try (Connection verbindung = datenbank.get().getConnection()) {
            verbindung.setAutoCommit(false);
            try (PreparedStatement schwelle = verbindung.prepareStatement(
                    "select set_config('pg_trgm.similarity_threshold', '0.5', true)");
                 PreparedStatement erklaeren = verbindung.prepareStatement("explain select id from " + tabelle()
                         + " where name_normalisiert operator(public.%) 'alpenbau probedorf' "
                         + "order by public.similarity(name_normalisiert, 'alpenbau probedorf') desc limit 2")) {
                schwelle.execute();
                if (ohneSeqScan) {
                    try (PreparedStatement aus = verbindung.prepareStatement("set local enable_seqscan = off")) {
                        aus.execute();
                    }
                }
                StringBuilder text = new StringBuilder();
                try (ResultSet zeile = erklaeren.executeQuery()) {
                    while (zeile.next()) {
                        text.append(zeile.getString(1)).append('\n');
                    }
                }
                return text.toString();
            } finally {
                verbindung.rollback();
                verbindung.setAutoCommit(true);
            }
        }
    }

    private String name(int i) {
        String form = FORMEN[zufall.nextInt(FORMEN.length)];
        return TEIL_A[zufall.nextInt(TEIL_A.length)] + TEIL_B[zufall.nextInt(TEIL_B.length)] + " "
                + ORTE[zufall.nextInt(ORTE.length)] + " " + i + (form.isEmpty() ? "" : " " + form);
    }

    private static void leeren(Connection verbindung) throws SQLException {
        try (PreparedStatement loeschen = verbindung.prepareStatement("delete from " + tabelle())) {
            loeschen.execute();
        }
    }

    private static String tabelle() {
        return new MandantCode(MANDANT).schema() + ".kontakt";
    }
}
