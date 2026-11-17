package ch.urinet.archiv.mandant.application;

import ch.urinet.archiv.mandant.adapter.in.kommando.MandantKommando;
import ch.urinet.archiv.ablage.application.AblagePort;
import ch.urinet.archiv.ablage.domain.Kopie;
import ch.urinet.archiv.ablage.domain.MandantAblageAbbau;
import ch.urinet.archiv.mandant.domain.MandantCode;
import io.cucumber.java.de.Dann;
import io.cucumber.java.de.Und;
import io.cucumber.java.de.Wenn;

import java.io.OutputStream;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.zip.ZipFile;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Spec 03, Aufgabe 12.2: überprüft exportrelevante Sicherheits- und Aufbewahrungsregeln. */
public class MandantVollexportAbbauSchritte {

    private String csv;
    private Optional<MandantKommando.AbbauAufruf> abbauAufruf = Optional.empty();
    private LocalDate sicherungsstaendeBis;

    @Wenn("eine Vollexport-CSV-Zeile mit einer Formelzelle serialisiert wird")
    public void csvZeileSchreiben() {
        csv = MandantVollexportSchreiber.csvZeile("id", "=1+1");
    }

    @Dann("ist die Formelzelle in der CSV entschärft")
    public void csvIstEntschaerft() {
        assertThat(csv).contains("\"'=1+1\"");
    }

    @Wenn("der Betreiber einen Abbau-Aufruf ohne Ausführungsflag parst")
    public void abbauParsen() {
        abbauAufruf = MandantKommando.abbauLesen(List.of("abbauen", "vhf-gsk", "--nach-export",
                "bc9b7ac8-0ddd-4ab8-9850-27a7f598f0d7"));
    }

    @Dann("ist der Abbau-Aufruf ein Trockenlauf")
    public void trockenlauf() {
        assertThat(abbauAufruf).hasValueSatisfying(aufruf -> assertThat(aufruf.ausfuehren()).isFalse());
    }

    @Und("ein widersprüchlicher Trockenlauf wird abgelehnt")
    public void widerspruch() {
        assertThat(MandantKommando.abbauLesen(List.of("abbauen", "vhf-gsk", "--nach-export",
                "bc9b7ac8-0ddd-4ab8-9850-27a7f598f0d7", "--ausfuehren", "--dry-run"))).isEmpty();
    }

    @Wenn("die Sicherungsfrist für einen Mandanten berechnet wird")
    public void sicherungsfristBerechnen() {
        sicherungsstaendeBis = MandantAbbau.sicherungsstaendeBis(Instant.parse("2026-01-01T00:00:00Z"));
    }

    @Dann("nennt der Bericht eine Datenbankfrist und die unbefristete Datei-Aufbewahrung")
    public void sicherungsaufbewahrung() {
        assertThat(sicherungsstaendeBis).isEqualTo(LocalDate.of(2036, 1, 1));
        // Berechnung vom Löschtag in Zürcher Zeit: 23:30 UTC am 15.3. ist schon der 16.3.
        assertThat(MandantAbbau.sicherungsstaendeBis(Instant.parse("2026-03-15T23:30:00Z")))
                .isEqualTo(LocalDate.of(2036, 3, 16));
        assertThat(MandantAbbau.DATEI_SICHERUNG_AUFBEWAHRUNG)
                .isEqualTo("unbefristet, bis die Spec zur Löschfunktion (Spec 01, 9.9) einen Weg regelt");
    }

    private static final Instant START = Instant.parse("2026-01-01T00:00:00Z");
    private static final java.util.UUID EXPORT = java.util.UUID.fromString("6d60de8a-0f65-47df-96f2-4c7fe5e381cd");
    private final Map<MandantCode, Path> zips = new java.util.HashMap<>();
    private Path zip;
    private String pruefsummen;
    private MandantAbbauTest.Testaufbau aufbau;
    private MandantAbbau.Bericht bericht;
    private Throwable fehler;

    @Wenn("ein Vollexport mit einem Dokument und einem Manifest geschrieben wird")
    public void vollexportSchreiben() throws Exception {
        zip = schreiben(new MandantCode("vhf-gsk"), "vhf");
    }

