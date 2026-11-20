package ch.urinet.archiv.wissenpaket;

import ch.urinet.archiv.gemeinsam.domain.Seitentext;
import ch.urinet.archiv.wissen.domain.WissensMarkdown;
import ch.urinet.archiv.wissen.domain.WissensPruefung;
import ch.urinet.archiv.wissen.domain.paket.WissensPaketLeser;
import org.junit.jupiter.api.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

class WissenPaketCliTest {
    private Path arbeit, eingabe, ausgabe, quellen;
    private StringWriter text;

    @BeforeEach void vorbereiten() throws IOException {
        arbeit = Files.createDirectories(Path.of("target", "cli-" + UUID.randomUUID()));
        eingabe = Files.createDirectories(arbeit.resolve("eingabe/konzepte")).getParent();
        ausgabe = arbeit.resolve("paket.zip");
        quellen = Files.createDirectory(arbeit.resolve("quellen"));
        text = new StringWriter();
        Files.writeString(eingabe.resolve("konzepte/test.md"), """
                ---
                type: Konzept
                title: Test
                description: Synthetisches Beispiel.
                tags: []
                stale_after: null
                sources: []
                ---
                # Beispiel
                """);
    }

    @AfterEach void aufraeumen() throws IOException {
        try (var pfade = Files.walk(arbeit)) {
            for (Path p : pfade.sorted(Comparator.reverseOrder()).toList()) Files.delete(p);
        }
    }

    private int aufruf(String... args) {
        text = new StringWriter();
        return WissenPaketCli.command(new PrintWriter(text, true), new PrintWriter(new StringWriter())).execute(args);
    }

    private String[] bauen(Path ziel, String... extra) {
        List<String> args = new ArrayList<>(List.of("bauen", eingabe.toString(), "-o", ziel.toString(),
                "--mandant", "urinet", "--erzeugt-von", "test", "--erzeugt-mit", "lokal",
                "--kontext-stufe", "NORMAL"));
        args.addAll(List.of(extra));
        return args.toArray(String[]::new);
    }

    @Test void bautReproduzierbarOhneEingabenZuVeraendernUndUeberschreibtNurAusdruecklich() throws Exception {
        byte[] seite = Files.readAllBytes(eingabe.resolve("konzepte/test.md"));
        assertThat(aufruf(bauen(ausgabe))).isZero();
        byte[] zip = Files.readAllBytes(ausgabe);
        assertThat(new WissensPaketLeser().lesen(zip).seiten()).hasSize(1);
        assertThat(aufruf("validate", ausgabe.toString(), "--json")).isZero();
        assertThat(aufruf(bauen(ausgabe))).isEqualTo(1);
        assertThat(Files.readAllBytes(ausgabe)).isEqualTo(zip);
        assertThat(aufruf(bauen(ausgabe, "--ueberschreiben"))).isZero();
        assertThat(Files.readAllBytes(ausgabe)).isEqualTo(zip);
        assertThat(Files.readAllBytes(eingabe.resolve("konzepte/test.md"))).isEqualTo(seite);
        assertThat(Files.exists(eingabe.resolve("paket.yaml"))).isFalse();
        assertThat(aufruf(bauen(eingabe.resolve("ergebnis.zip")))).isEqualTo(1);
        assertThat(Files.exists(eingabe.resolve("ergebnis.zip"))).isFalse();
        assertThat(text.toString()).contains("Cloud-Modelle", "INTERN", "PERSONAL");
    }

    @Test void fehlerhafteGrenzenUndCloudStufeErzeugenKeinZip() throws Exception {
        Path seite = eingabe.resolve("konzepte/test.md");
        Files.writeString(seite, "x".repeat(131_073));
        assertThat(aufruf(bauen(ausgabe))).isEqualTo(1);
        assertThat(text.toString()).contains("GRENZE");
        assertThat(Files.exists(ausgabe)).isFalse();
        Files.delete(seite);
        assertThat(aufruf("bauen", eingabe.toString(), "-o", ausgabe.toString(), "--mandant", "urinet",
                "--erzeugt-von", "test", "--erzeugt-mit", "cloud", "--kontext-stufe", "INTERN")).isEqualTo(1);
        assertThat(text.toString()).contains("CLOUD_STUFE");
        assertThat(Files.exists(ausgabe)).isFalse();
    }

