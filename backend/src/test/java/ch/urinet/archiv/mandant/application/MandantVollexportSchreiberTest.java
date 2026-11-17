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
import java.util.Optional;
import java.util.UUID;
import java.util.zip.ZipFile;

import static org.assertj.core.api.Assertions.assertThat;

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
