package ch.urinet.archiv.mandant.application;

import ch.urinet.archiv.ablage.application.AblagePort;
import ch.urinet.archiv.ablage.domain.Kopie;
import ch.urinet.archiv.mandant.domain.MandantCode;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.zip.ZipFile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Läuft mit {@code -Xmx64m} (Maven-Ausführung {@code speicher}, backend/pom.xml): Eine 256 MiB grosse Datei, viermal
 * so gross wie der Heap, wird nur blockweise erzeugt, gelesen und ins ZIP geschrieben. Ein Schreiber, der die Datei
 * ganz (z. B. mit {@code readAllBytes}) im Speicher hielte, bräche hier mit {@link OutOfMemoryError} ab.
 */
class MandantVollexportKnapperHeapTest {

    private static final long DATEIGROESSE = 256L * 1024 * 1024;
    private static final int BLOCK = 8192;

    @Test
    void grosseDateiUeberDemHeapWirdGestreamtInsZipGeschrieben(@TempDir Path temp) throws Exception {
        String sha256 = erzeugen(OutputStream.nullOutputStream());
        UUID id = UUID.randomUUID();
        MandantVollexportDaten daten = new MandantVollexportDaten() {
            @Override
            public List<Dokument> seite(Optional<UUID> nach, int anzahl) {
                return nach.isPresent() ? List.of() : List.of(new Dokument(id, "ARCHIVIERT", "BELEG",
                        "{\"id\":\"" + id + "\"}", List.of(new Datei("archiv/test", "v1", sha256,
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
                    return new Kopie(erzeugen((OutputStream) argumente[3]), DATEIGROESSE);
                });
        Path zipPfad = temp.resolve("vollexport.zip");
        try (OutputStream ziel = Files.newOutputStream(zipPfad)) {
            new MandantVollexportSchreiber(daten, ablage, (mandant, strom) -> {}).schreiben(new MandantCode("vhf-gsk"),
                    UUID.randomUUID(), temp, ziel);
        }

        try (ZipFile zip = new ZipFile(zipPfad.toFile())) {
            var eintrag = zip.getEntry("dateien/" + id + "/ORIGINAL-1.bin");
            assertThat(eintrag).isNotNull();
            assertThat(eintrag.getSize()).isEqualTo(DATEIGROESSE);
            assertThat(new String(zip.getInputStream(zip.getEntry("pruefsummen.sha256")).readAllBytes(),
                    StandardCharsets.UTF_8)).contains(sha256 + "  dateien/" + id + "/ORIGINAL-1.bin");
        }
    }

    /** Gegenprobe: Der Heap ist wirklich zu klein für die Datei, ein Puffern der ganzen Datei schlüge fehl. */
    @Test
    void heapIstKleinerAlsDieDatei() {
        Assumptions.assumeTrue(Boolean.getBoolean("archiv.speicher-test"), "nur in der Ausführung «speicher»");

        assertThat(Runtime.getRuntime().maxMemory()).isLessThan(DATEIGROESSE / 2);
        assertThatThrownBy(() -> {
            byte[] ganz = new byte[(int) DATEIGROESSE];
            ganz[0] = 1;
        }).isInstanceOf(OutOfMemoryError.class);
    }

    /** Schreibt die synthetische Datei blockweise nach {@code ziel}; liefert ihre SHA-256 (nie ganz im Speicher). */
    private static String erzeugen(OutputStream ziel) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] block = new byte[BLOCK];
        for (int i = 0; i < block.length; i++) {
            block[i] = (byte) i;
        }
        for (long geschrieben = 0; geschrieben < DATEIGROESSE; geschrieben += BLOCK) {
            digest.update(block);
            ziel.write(block);
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
