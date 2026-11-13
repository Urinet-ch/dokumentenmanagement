package ch.urinet.archiv.export.application;

import ch.urinet.archiv.ablage.domain.Kopie;
import ch.urinet.archiv.ablage.domain.Pruefsumme;
import ch.urinet.archiv.export.domain.Geschaeftsjahr;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.MonthDay;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** {@link ExportSchreiber} ohne Quarkus: Inhalt, Seiten, Manifest-Fenster und Abbruch bei falscher Prüfsumme. */
class ExportSchreiberTest {

    private static final Geschaeftsjahr JAHR = new Geschaeftsjahr(2026, MonthDay.of(12, 31));

    private final Map<String, byte[]> objekte = new LinkedHashMap<>();
    private final List<LocalDate[]> manifestAbfragen = new ArrayList<>();

    private ExportBelege.Datei datei(String schluessel, String text) {
        byte[] inhalt = text.getBytes(StandardCharsets.UTF_8);
        objekte.put(schluessel, inhalt);
        return new ExportBelege.Datei(schluessel, "v1", Pruefsumme.von(inhalt), "pdf");
    }

    private ExportSchreiber.Quelle quelle() {
        return (schluessel, version, ziel) -> {
            byte[] inhalt = objekte.get(schluessel);
            try {
                ziel.write(inhalt);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            return new Kopie(Pruefsumme.von(inhalt), inhalt.length);
        };
    }

    private ExportBelege belege(List<ExportBelege.Beleg> alle, List<ExportBelege.Manifestdatei> manifeste) {
        return new ExportBelege() {
            @Override
            public List<Beleg> seite(int geschaeftsjahr, Optional<UUID> nach, int anzahl) {
                assertThat(geschaeftsjahr).isEqualTo(2026);
                int ab = nach.map(id -> alle.indexOf(alle.stream().filter(b -> b.id().equals(id)).findFirst()
                        .orElseThrow()) + 1).orElse(0);
                return alle.subList(Math.min(ab, alle.size()), Math.min(ab + anzahl, alle.size()));
            }

            @Override
            public List<Manifestdatei> manifeste(LocalDate von, LocalDate bis) {
                manifestAbfragen.add(new LocalDate[]{von, bis});
                return manifeste;
            }
        };
    }

    private static Map<String, byte[]> entpacken(byte[] zip) throws IOException {
        Map<String, byte[]> inhalt = new LinkedHashMap<>();
        try (ZipInputStream ein = new ZipInputStream(new ByteArrayInputStream(zip), StandardCharsets.UTF_8)) {
            for (ZipEntry e = ein.getNextEntry(); e != null; e = ein.getNextEntry()) {
                inhalt.put(e.getName(), ein.readAllBytes());
            }
        }
        return inhalt;
    }

    @Test
    void schreibtBelegeSeitenweiseUndManifesteBisZurLetztenArchivierung() throws Exception {
        List<ExportBelege.Beleg> alle = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            UUID id = new UUID(0, i + 1);
            alle.add(new ExportBelege.Beleg(id, "QUITTUNG", LocalDate.of(2026, 1, i + 1),
                    Instant.parse("2026-01-01T08:00:00Z"),
                    i == 4 ? Instant.parse("2027-02-03T10:00:00Z") : Instant.parse("2026-02-01T10:00:00Z"),
                    "Partner", "Q" + i, "AUSGABE", new BigDecimal("1.00"), "CHF",
                    List.of(datei("archiv/2026/" + id + "/pdfa.pdf", "PDF " + i))));
        }
        byte[] json = "manifest".getBytes(StandardCharsets.UTF_8);
        objekte.put("manifest/2026-02-01.json", json);
        var manifest = new ExportBelege.Manifestdatei(LocalDate.of(2026, 2, 1), "json", "manifest/2026-02-01.json", "v",
                Pruefsumme.von(json));
        ByteArrayOutputStream aus = new ByteArrayOutputStream();

        ExportSchreiber.Ergebnis ergebnis = new ExportSchreiber(belege(alle, List.of(manifest)), quelle(), 2)
                .schreiben(JAHR, aus);

        // 4 Belege am 2026-02-01 mit Manifest, einer am 2027-02-03 ohne
        assertThat(ergebnis).isEqualTo(new ExportSchreiber.Ergebnis(5, 5, 1, 1));
        Map<String, byte[]> zip = entpacken(aus.toByteArray());
        assertThat(zip.keySet()).hasSize(8).contains("belege/2026-01-01_QUITTUNG_Q0_Partner.pdf",
                "manifeste/2026-02-01.json", "belegliste.csv", "pruefsummen.sha256");
        assertThat(new String(zip.get("belege/2026-01-05_QUITTUNG_Q4_Partner.pdf"), StandardCharsets.UTF_8))
                .isEqualTo("PDF 4");
        assertThat(manifestAbfragen).singleElement().satisfies(f -> {
            assertThat(f[0]).isEqualTo(LocalDate.of(2026, 1, 1));
            // spät archivierter Beleg: Manifeste bis zu seinem Archivierungstag
            assertThat(f[1]).isEqualTo(LocalDate.of(2027, 2, 3));
        });
        String liste = new String(zip.get("belegliste.csv"), StandardCharsets.UTF_8);
        assertThat(liste.lines().count()).isEqualTo(6);
        String summen = new String(zip.get("pruefsummen.sha256"), StandardCharsets.US_ASCII);
        assertThat(summen.lines().toList()).hasSize(7)
                .contains(Pruefsumme.von(zip.get("belegliste.csv")) + "  belegliste.csv");
    }

