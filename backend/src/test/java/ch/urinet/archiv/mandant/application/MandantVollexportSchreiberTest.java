package ch.urinet.archiv.mandant.application;

import ch.urinet.archiv.ablage.application.AblagePort;
import ch.urinet.archiv.ablage.domain.Kopie;
import ch.urinet.archiv.mandant.domain.MandantCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.zip.ZipFile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MandantVollexportSchreiberTest {

    private static final int DATEIGROESSE = 32 * 1024 * 1024;
    private static final String DATEI_SHA256 = digest(DATEIGROESSE);

    @Test
    void csvEntschaerftFormelnUndEscapedAnfuehrungszeichen() {
        String zeile = MandantVollexportSchreiber.csvZeile("=1+1", "+1", "-1", "@cmd", "a\"b");

        assertThat(zeile).isEqualTo("\"'=1+1\",\"'+1\",\"'-1\",\"'@cmd\",\"a\"\"b\"\r\n");
    }

    @Test
    void grossesDokumentWirdOhneVollstaendigesDateiArrayInsZipGestreamt(@TempDir Path temp) throws Exception {
        UUID id = UUID.randomUUID();
        MandantVollexportDaten daten = new MandantVollexportDaten() {
            @Override
            public List<Dokument> seite(Optional<UUID> nach, int anzahl) {
                if (nach.isPresent()) {
                    return List.of();
                }
                return List.of(new Dokument(id, "ARCHIVIERT", "BELEG", """
                        {"id":"%s","status":"ARCHIVIERT","art":"BELEG"}
                        """.formatted(id), List.of(new Datei("archiv/test", "v1", DATEI_SHA256,
                        "dateien/" + id + "/ORIGINAL-1.bin"))));
            }

            @Override
            public List<Manifestdatei> manifeste() {
                return List.of();
            }
        };
        AblagePort ablage = (AblagePort) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{AblagePort.class}, (proxy, methode, argumente) -> {
                    if (!methode.getName().equals("sicherungsKopie")) {
                        throw new AssertionError("Unerwarteter Ablageaufruf: " + methode.getName());
                    }
                    OutputStream ziel = (OutputStream) argumente[3];
                    MessageDigest digest = MessageDigest.getInstance("SHA-256");
                    byte[] block = new byte[8192];
                    for (int i = 0; i < block.length; i++) {
                        block[i] = (byte) i;
                    }
                    for (int geschrieben = 0; geschrieben < DATEIGROESSE; geschrieben += block.length) {
                        digest.update(block);
                        ziel.write(block);
                    }
                    return new Kopie(HexFormat.of().formatHex(digest.digest()), DATEIGROESSE);
                });
        Path zipPfad = temp.resolve("vollexport.zip");
        try (OutputStream ziel = Files.newOutputStream(zipPfad)) {
            new MandantVollexportSchreiber(daten, ablage).schreiben(new MandantCode("vhf-gsk"),
                    UUID.randomUUID(), temp, ziel);
        }

        try (ZipFile zip = new ZipFile(zipPfad.toFile())) {
            var eintrag = zip.getEntry("dateien/" + id + "/ORIGINAL-1.bin");
            assertThat(eintrag).isNotNull();
            assertThat(eintrag.getSize()).isEqualTo(DATEIGROESSE);
            assertThat(zip.getInputStream(zip.getEntry("pruefsummen.sha256")).readAllBytes())
                    .asString(java.nio.charset.StandardCharsets.UTF_8)
                    .contains(DATEI_SHA256 + "  dateien/" + id + "/ORIGINAL-1.bin");
        }
    }

    @Test
    void csvEntschaerftAuchTabulatorUndWagenruecklauf() {
        assertThat(MandantVollexportSchreiber.csvZeile("\t=cmd", "\rx")).isEqualTo("\"'\t=cmd\",\"'\rx\"\r\n");
    }

    @Test
    void zipEnthaeltOriginalArbeitsfassungPdfaMetadatenManifestUndPruefsummen(@TempDir Path temp) throws Exception {
        UUID id = UUID.randomUUID();
        Map<String, byte[]> inhalt = Map.of(
                "archiv/o", "original".getBytes(), "archiv/a", "arbeit".getBytes(),
                "archiv/p", "pdfa".getBytes(), "manifest/m", "manifest".getBytes());
        var dokument = new MandantVollexportDaten.Dokument(id, "ARCHIVIERT", "BELEG", "{\"id\":\"" + id + "\"}", List.of(
                datei("archiv/o", inhalt, "dateien/" + id + "/ORIGINAL-1.bin"),
                datei("archiv/a", inhalt, "dateien/" + id + "/ARBEITSFASSUNG-1.bin"),
                datei("archiv/p", inhalt, "dateien/" + id + "/pdfa.pdf")));
        var manifest = new MandantVollexportDaten.Manifestdatei(java.time.LocalDate.of(2026, 1, 2), "json",
                "manifest/m", "v1", sha(inhalt.get("manifest/m")));

        Path zipPfad = schreiben(temp, daten(List.of(dokument), List.of(manifest)), ablage(inhalt));

        try (ZipFile zip = new ZipFile(zipPfad.toFile())) {
            assertThat(text(zip, "dateien/" + id + "/ORIGINAL-1.bin")).isEqualTo("original");
            assertThat(text(zip, "dateien/" + id + "/ARBEITSFASSUNG-1.bin")).isEqualTo("arbeit");
            assertThat(text(zip, "dateien/" + id + "/pdfa.pdf")).isEqualTo("pdfa");
            assertThat(text(zip, "manifeste/2026-01-02/0.bin")).isEqualTo("manifest");
            assertThat(text(zip, "metadaten.json")).contains(id.toString());
            assertThat(text(zip, "metadaten.csv")).contains(id.toString(), "ARCHIVIERT");
            assertThat(text(zip, "manifeste.csv")).contains("manifest/m");
            assertThat(text(zip, "pruefsummen.sha256"))
                    .contains(sha(inhalt.get("archiv/o")) + "  dateien/" + id + "/ORIGINAL-1.bin")
                    .contains(sha(inhalt.get("archiv/p")) + "  dateien/" + id + "/pdfa.pdf")
                    .contains(sha(inhalt.get("manifest/m")) + "  manifeste/2026-01-02/0.bin")
                    .contains("  metadaten.json");
        }
    }

    @Test
    void seitenwechselUeberZweihundertDokumenteSchreibtAlleEinmal(@TempDir Path temp) throws Exception {
        List<MandantVollexportDaten.Dokument> alle = new java.util.ArrayList<>();
        for (int i = 0; i < 405; i++) {
            alle.add(new MandantVollexportDaten.Dokument(new UUID(0, i + 1), "NEU", "BELEG", "{\"n\":" + i + "}",
                    List.of()));
        }

        Path zipPfad = schreiben(temp, daten(alle, List.of()), ablage(Map.of()));

        try (ZipFile zip = new ZipFile(zipPfad.toFile())) {
            String csv = text(zip, "metadaten.csv");
            assertThat(csv.lines().count()).isEqualTo(406);
            assertThat(text(zip, "metadaten.json")).contains("{\"n\":0}", "{\"n\":404}");
        }
    }

    @Test
    void abweichendePruefsummeBrichtAbOhneZip(@TempDir Path temp) {
        UUID id = UUID.randomUUID();
        Map<String, byte[]> inhalt = Map.of("archiv/o", "original".getBytes());
        var dokument = new MandantVollexportDaten.Dokument(id, "ARCHIVIERT", "BELEG", "{}", List.of(
                new MandantVollexportDaten.Datei("archiv/o", "v1", sha("anderer Inhalt".getBytes()),
                        "dateien/" + id + "/ORIGINAL-1.bin")));

        assertThatThrownBy(() -> schreiben(temp, daten(List.of(dokument), List.of()), ablage(inhalt)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("Prüfsumme weicht ab");
    }

    @Test
    void ungueltigeZipPfadeWerdenAbgelehnt(@TempDir Path temp) {
        for (String pfad : List.of("../aussen.bin", "/absolut.bin", "dateien\\x.bin", "dateien/a b.bin")) {
            Map<String, byte[]> inhalt = Map.of("archiv/o", "x".getBytes());
            var dokument = new MandantVollexportDaten.Dokument(UUID.randomUUID(), "NEU", "BELEG", "{}",
                    List.of(new MandantVollexportDaten.Datei("archiv/o", "v1", sha("x".getBytes()), pfad)));

            assertThatThrownBy(() -> schreiben(temp, daten(List.of(dokument), List.of()), ablage(inhalt)))
                    .as(pfad).isInstanceOf(IllegalStateException.class).hasMessageContaining("Ungültiger ZIP-Pfad");
        }
    }

    private static MandantVollexportDaten.Datei datei(String schluessel, Map<String, byte[]> inhalt, String zipPfad) {
        return new MandantVollexportDaten.Datei(schluessel, "v1", sha(inhalt.get(schluessel)), zipPfad);
    }

    private static Path schreiben(Path temp, MandantVollexportDaten daten, AblagePort ablage) throws Exception {
        Path zipPfad = temp.resolve("export-" + UUID.randomUUID() + ".zip");
        try (OutputStream ziel = Files.newOutputStream(zipPfad)) {
            new MandantVollexportSchreiber(daten, ablage).schreiben(new MandantCode("vhf-gsk"), UUID.randomUUID(),
                    temp, ziel);
        }
        return zipPfad;
    }

    private static MandantVollexportDaten daten(List<MandantVollexportDaten.Dokument> dokumente,
                                                List<MandantVollexportDaten.Manifestdatei> manifeste) {
        return new MandantVollexportDaten() {
            @Override
            public List<Dokument> seite(Optional<UUID> nach, int anzahl) {
                return dokumente.stream().filter(d -> nach.isEmpty() || d.id().compareTo(nach.get()) > 0)
                        .limit(anzahl).toList();
            }

            @Override
            public List<Manifestdatei> manifeste() {
                return manifeste;
            }
        };
    }

    private AblagePort ablage(Map<String, byte[]> inhalt) {
        return (AblagePort) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{AblagePort.class},
                (proxy, methode, argumente) -> {
                    if (!methode.getName().equals("sicherungsKopie")) {
                        throw new AssertionError("Unerwarteter Ablageaufruf: " + methode.getName());
                    }
                    byte[] daten = inhalt.get((String) argumente[1]);
                    ((OutputStream) argumente[3]).write(daten);
                    return new Kopie(sha(daten), daten.length);
                });
    }

    private static String text(ZipFile zip, String name) throws java.io.IOException {
        var eintrag = zip.getEntry(name);
        assertThat(eintrag).as(name).isNotNull();
        return new String(zip.getInputStream(eintrag).readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
    }

    private static String sha(byte[] daten) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(daten));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String digest(int laenge) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] block = new byte[8192];
            for (int i = 0; i < block.length; i++) {
                block[i] = (byte) i;
            }
            for (int geschrieben = 0; geschrieben < laenge; geschrieben += block.length) {
                digest.update(block);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
