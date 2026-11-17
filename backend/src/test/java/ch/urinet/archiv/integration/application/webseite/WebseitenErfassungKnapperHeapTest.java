package ch.urinet.archiv.integration.application.webseite;

import ch.urinet.archiv.dokument.application.DokumentErfassen;
import ch.urinet.archiv.mandant.application.MandantAufloesung;
import ch.urinet.archiv.mandant.application.MandantAusAnmeldung;
import ch.urinet.archiv.mandant.domain.MandantCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** Surefire führt diese Probe mit {@code -Xmx64m} aus; sie nutzt denselben Datei-Reader wie der Webseiten-Upload. */
class WebseitenErfassungKnapperHeapTest {

    private static final long DATEIGROESSE = 96L * 1024 * 1024;
    private static final int BLOCK = 8192;

    @Test
    void grosseUploaddateiWirdZumHashenGestreamt(@TempDir Path temp) throws Exception {
        Path datei = temp.resolve("synthetischer-upload.bin");
        String erwartet = erzeugen(datei);
        MandantCode mandant = new MandantCode("probe");
        MandantAufloesung aufloesung = new MandantAufloesung(null,
                () -> new MandantAusAnmeldung.Gueltig(mandant), code -> Optional.of(code.schema()));
        // pruefsumme benötigt nur die Mandantenauflösung und Dateigrenze; die übrigen Upload-Ports werden nicht aufgerufen.
        DokumentErfassen erfassen = new DokumentErfassen(null, null, null, null, aufloesung, null,
                () -> DATEIGROESSE);

        assertThat(Runtime.getRuntime().maxMemory()).isLessThan(DATEIGROESSE);
        DokumentErfassen.EmpfangeneDatei empfangen = erfassen.pruefsumme(datei);

        assertThat(empfangen.groesse()).isEqualTo(DATEIGROESSE);
        assertThat(empfangen.sha256()).isEqualTo(erwartet);
    }

    private static String erzeugen(Path datei) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] block = new byte[BLOCK];
        for (int i = 0; i < block.length; i++) {
            block[i] = (byte) i;
        }
        try (OutputStream ziel = Files.newOutputStream(datei)) {
            for (long geschrieben = 0; geschrieben < DATEIGROESSE; geschrieben += BLOCK) {
                digest.update(block);
                ziel.write(block);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