    /** Vor Beginn des Geschäftsjahres archiviert (GJ aus dem Belegdatum): Manifeste ab dem Archivierungstag. */
    @Test
    void manifesteAbDerFruehestenArchivierung() throws Exception {
        UUID id = new UUID(0, 11);
        var beleg = new ExportBelege.Beleg(id, "AUSGANGSRECHNUNG", LocalDate.of(2026, 1, 5),
                Instant.parse("2025-12-20T08:00:00Z"), Instant.parse("2025-12-20T09:00:00Z"), null, null, null, null,
                null, List.of(datei("archiv/2025/" + id + "/pdfa.pdf", "frueh")));

        ExportSchreiber.Ergebnis ergebnis = new ExportSchreiber(belege(List.of(beleg), List.of()), quelle(), 10)
                .schreiben(JAHR, OutputStream.nullOutputStream());

        assertThat(manifestAbfragen).singleElement().satisfies(f -> {
            assertThat(f[0]).isEqualTo(LocalDate.of(2025, 12, 20));
            assertThat(f[1]).isEqualTo(LocalDate.of(2026, 12, 31));
        });
        assertThat(ergebnis.ohneManifest()).isEqualTo(1);
    }

    @Test
    void falschePruefsummeBrichtAb() {
        UUID id = new UUID(0, 9);
        ExportBelege.Datei falsch = new ExportBelege.Datei("archiv/2026/" + id + "/pdfa.pdf", "v1", "0".repeat(64),
                "pdf");
        objekte.put(falsch.schluessel(), "anders".getBytes(StandardCharsets.UTF_8));
        var beleg = new ExportBelege.Beleg(id, "QUITTUNG", null, Instant.parse("2026-05-01T08:00:00Z"), null, null,
                null, null, null, null, List.of(falsch));
        assertThatThrownBy(() -> new ExportSchreiber(belege(List.of(beleg), List.of()), quelle(), 10)
                .schreiben(JAHR, OutputStream.nullOutputStream()))
                .isInstanceOf(ExportSchreiber.PruefsummeWeichtAb.class).hasMessageContaining(id.toString())
                .hasMessageNotContaining("anders");
    }

    @Test
    void ohneBelegdatumDasEingangsdatumUndMehrereOriginale() throws Exception {
        UUID id = new UUID(0, 7);
        var beleg = new ExportBelege.Beleg(id, "LIEFERANTENBELEG", null, Instant.parse("2026-12-31T23:30:00Z"),
                Instant.parse("2027-01-01T08:00:00Z"), "Lieferant", null, null, null, null,
                List.of(datei("archiv/2026/" + id + "/1-original.pdf", "eins"),
                        datei("archiv/2026/" + id + "/2-original.pdf", "zwei")));
        ByteArrayOutputStream aus = new ByteArrayOutputStream();

        new ExportSchreiber(belege(List.of(beleg), List.of()), quelle(), 10).schreiben(JAHR, aus);

        // 23:30 UTC am 31.12. ist in Zürich schon der 1.1.
        assertThat(entpacken(aus.toByteArray()).keySet()).contains(
                "belege/2027-01-01_LIEFERANTENBELEG_" + id + "_Lieferant-1.pdf",
                "belege/2027-01-01_LIEFERANTENBELEG_" + id + "_Lieferant-2.pdf");
    }

    @Test
    void unterbrechungBrichtZwischenDateienAb() {
        UUID id = new UUID(0, 3);
        var beleg = new ExportBelege.Beleg(id, "QUITTUNG", LocalDate.of(2026, 3, 3), Instant.now(), Instant.now(),
                null, null, null, null, null, List.of(datei("archiv/2026/" + id + "/pdfa.pdf", "x")));
        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> new ExportSchreiber(belege(List.of(beleg), List.of()), quelle(), 10)
                    .schreiben(JAHR, OutputStream.nullOutputStream())).isInstanceOf(InterruptedException.class);
        } finally {
            Thread.interrupted();
        }
    }
}