    @Test void grosseGueltigeOrdnerWerdenOhneUnzulaessigesKompressionsverhaeltnisGepackt() throws Exception {
        String inhalt = Files.readString(eingabe.resolve("konzepte/test.md")) + "# " + "知".repeat(19_000) + "\n";
        for (int i = 0; i < 180; i++) Files.writeString(eingabe.resolve("konzepte/seite-" + i + ".md"), inhalt);
        assertThat(aufruf(bauen(ausgabe))).isZero();
        assertThat(new WissensPaketLeser().lesen(Files.readAllBytes(ausgabe)).seiten()).hasSize(181);
        assertThat(Files.size(ausgabe)).isLessThan(10L * 1024 * 1024);
    }

    @Test void validierenUndBauenSchreibenNurDasAngegebeneZiel() throws Exception {
        Files.copy(Path.of("../../backend/src/test/resources/wissen-pakete/paket.yaml"), eingabe.resolve("paket.yaml"));
        Map<String, byte[]> vorher = dateien();
        assertThat(aufruf("validate", eingabe.toString(), "--quellen", quellen.toString())).isZero();
        Map<String, byte[]> nachValidieren = dateien();
        assertThat(nachValidieren.keySet()).isEqualTo(vorher.keySet());
        vorher.forEach((name, inhalt) -> assertThat(nachValidieren.get(name)).isEqualTo(inhalt));
        assertThat(aufruf(bauen(ausgabe, "--quellen", quellen.toString()))).isZero();
        Map<String, byte[]> nachher = dateien();
        assertThat(nachher.remove("paket.zip")).isNotNull();
        assertThat(nachher.keySet()).isEqualTo(vorher.keySet());
        vorher.forEach((name, inhalt) -> assertThat(nachher.get(name)).isEqualTo(inhalt));
    }

    private Map<String, byte[]> dateien() throws IOException {
        Map<String, byte[]> ergebnis = new TreeMap<>();
        try (var lauf = Files.walk(arbeit)) {
            for (Path p : lauf.filter(Files::isRegularFile).toList())
                ergebnis.put(arbeit.relativize(p).toString(), Files.readAllBytes(p));
        }
        return ergebnis;
    }

    @Test void sperrtFormatMarkdownSymlinksUndAusgabeAlsHardlink() throws Exception {
        Path seite = eingabe.resolve("konzepte/test.md");
        Files.writeString(seite, Files.readString(seite) + "\n<script>geheim</script>\n");
        assertThat(aufruf(bauen(ausgabe))).isEqualTo(1);
        assertThat(text.toString()).contains("LINK_UNGUELTIG").doesNotContain("geheim");
        assertThat(Files.exists(ausgabe)).isFalse();
        Files.writeString(seite, Files.readString(seite).replace("<script>geheim</script>", "# Beispiel"));
        Path link = eingabe.resolve("konzepte/link.md");
        Files.createSymbolicLink(link, seite.toAbsolutePath());
        assertThat(aufruf(bauen(ausgabe))).isEqualTo(1);
        assertThat(text.toString()).contains("SYMLINK");
        Files.delete(link);
        Files.createLink(ausgabe, seite);
        byte[] vorher = Files.readAllBytes(seite);
        assertThat(aufruf(bauen(ausgabe, "--ueberschreiben"))).isEqualTo(1);
        assertThat(Files.readAllBytes(seite)).isEqualTo(vorher);
    }

    @Test void ordnerMitMetadatenWirdGleichWieDasZipGeprueft() throws Exception {
        Files.copy(Path.of("../../backend/src/test/resources/wissen-pakete/paket.yaml"), eingabe.resolve("paket.yaml"));
        assertThat(aufruf("validate", eingabe.toString(), "--json")).isZero();
        assertThat(aufruf(bauen(ausgabe))).isZero();
        assertThat(aufruf("validate", ausgabe.toString(), "--json")).isZero();
        assertThat(text.toString()).doesNotContain("Synthetisches Beispiel");
    }

