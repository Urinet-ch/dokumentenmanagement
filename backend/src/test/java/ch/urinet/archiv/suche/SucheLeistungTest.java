package ch.urinet.archiv.suche;

import ch.urinet.archiv.dokument.Testdokumente;
import ch.urinet.archiv.mandant.application.MandantKontext;
import ch.urinet.archiv.mandant.application.MandantProvisionierung;
import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.sicherheit.domain.Benutzer;
import ch.urinet.archiv.sicherheit.domain.Rolle;
import ch.urinet.archiv.suche.application.DokumentSuche;
import ch.urinet.archiv.suche.domain.Richtung;
import ch.urinet.archiv.suche.domain.Suchanfrage;
import ch.urinet.archiv.suche.domain.Suchergebnis;
import io.agroal.api.AgroalDataSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.LocalDate;
import java.time.OffsetDateTime;
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
 * Laufzeit der Volltextsuche mit 10'000 synthetischen Dokumenten (Aufgabe 4.5, Req 14.3): gemessen wird der
 * Anwendungsfall mit Sichtregel, Rang, Auszug und Zähler, ohne HTTP. Die Grenze ist locker (Stufe A und lokale Rechner
 * sind klein, tech.md: Leistungsziele erst auf Stufe B mit 50'000 Dokumenten, Aufgabe 5.11); der Messwert steht in
 * der Ausgabe und in design.md.
 */
@QuarkusTest
class SucheLeistungTest {

    private static final int DOKUMENTE = 10_000;
    private static final int SUCHEN = 100;
    private static final String MANDANT = "urinet";
    private static final String[] ARTEN = {"AUSGANGSRECHNUNG", "LIEFERANTENBELEG", "OFFERTE", "QUITTUNG", "VERTRAG",
            "KORRESPONDENZ", "LOHNABRECHNUNG", "VERSICHERUNG"};
    private static final String[] WOERTER = {"Rechnung", "Lieferung", "Trikots", "Clubhaus", "Beleuchtung", "Wartung",
            "Netzwerk", "Server", "Drucker", "Offerte", "Vertrag", "Versicherung", "Prämie", "Lohn", "Spesen", "Miete",
            "Reparatur", "Installation", "Schulung", "Beratung", "Material", "Kabel", "Lizenz", "Abonnement", "Porto",
            "Rabatt", "Skonto", "Mahnung", "Gutschrift", "Projekt", "Webseite", "Hosting", "Domain", "Telefon",
            "Fahrzeug", "Treibstoff", "Werkzeug", "Bürobedarf", "Verein", "Turnier"};
    private static final String[] PARTNER = {"Probe Sport AG", "Muster Bau GmbH", "Beispiel Technik", "Test Verein",
            "Feld Garage", "Stern Druck AG", "Wald Holz GmbH", "Linden Treuhand"};
    private static final String[] VERTRAULICHKEIT = {"NORMAL", "NORMAL", "NORMAL", "INTERN", "PERSONAL"};

    @Inject
    DokumentSuche suche;

    @Inject
    MandantKontext kontext;

    @Inject
    MandantProvisionierung provisionierung;

    @Inject
    Instance<AgroalDataSource> datenbank;

    private final Random zufall = new Random(45);
    private final List<UUID> ids = new ArrayList<>();
    private final String lauf = "lauf" + Long.toHexString(new Random().nextLong());

    @BeforeEach
    void dokumenteAnlegen() throws SQLException {
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
                    + "art, titel, datum, quelle, partner_id, partner_name, tags, vertraulichkeit, person_benutzer, "
                    + "erstellt_von, status, bestaetigt, bestaetigt_von, geschaeftsjahr, volltext) "
                    + "values (?, ?, ?, ?, 'UPLOAD', ?, ?, ?, ?, ?, 'probe', 'ARCHIVIERT', ?, 'probe', ?, ?)");
                 PreparedStatement finanz = verbindung.prepareStatement("insert into " + schema + ".dokument_finanz "
                         + "(dokument_id, belegnummer, richtung, betrag, waehrung) values (?, ?, ?, ?, 'CHF')")) {
                for (int i = 0; i < DOKUMENTE; i++) {
                    UUID id = UUID.randomUUID();
                    ids.add(id);
                    String art = ARTEN[i % ARTEN.length];
                    String partner = PARTNER[zufall.nextInt(PARTNER.length)];
                    LocalDate datum = LocalDate.of(2022, 1, 1).plusDays(zufall.nextInt(5 * 365));
                    String vertraulichkeit = VERTRAULICHKEIT[zufall.nextInt(VERTRAULICHKEIT.length)];
                    dokument.setObject(1, id);
                    dokument.setString(2, art);
                    dokument.setString(3, wort() + " " + wort() + " " + i);
                    dokument.setObject(4, datum, Types.DATE);
                    dokument.setObject(5, UUID.nameUUIDFromBytes(partner.getBytes()), Types.OTHER);
                    dokument.setString(6, partner);
                    dokument.setArray(7, verbindung.createArrayOf("text", new Object[] {lauf, wort().toLowerCase()}));
                    dokument.setString(8, vertraulichkeit);
                    dokument.setString(9, "PERSONAL".equals(vertraulichkeit) ? "joel" : null);
                    dokument.setObject(10, OffsetDateTime.now(), Types.TIMESTAMP_WITH_TIMEZONE);
                    dokument.setObject(11, datum.getYear(), Types.INTEGER);
                    dokument.setString(12, text());
                    dokument.addBatch();
                    if (i % 3 == 0) {
                        finanz.setObject(1, id);
                        finanz.setString(2, String.format("%d-%03d", datum.getYear(), i % 1000));
                        finanz.setString(3, i % 2 == 0 ? "EINNAHME" : "AUSGABE");
                        finanz.setBigDecimal(4, BigDecimal.valueOf(zufall.nextInt(500_000), 2));
                        finanz.addBatch();
                    }
                    if (i % 1000 == 999) {
                        dokument.executeBatch();
                        finanz.executeBatch();
                    }
                }
                dokument.executeBatch();
                finanz.executeBatch();
                verbindung.commit();
            } catch (SQLException e) {
                verbindung.rollback();
                throw e;
            } finally {
                verbindung.setAutoCommit(true);
            }
            try (Statement analysieren = verbindung.createStatement()) {
                analysieren.execute("analyze " + schema + ".dokument");
                analysieren.execute("analyze " + schema + ".dokument_finanz");
            }
        }
    }

    @AfterEach
    void aufraeumen() throws SQLException {
        Testdokumente.loeschen(datenbank.get(), MANDANT, ids);
    }

    @Test
    void sucheMitZehntausendDokumenten() throws Exception {
        List<Benutzer> benutzer = List.of(new Benutzer("fabian", Rolle.INHABER), new Benutzer("joel", Rolle.MITARBEITER),
                new Benutzer("treuhand", Rolle.TREUHAENDER));
        List<Suchanfrage> anfragen = new ArrayList<>();
        for (int i = 0; i < SUCHEN; i++) {
            String begriff = wort();
            anfragen.add(switch (i % 5) {
                case 0 -> anfrage(begriff, null, null, null, null);
                case 1 -> anfrage(begriff + " " + wort(), null, null, null, null);
                case 2 -> anfrage(begriff, ARTEN[i % ARTEN.length], LocalDate.of(2024, 1, 1), Richtung.EINNAHME, null);
                case 3 -> anfrage("\"" + begriff + " " + wort() + "\" -" + wort(), null, null, null, null);
                default -> anfrage(null, ARTEN[i % ARTEN.length], LocalDate.of(2023, 1, 1), null,
                        String.format("%d-%03d", 2023 + i % 3, i * 3 % 1000));
            });
        }
        ExecutorService worker = Executors.newSingleThreadExecutor();
        long[] dauer = new long[SUCHEN];
        long treffer = 0;
        try {
            // Aufwärmen (JIT, Verbindungen, Cache), nicht gemessen
            for (int i = 0; i < 10; i++) {
                suchen(worker, benutzer.get(i % benutzer.size()), anfragen.get(i));
            }
            for (int i = 0; i < SUCHEN; i++) {
                long start = System.nanoTime();
                treffer += suchen(worker, benutzer.get(i % benutzer.size()), anfragen.get(i)).gesamt();
                dauer[i] = System.nanoTime() - start;
            }
        } finally {
            worker.shutdown();
        }
        Arrays.sort(dauer);
        long median = dauer[SUCHEN / 2] / 1_000_000;
        long p95 = dauer[SUCHEN * 95 / 100] / 1_000_000;
        long max = dauer[SUCHEN - 1] / 1_000_000;
        System.out.printf("Volltextsuche mit %d Dokumenten: Median %d ms, p95 %d ms, Max %d ms (%d Suchen)%n",
                DOKUMENTE, median, p95, max, SUCHEN);
        assertThat(treffer).as("die Suchen finden Dokumente").isPositive();
        assertThat(p95).as("p95 in ms").isLessThan(1_000);
    }

    private static Suchanfrage anfrage(String begriff, String art, LocalDate datumVon, Richtung richtung,
                                       String belegnummer) {
        return new Suchanfrage(begriff, art, null, datumVon, null, null, null, richtung, null, null, null, belegnummer,
                null, 0, Suchanfrage.GROESSE_STANDARD);
    }

    private Suchergebnis suchen(ExecutorService worker, Benutzer benutzer, Suchanfrage anfrage) throws Exception {
        return worker.submit(() -> kontext.mit(new MandantCode(MANDANT), () -> suche.suchen(benutzer, anfrage)))
                .get(30, TimeUnit.SECONDS);
    }

    private String wort() {
        return WOERTER[zufall.nextInt(WOERTER.length)];
    }

    /** Rund 1'500 Zeichen synthetischer Text, damit Rang und Auszug realistisch viel zu tun haben. */
    private String text() {
        StringBuilder text = new StringBuilder();
        while (text.length() < 1_500) {
            text.append(wort()).append(zufall.nextInt(4) == 0 ? ". " : " ");
        }
        return text.toString();
    }
}
