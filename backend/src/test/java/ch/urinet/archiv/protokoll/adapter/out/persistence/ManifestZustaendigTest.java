package ch.urinet.archiv.protokoll.adapter.out.persistence;

import ch.urinet.archiv.dokument.adapter.out.persistence.PanacheDokumentPrueflauf;
import ch.urinet.archiv.dokument.application.DokumentPrueflaufPort.Datei;
import ch.urinet.archiv.dokument.application.DokumentPrueflaufPort.Pruefdokument;
import ch.urinet.archiv.gemeinsam.LogMitschnitt;
import ch.urinet.archiv.mandant.application.MandantKontext;
import ch.urinet.archiv.mandant.application.MandantProvisionierung;
import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.protokoll.application.ManifestDaten;
import ch.urinet.archiv.protokoll.application.ManifestSpeichern;
import ch.urinet.archiv.protokoll.application.ManifestDaten.Zustaendig;
import ch.urinet.archiv.protokoll.domain.ManifestEintrag;
import ch.urinet.archiv.protokoll.domain.ManifestFenster;
import ch.urinet.archiv.protokoll.domain.ProtokollHash;
import io.agroal.api.AgroalDataSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.transaction.UserTransaction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Prüflauf und Tages-Manifest mit Dev Service (Req 15.5; Aufgabe 5.3): Das zuständige Manifest ist das, in dessen Fenster
 * {@code [von, bis)} {@code dokument.archiviert} liegt ({@code von} inklusive, {@code bis} exklusiv, erstes Manifest
 * ohne untere Grenze); Variante und Nr. aus {@link PanacheDokumentPrueflauf} entsprechen den Einträgen aus
 * {@link JdbcManifestDaten#eintraege}. Alles in einer Transaktion, die am Ende zurückgerollt wird, weil
 * {@code manifest} nur Einfügen erlaubt und andere Tests die Kette des Mandanten lesen.
 */
@QuarkusTest
class ManifestZustaendigTest {

    private static final MandantCode MANDANT = new MandantCode("urinet");
    private static final Instant T1 = Instant.parse("2001-01-01T23:00:00Z");
    private static final Instant T2 = Instant.parse("2001-01-02T23:00:00Z");
    private static final Instant T3 = Instant.parse("2001-01-03T23:00:00Z");

    @Inject
    JdbcManifestDaten daten;

    @Inject
    ManifestSpeichern speichern;

    @Inject
    PanacheDokumentPrueflauf prueflauf;

    @Inject
    MandantKontext kontext;

    @Inject
    MandantProvisionierung provisionierung;

    @Inject
    UserTransaction transaktion;

    @Inject
    Instance<AgroalDataSource> datenbank;

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Random zufall = new Random();

    @BeforeEach
    void mandant() throws SQLException {
        try (Connection v = datenbank.get().getConnection();
             PreparedStatement p = v.prepareStatement("select 1 from public.mandant where code = ?")) {
            p.setString(1, MANDANT.wert());
            try (ResultSet r = p.executeQuery()) {
                if (!r.next()) {
                    provisionierung.anlegen(MANDANT.wert(), null, null);
                }
            }
        }
    }

    @AfterEach
    void beenden() {
        worker.shutdown();
    }

    @Test
    void vonInklusiveBisExklusiveErstesOhneUntereGrenze() {
        List<Optional<Zustaendig>> ergebnis = zurueckgerollt(() -> {
            manifest(LocalDate.parse("2001-01-01"), null, T1);
            manifest(LocalDate.parse("2001-01-02"), T1, T2);
            manifest(LocalDate.parse("2001-01-03"), T2, T3);
            return List.of(daten.zustaendig(Instant.parse("1999-06-01T00:00:00Z")), daten.zustaendig(T1.minusMillis(1)),
                    daten.zustaendig(T1), daten.zustaendig(T2.minusMillis(1)), daten.zustaendig(T2),
                    daten.zustaendig(T3.minusMillis(1)));
        });

        assertThat(ergebnis).extracting(z -> z.map(Zustaendig::datum).map(LocalDate::toString).orElse("-"))
                .containsExactly("2001-01-01", "2001-01-01", "2001-01-02", "2001-01-02", "2001-01-03", "2001-01-03");
        Zustaendig erstes = ergebnis.getFirst().orElseThrow();
        assertThat(erstes.von()).isEmpty();
        assertThat(erstes.bis()).isEqualTo(T1);
        assertThat(ergebnis.get(2).orElseThrow().von()).contains(T1);
        assertThat(zeilen("select count(*) from " + MANDANT.schema() + ".manifest where datum < '2002-01-01'"))
                .as("zurückgerollt").isZero();
    }

    /**
     * Nach {@code bis} des jüngsten Manifests ist keines zuständig. Geprüft in einem Zeitpunkt nach allen Manifesten,
     * die andere Tests im Mandanten anlegen, und ohne Manifest im ersten Fenster.
     */
    @Test
    void nachDemJuengstenManifestKeinesZustaendig() {
        Optional<Zustaendig> spaeter = zurueckgerollt(() -> daten.zustaendig(Instant.parse("2999-01-01T00:00:00Z")));
        assertThat(spaeter).isEmpty();
    }

    @Test
    void manifestEnthaeltWissensversionenUndLog() {
        ManifestSpeichern.Sammlung sammlung = zurueckgerollt(() -> speichern.sammeln(MANDANT,
                ManifestFenster.fuer(LocalDate.parse("2001-01-02"), null)));
        assertThat(sammlung.blaetter()).containsKeys(ProtokollHash.WISSENSSEITE_VERSION, ProtokollHash.WISSENS_LOG);
    }

    @Test
    void wissensseitenUndLogWerdenGehashtOhneInhalteZuLoggen() {
        String inhalt = "synthetischer-wissensinhalt-nicht-loggen";
        record Blaetter(List<ManifestDaten.Blatt> versionen, List<ManifestDaten.Blatt> log, String protokoll) {
        }
        Blaetter blaetter = zurueckgerollt(() -> {
            UUID seite = UUID.randomUUID();
            Instant zeit = T1.plusSeconds(10);
            ausfuehren("insert into " + MANDANT.schema()
                    + ".wissensseite (id, pfad, typ, titel, beschreibung, vertraulichkeit, inhalt, version, erzeugt_von, erzeugt_am, "
                    + "freigegeben_von, freigegeben_am) values (?, ?, 'Konzept', ?, 'synthetisch', 'NORMAL', ?, 1, "
                    + "'human:probe', ?, 'human:probe', ?)", p -> {
                p.setObject(1, seite);
                p.setString(2, "probe/" + seite);
                p.setString(3, "Synthetische Probe");
                p.setString(4, inhalt);
                p.setObject(5, zeit.atOffset(ZoneOffset.UTC), Types.TIMESTAMP_WITH_TIMEZONE);
                p.setObject(6, zeit.atOffset(ZoneOffset.UTC), Types.TIMESTAMP_WITH_TIMEZONE);
            });
            ausfuehren("insert into " + MANDANT.schema()
                    + ".wissensseite_version (seite_id, version, daten, inhalt, erzeugt_von, freigegeben_von, zeit) "
                    + "values (?, 1, '{}', ?, 'human:probe', 'human:probe', ?)", p -> {
                p.setObject(1, seite);
                p.setString(2, inhalt);
                p.setObject(3, zeit.atOffset(ZoneOffset.UTC), Types.TIMESTAMP_WITH_TIMEZONE);
            });
            ausfuehren("insert into " + MANDANT.schema()
                    + ".wissens_log (zeit, aktion, akteur, seiten, angaben) "
                    + "values (?, 'CREATION', 'human:probe', array[?::uuid], ?::jsonb)", p -> {
                p.setObject(1, zeit.atOffset(ZoneOffset.UTC), Types.TIMESTAMP_WITH_TIMEZONE);
                p.setObject(2, seite);
                p.setString(3, "{\"inhalt\":\"" + inhalt + "\"}");
            });
            try (LogMitschnitt protokoll = LogMitschnitt.starten()) {
                return new Blaetter(daten.blaetter(ProtokollHash.WISSENSSEITE_VERSION, T1, T2),
                        daten.blaetter(ProtokollHash.WISSENS_LOG, T1, T2), protokoll.volltext());
            }
        });

        assertThat(blaetter.versionen()).hasSize(1).allSatisfy(b -> assertThat(b.sha256()).hasSize(64));
        assertThat(blaetter.log()).hasSize(1).allSatisfy(b -> assertThat(b.sha256()).hasSize(64));
        assertThat(blaetter.protokoll()).doesNotContain(inhalt);
    }

    @Test
    void sicherungenNutzenHalboffenesFensterGlobaleUndMandanteneigeneZeilenSortiertOhneDuplikate() {
        String fremd = "sicherung-" + UUID.randomUUID().toString().substring(0, 8);
        List<List<String>> ergebnis = zurueckgerollt(() -> {
            mandantEintragen(fremd);
            sicherung("1".repeat(64), null, T1);
            sicherung("2".repeat(64), MANDANT.wert(), T1.plusSeconds(1));
            sicherung("1".repeat(64), MANDANT.wert(), T1.plusSeconds(2));
            sicherung("3".repeat(64), fremd, T1.plusSeconds(3));
            sicherung("4".repeat(64), null, T2);
            sicherung("5".repeat(64), null, T1.minusSeconds(1));
            return List.of(daten.sicherungen(MANDANT, T1, T2), daten.sicherungen(MANDANT, null, T2));
        });

        assertThat(ergebnis.getFirst()).containsExactly("1".repeat(64), "2".repeat(64));
        assertThat(ergebnis.get(1)).containsExactly("1".repeat(64), "2".repeat(64), "5".repeat(64));
    }

    /** Variante und Nr. wie im Manifest: Original, Arbeitsfassung und PDF/A; verworfene Datei nicht im Manifest. */
    @Test
    void varianteUndNrWieImManifest() {
        UUID id = UUID.fromString("ffffffff-ffff-4fff-bfff-" + HexFormat.of().toHexDigits(zufall.nextLong()).substring(4));
        Instant archiviert = Instant.parse("2001-01-02T10:00:00Z");
        record Paar(Optional<Pruefdokument> dokument, List<ManifestEintrag> eintraege) {
        }
        Paar paar = zurueckgerollt(() -> {
            dokument(id, archiviert);
            datei(id, 1, "ORIGINAL", "AKTIV");
            datei(id, 2, "ORIGINAL", "VERWORFEN");
            datei(id, 1, "ARBEITSFASSUNG", "AKTIV");
            return new Paar(prueflauf.archivierte(Optional.of(new UUID(id.getMostSignificantBits(), 0)), 1000).stream()
                    .filter(d -> d.id().equals(id)).findFirst(), daten.eintraege(T1, T2).stream()
                    .filter(e -> e.dokument().equals(id)).toList());
        });

        Pruefdokument dokument = paar.dokument().orElseThrow();
        assertThat(dokument.archiviert()).contains(archiviert);
        assertThat(dokument.dateien().stream().filter(Datei::imManifest).map(d -> d.variante() + "/" + d.nr()))
                .containsExactlyInAnyOrderElementsOf(paar.eintraege().stream().map(e -> e.variante() + "/" + e.nr())
                        .toList())
                .containsExactlyInAnyOrder("original/1", "arbeitsfassung/1", "pdfa/1");
        assertThat(dokument.dateien().stream().filter(d -> !d.imManifest()).map(d -> d.variante() + "/" + d.nr()))
                .containsExactly("original/2");
        assertThat(zeilen("select count(*) from " + MANDANT.schema() + ".dokument where id = '" + id + "'"))
                .as("zurückgerollt").isZero();
        // dieselbe SHA-256 je Schlüssel
        for (ManifestEintrag e : paar.eintraege()) {
            assertThat(dokument.dateien()).filteredOn(d -> d.variante().equals(e.variante()) && d.nr() == e.nr())
                    .singleElement().satisfies(d -> assertThat(d.sha256()).contains(e.sha256()));
        }
    }

    private void manifest(LocalDate datum, Instant von, Instant bis) {
        ausfuehren("insert into " + MANDANT.schema() + ".manifest (datum, von, bis, sha256, vorher_sha256, anzahl, "
                + "protokolle, s3_schluessel, s3_version, sig_sha256, sig_s3_version) values (?, ?, ?, ?, ?, 0, "
                + "'{}'::jsonb, ?, 'v1', ?, 's1')", p -> {
            p.setObject(1, datum);
            p.setObject(2, von == null ? null : von.atOffset(ZoneOffset.UTC), Types.TIMESTAMP_WITH_TIMEZONE);
            p.setObject(3, bis.atOffset(ZoneOffset.UTC), Types.TIMESTAMP_WITH_TIMEZONE);
            p.setString(4, sha());
            p.setString(5, von == null ? null : sha());
            p.setString(6, "manifest/" + datum + ".json");
            p.setString(7, sha());
        });
    }

    private void dokument(UUID id, Instant archiviert) {
        ausfuehren("insert into " + MANDANT.schema() + ".dokument (id, quelle, erstellt_von, status, archiviert, "
                + "s3_pdfa, s3_version_pdfa, sha256_pdfa) values (?, 'UPLOAD', 'probe', 'ARCHIVIERT', ?, ?, 'p1', ?)",
                p -> {
                    p.setObject(1, id);
                    p.setObject(2, archiviert.atOffset(ZoneOffset.UTC), Types.TIMESTAMP_WITH_TIMEZONE);
                    p.setString(3, "archiv/2001/" + id + "/pdfa.pdf");
                    p.setString(4, sha());
                });
    }

    private void datei(UUID id, int nr, String rolle, String status) {
        ausfuehren("insert into " + MANDANT.schema() + ".dokument_datei (dokument_id, nr, rolle, sha256, "
                + "s3_schluessel, s3_version, status) values (?, ?, ?, ?, ?, 'v1', ?)", p -> {
            p.setObject(1, id);
            p.setInt(2, nr);
            p.setString(3, rolle);
            p.setString(4, sha());
            p.setString(5, "archiv/2001/" + id + "/" + nr + "-" + rolle.toLowerCase(java.util.Locale.ROOT) + ".pdf");
            p.setString(6, status);
        });
    }

    private void sicherung(String sha256, String mandant, Instant erstellt) {
        ausfuehren("insert into public.sicherung_export (art, mandant, sha256, erstellt) values (?, ?, ?, ?)", p -> {
            p.setString(1, mandant == null ? "db" : "bucket");
            if (mandant == null) {
                p.setNull(2, java.sql.Types.VARCHAR);
            } else {
                p.setString(2, mandant);
            }
            p.setString(3, sha256);
            p.setObject(4, erstellt.atOffset(ZoneOffset.UTC), java.sql.Types.TIMESTAMP_WITH_TIMEZONE);
        });
    }

    private void mandantEintragen(String code) {
        ausfuehren("insert into public.mandant (code, name, schema_name, bucket) values (?, ?, ?, ?)", p -> {
            p.setString(1, code);
            p.setString(2, code);
            p.setString(3, "m_" + code.replace('-', '_'));
            p.setString(4, "urinet-archiv-test-" + code);
        });
    }

    private long zeilen(String sql) {
        try (Connection v = datenbank.get().getConnection(); PreparedStatement p = v.prepareStatement(sql);
             ResultSet r = p.executeQuery()) {
            r.next();
            return r.getLong(1);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private String sha() {
        byte[] bytes = new byte[32];
        zufall.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    private interface Parameter {
        void setzen(PreparedStatement p) throws SQLException;
    }

    /** In der laufenden Transaktion (dieselbe Verbindung über Agroal), also mit ihr zurückgerollt. */
    private void ausfuehren(String sql, Parameter parameter) {
        try (Connection v = datenbank.get().getConnection(); PreparedStatement p = v.prepareStatement(sql)) {
            parameter.setzen(p);
            p.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private <T> T zurueckgerollt(Supplier<T> arbeit) {
        try {
            return CompletableFuture.supplyAsync(() -> kontext.mit(MANDANT, () -> {
                try {
                    transaktion.begin();
                    try {
                        return arbeit.get();
                    } finally {
                        transaktion.rollback();
                    }
                } catch (RuntimeException e) {
                    throw e;
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }), worker).get(1, TimeUnit.MINUTES);
        } catch (ExecutionException e) {
            throw e.getCause() instanceof RuntimeException laufzeit ? laufzeit : new IllegalStateException(e.getCause());
        } catch (InterruptedException | TimeoutException e) {
            throw new IllegalStateException(e);
        }
    }
}