    @Dann("enthält das ZIP Original, Arbeitsfassung, PDF\\/A, Metadaten und Manifest")
    public void zipInhalt() throws Exception {
        try (ZipFile z = new ZipFile(zip.toFile())) {
            assertThat(java.util.Collections.list(z.entries()).stream().map(java.util.zip.ZipEntry::getName))
                    .contains("dateien/" + DOKUMENT + "/ORIGINAL-1.bin", "dateien/" + DOKUMENT + "/ARBEITSFASSUNG-1.bin",
                            "dateien/" + DOKUMENT + "/pdfa.pdf", "metadaten.json", "metadaten.csv",
                            "manifeste/2026-01-02/0.bin", "pruefsummen.sha256");
            pruefsummen = new String(z.getInputStream(z.getEntry("pruefsummen.sha256")).readAllBytes(),
                    StandardCharsets.UTF_8);
        }
    }

    @Und("die Prüfsummenliste stimmt mit dem Inhalt des ZIP überein")
    public void pruefsummenStimmen() throws Exception {
        try (ZipFile z = new ZipFile(zip.toFile())) {
            for (String zeile : pruefsummen.split("\n")) {
                String[] teile = zeile.split("  ", 2);
                byte[] inhalt = z.getInputStream(z.getEntry(teile[1])).readAllBytes();
                assertThat(sha(inhalt)).as(teile[1]).isEqualTo(teile[0]);
            }
            assertThat(pruefsummen.split("\n")).hasSize(7);
        }
    }

    @Wenn("für zwei Mandanten je ein Vollexport geschrieben wird")
    public void zweiMandanten() throws Exception {
        zips.put(new MandantCode("vhf-gsk"), schreiben(new MandantCode("vhf-gsk"), "vhf"));
        zips.put(new MandantCode("urinet"), schreiben(new MandantCode("urinet"), "uri"));
    }

