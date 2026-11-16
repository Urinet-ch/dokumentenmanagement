package ch.urinet.archiv.ablage;

import ch.urinet.archiv.ablage.application.AblagePort;
import ch.urinet.archiv.ablage.domain.Kopie;
import ch.urinet.archiv.ablage.domain.Objektversion;
import ch.urinet.archiv.ablage.domain.Versionsseite;
import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.sicherung.application.SicherungBucketExport;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.lang.reflect.Proxy;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** Surefire führt diese Tests im Speicherlauf mit {@code -Xmx64m} und ohne Quarkus-Start aus. */
class SicherungExportKnapperHeapTest {

    private static final MandantCode MANDANT = new MandantCode("probe");
    private static final int EXPORT_GROESSE = 96 * 1024 * 1024;
    private static final int INVENTAR_ZEILEN = 500_000;
    private static final String EXPORT_SCHLUESSEL = "archiv/gross.bin";
    private static final String EXPORT_VERSION = "v1";
    private static final Instant ZEIT = Instant.parse("2026-01-01T00:00:00Z");
    private static final byte[] BLOCK = new byte[32 * 1024];

    @Test
    void exportUndInventarBleibenBei64MiBStromweise() {
        Zaehler ausgabe = new Zaehler();
        SicherungBucketExport export = new SicherungBucketExport(ablage(false));

        export.zuwachs(MANDANT, version -> true, ausgabe);

        long rahmen = SicherungBucketExport.BUCKET_FORMAT.length() + Integer.BYTES * 3L
                + EXPORT_SCHLUESSEL.getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                + EXPORT_VERSION.length() + 2L * Long.BYTES + Integer.BYTES + 32;
        assertThat(ausgabe.bytes).isEqualTo(rahmen + EXPORT_GROESSE);

        long[] zeilen = {0};
        new SicherungBucketExport(ablage(true)).inventar(MANDANT, eintrag -> zeilen[0]++);
        assertThat(zeilen[0]).isEqualTo(INVENTAR_ZEILEN);
    }

    private static AblagePort ablage(boolean inventar) {
        return (AblagePort) Proxy.newProxyInstance(AblagePort.class.getClassLoader(),
                new Class<?>[]{AblagePort.class}, (proxy, methode, args) -> switch (methode.getName()) {
                    case "versionen" -> {
                        String praefix = (String) args[1];
                        if (!praefix.equals("archiv/")) {
                            yield new Versionsseite(List.of(), Optional.empty());
                        }
                        if (!inventar) {
                            yield new Versionsseite(List.of(new Objektversion(EXPORT_SCHLUESSEL, EXPORT_VERSION,
                                    false, ZEIT, EXPORT_GROESSE)), Optional.empty());
                        }
                        Optional<?> marke = (Optional<?>) args[2];
                        int start = marke.map(Versionsseite.Marke.class::cast)
                                .map(m -> Integer.parseInt(m.schluessel().substring("archiv/".length())) + 1)
                                .orElse(0);
                        int ende = Math.min(start + (Integer) args[3], INVENTAR_ZEILEN);
                        List<Objektversion> versionen = new ArrayList<>(ende - start);
                        for (int i = start; i < ende; i++) {
                            versionen.add(new Objektversion(schluessel(i), "v1", false, ZEIT, 0));
                        }
                        Optional<Versionsseite.Marke> weiter = ende < INVENTAR_ZEILEN
                                ? Optional.of(new Versionsseite.Marke(schluessel(ende - 1), "v1"))
                                : Optional.empty();
                        yield new Versionsseite(versionen, weiter);
                    }
                    case "sicherungsSperre" -> Optional.empty();
                    case "sicherungsKopie" -> {
                        long rest = EXPORT_GROESSE;
                        OutputStream ziel = (OutputStream) args[3];
                        while (rest > 0) {
                            int anzahl = (int) Math.min(rest, BLOCK.length);
                            ziel.write(BLOCK, 0, anzahl);
                            rest -= anzahl;
                        }
                        yield new Kopie(sha256(EXPORT_GROESSE), EXPORT_GROESSE);
                    }
                    case "toString" -> "SicherungExportKnapperHeapTest";
                    default -> throw new UnsupportedOperationException(methode.getName());
                });
    }

    private static String schluessel(int nummer) {
        String zahl = Integer.toString(nummer);
        return "archiv/" + "0".repeat(6 - zahl.length()) + zahl;
    }

    private static String sha256(long groesse) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long rest = groesse;
            while (rest > 0) {
                int anzahl = (int) Math.min(rest, BLOCK.length);
                digest.update(BLOCK, 0, anzahl);
                rest -= anzahl;
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static final class Zaehler extends OutputStream {
        private long bytes;

        @Override
        public void write(int b) {
            bytes++;
        }

        @Override
        public void write(byte[] daten, int ab, int laenge) {
            bytes += laenge;
        }
    }
}