    @Test void quellenHashUndLokalBestimmbarerAuszugSindNurVorpruefung() throws Exception {
        byte[] original = "synthetisches original".getBytes(StandardCharsets.UTF_8);
        Files.write(quellen.resolve("original.pdf"), original);
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(original));
        Path seite = eingabe.resolve("konzepte/test.md");
        Files.writeString(seite, Files.readString(seite).replace("sources: []", """
                sources:
                  - id: q-test
                    sha256: '%s'
                    seite: 2
                    auszug: 'Wortlaut'
                """.formatted(hash)));
        assertThat(aufruf(bauen(ausgabe, "--quellen", quellen.toString()))).isZero();
        var paket = new WissensPaketLeser().lesen(Files.readAllBytes(ausgabe));
        var treffer = LokaleQuellen.pruefen(paket, quellen,
                p -> Optional.of(new Seitentext(List.of("erste Seite", "Wortlaut"))));
        assertThat(treffer).isEmpty();
        var fehlt = LokaleQuellen.pruefen(paket, quellen,
                p -> Optional.of(new Seitentext(List.of("Wortlaut", "andere Seite"))));
        assertThat(fehlt).extracting(OfflinePruefung.Meldung::code).containsExactly("AUSZUG_LOKAL_NICHT_GEFUNDEN");
        Files.writeString(quellen.resolve("original.pdf"), "anders");
        assertThat(LokaleQuellen.pruefen(paket, quellen))
                .extracting(OfflinePruefung.Meldung::code).containsExactly("QUELLE_NICHT_GEPRUEFT");
    }

    @Test void cliPrueftPdfTextUeberPdftotextOhneInhalteAuszugeben() throws Exception {
        byte[] original = "synthetisches original".getBytes(StandardCharsets.UTF_8);
        Files.write(quellen.resolve("original.pdf"), original);
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(original));
        Path seite = eingabe.resolve("konzepte/test.md");
        Files.writeString(seite, Files.readString(seite).replace("sources: []", """
                sources:
                  - id: q-test
                    sha256: '%s'
                    seite: 2
                    auszug: 'Wortlaut'
                """.formatted(hash)));
        assertThat(aufruf(bauen(ausgabe))).isZero();
        Path bin = Files.createDirectory(arbeit.resolve("bin"));
        Path extraktor = bin.resolve("pdftotext");
        Files.writeString(extraktor, "#!/bin/sh\nprintf 'Wortlaut\\fAndere Seite\\f'\n");
        assertThat(extraktor.toFile().setExecutable(true)).isTrue();
        var prozess = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Djava.io.tmpdir=" + arbeit.toAbsolutePath(), "-cp", System.getProperty("surefire.test.class.path"),
                WissenPaketCli.class.getName(),
                "validate", ausgabe.toAbsolutePath().toString(), "--quellen", quellen.toAbsolutePath().toString(), "--json");
        prozess.environment().put("PATH", bin.toAbsolutePath() + File.pathSeparator + System.getenv("PATH"));
        prozess.directory(arbeit.toFile());
        prozess.redirectErrorStream(true);
        Process lauf = prozess.start();
        String ausgabeText = new String(lauf.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(lauf.waitFor()).isZero();
        assertThat(ausgabeText).contains("AUSZUG_LOKAL_NICHT_GEFUNDEN").doesNotContain("Wortlaut", "Andere Seite");
    }

    @Test void toolKlassenHabenKeineNetzwerkAbhaengigkeit() throws IOException {
        List<Class<?>> klassen = new ArrayList<>(List.of(WissenPaketCli.class, PaketDateien.class,
                OfflinePruefung.class, LokaleQuellen.class, WissensPaketLeser.class, WissensPruefung.class,
                WissensMarkdown.class, Seitentext.class));
        for (int i = 0; i < klassen.size(); i++) klassen.addAll(List.of(klassen.get(i).getDeclaredClasses()));
        for (Class<?> klasse : klassen) {
            String name = "/" + klasse.getName().replace('.', '/') + ".class";
            try (InputStream strom = klasse.getResourceAsStream(name)) {
                assertThat(strom).isNotNull();
                String bytes = new String(strom.readAllBytes(), StandardCharsets.ISO_8859_1);
                assertThat(bytes).doesNotContain("java/net/", "java/net/http/", "HttpClient", "Socket");
            }
        }
    }
}