    @Dann("enthält jedes ZIP nur die Dateien des eigenen Mandanten")
    public void nurEigeneDateien() throws Exception {
        for (var eintrag : zips.entrySet()) {
            String eigen = eintrag.getKey().wert().equals("urinet") ? "uri" : "vhf";
            try (ZipFile z = new ZipFile(eintrag.getValue().toFile())) {
                assertThat(new String(z.getInputStream(z.getEntry("dateien/" + DOKUMENT + "/ORIGINAL-1.bin"))
                        .readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("original-" + eigen);
            }
        }
    }

    @Wenn("nach dem Vollexport ein weiteres Dokument entstanden ist")
    public void veralteterExport() {
        aufbau = new MandantAbbauTest.Testaufbau(START);
        aufbau.bestandAnzahl = aufbau.anzahl + 1;
        fehler = catchThrowable(() -> aufbau.abbau().ausfuehren(new MandantCode("vhf-gsk"), EXPORT, true, false, MandantAbbauTest.ANLASS));
    }

    @Dann("bricht der Abbau ab und der Mandant bleibt aktiv")
    public void abbauBrichtAb() {
        assertThat(fehler).isInstanceOf(MandantFehler.class).hasMessageContaining("veraltet");
        assertThat(aufbau.register.status.mandant().aktiv()).isTrue();
    }

    @Wenn("der Abbau 29 Tage nach der Deaktivierung ausgeführt wird")
    public void vorFrist() {
        aufbau = new MandantAbbauTest.Testaufbau(START.plusSeconds(29L * 24 * 60 * 60));
        aufbau.register.deaktivieren(new MandantCode("vhf-gsk"), EXPORT, START, "tester", "Kundenende");
        bericht = aufbau.abbau().ausfuehren(new MandantCode("vhf-gsk"), EXPORT, true, false, MandantAbbauTest.ANLASS);
    }

    @Dann("meldet der Abbau die Wartefrist und löscht nichts")
    public void wartefrist() {
        assertThat(bericht.zustand()).isEqualTo(MandantAbbau.Zustand.WARTEFRIST);
        assertThat(aufbau.ablageAufrufe.get()).isZero();
        assertThat(aufbau.datenbank.schemaEntfernt).isFalse();
    }

    @Wenn("der Abbau nach 30 Tagen mit einem gesperrten Objekt ausgeführt wird")
    public void nachFristMitSperre() {
        aufbau = new MandantAbbauTest.Testaufbau(START.plusSeconds(30L * 24 * 60 * 60));
        aufbau.register.deaktivieren(new MandantCode("vhf-gsk"), EXPORT, START, "tester", "Kundenende");
        aufbau.gesperrteObjekte = List.of(new MandantAblageAbbau.GesperrtesObjekt(
                "manifest/2026-01-01.json", "version-1", START.plusSeconds(365L * 24 * 60 * 60)));
        bericht = aufbau.abbau().ausfuehren(new MandantCode("vhf-gsk"), EXPORT, true, false, MandantAbbauTest.ANLASS);
    }

    @Dann("ist der Abbau unvollständig und listet das gesperrte Objekt auf")
    public void unvollstaendig() {
        assertThat(bericht.zustand()).isEqualTo(MandantAbbau.Zustand.ABBAU_UNVOLLSTAENDIG);
        assertThat(bericht.ablage().gesperrt()).extracting(MandantAblageAbbau.GesperrtesObjekt::schluessel)
                .containsExactly("manifest/2026-01-01.json");
    }

    private static final java.util.UUID DOKUMENT = java.util.UUID.fromString("11111111-2222-3333-4444-555555555555");

    private static Path schreiben(MandantCode mandant, String kennung) throws Exception {
        Path temp = Files.createTempDirectory("vollexport-schritte-");
        Map<String, byte[]> inhalt = Map.of("archiv/o", ("original-" + kennung).getBytes(StandardCharsets.UTF_8),
                "archiv/a", ("arbeit-" + kennung).getBytes(StandardCharsets.UTF_8),
                "archiv/p", ("pdfa-" + kennung).getBytes(StandardCharsets.UTF_8),
                "manifest/m", ("manifest-" + kennung).getBytes(StandardCharsets.UTF_8));
        var dokument = new MandantVollexportDaten.Dokument(DOKUMENT, "ARCHIVIERT", "BELEG", "{}", List.of(
                datei("archiv/o", inhalt, "ORIGINAL-1.bin"), datei("archiv/a", inhalt, "ARBEITSFASSUNG-1.bin"),
                datei("archiv/p", inhalt, "pdfa.pdf")));
        var manifest = new MandantVollexportDaten.Manifestdatei(LocalDate.of(2026, 1, 2), "json", "manifest/m", "v1",
                sha(inhalt.get("manifest/m")));
        MandantVollexportDaten daten = new MandantVollexportDaten() {
            @Override
            public List<Dokument> seite(Optional<java.util.UUID> nach, int anzahl) {
                return nach.isPresent() ? List.of() : List.of(dokument);
            }

            @Override
            public List<Manifestdatei> manifeste() {
                return List.of(manifest);
            }
        };
        AblagePort ablage = (AblagePort) Proxy.newProxyInstance(AblagePort.class.getClassLoader(),
                new Class<?>[]{AblagePort.class}, (proxy, methode, argumente) -> {
                    assertThat(argumente[0]).isEqualTo(mandant);
                    byte[] bytes = inhalt.get((String) argumente[1]);
                    ((OutputStream) argumente[3]).write(bytes);
                    return new Kopie(sha(bytes), bytes.length);
                });
        Path ziel = temp.resolve("export.zip");
        try (OutputStream strom = Files.newOutputStream(ziel)) {
            new MandantVollexportSchreiber(daten, ablage).schreiben(mandant, java.util.UUID.randomUUID(), temp, strom);
        }
        return ziel;
    }

    private static MandantVollexportDaten.Datei datei(String schluessel, Map<String, byte[]> inhalt, String name) {
        return new MandantVollexportDaten.Datei(schluessel, "v1", sha(inhalt.get(schluessel)),
                "dateien/" + DOKUMENT + "/" + name);
    }

    private static Throwable catchThrowable(org.assertj.core.api.ThrowableAssert.ThrowingCallable arbeit) {
        return org.assertj.core.api.Assertions.catchThrowable(arbeit);
    }

    private static String sha(byte[] daten) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(daten));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
