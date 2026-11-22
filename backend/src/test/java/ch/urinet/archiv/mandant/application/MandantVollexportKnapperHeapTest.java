package ch.urinet.archiv.mandant.application;

import ch.urinet.archiv.ablage.application.AblagePort;
import ch.urinet.archiv.ablage.domain.Kopie;
import ch.urinet.archiv.mandant.adapter.out.persistence.JdbcMandantVollexportKonfiguration;
import ch.urinet.archiv.mandant.domain.MandantCode;
import io.agroal.api.AgroalDataSource;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
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

    @Test
    void grosseKonfigurationsversionUndMehrAlsZweihundertVersionenWerdenGestreamt(@TempDir Path temp) throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("archiv.speicher-test"), "nur in der Ausführung «speicher»");
        long groesse = 128L * 1024 * 1024;
        assertThat(Runtime.getRuntime().maxMemory()).isLessThan(groesse);
        MandantVollexportKonfiguration konfiguration = new MandantVollexportKonfiguration() {
            @Override
            public Version aktuell() {
                return new Version(201, "{}");
            }

            @Override
            public void versionen(Versionsausgabe ausgabe) throws java.io.IOException {
                for (int version = 1; version <= 201; version++) {
                    if (version == 1) {
                        ausgabe.schreiben(version, ziel -> {
                            byte[] block = new byte[BLOCK];
                            java.util.Arrays.fill(block, (byte) 'x');
                            byte[] anfang = "{\"text\":\"".getBytes(StandardCharsets.UTF_8);
                            byte[] ende = "\"}".getBytes(StandardCharsets.UTF_8);
                            ziel.write(anfang);
                            long textlaenge = groesse - anfang.length - ende.length;
                            for (long position = 0; position < textlaenge; position += block.length) {
                                ziel.write(block, 0, (int) Math.min(block.length, textlaenge - position));
                            }
                            ziel.write(ende);
                        });
                    } else {
                        ausgabe.schreiben(version, ziel -> ziel.write("{}".getBytes(StandardCharsets.UTF_8)));
                    }
                }
            }
        };
        MandantVollexportDaten daten = new MandantVollexportDaten() {
            @Override
            public List<Dokument> seite(Optional<UUID> nach, int anzahl) {
                return List.of();
            }

            @Override
            public List<Manifestdatei> manifeste() {
                return List.of();
            }
        };
        AblagePort ablage = (AblagePort) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{AblagePort.class},
                (proxy, methode, argumente) -> { throw new AssertionError(methode.getName()); });
        Path datei = temp.resolve("konfiguration.zip");
        try (OutputStream ziel = Files.newOutputStream(datei)) {
            new MandantVollexportSchreiber(daten, ablage, (mandant, strom) -> {}, konfiguration)
                    .schreiben(new MandantCode("vhf-gsk"), UUID.randomUUID(), temp, ziel);
        }
        try (ZipFile zip = new ZipFile(datei.toFile())) {
            var eintrag = zip.getEntry("konfiguration/versionen.json");
            assertThat(eintrag).isNotNull();
            assertThat(eintrag.getSize()).isGreaterThan(groesse);
            try (var strom = zip.getInputStream(eintrag)) {
                strom.skipNBytes(eintrag.getSize() - 128);
                assertThat(new String(strom.readAllBytes(), StandardCharsets.UTF_8))
                        .contains("{\"version\":201,\"inhalt\":{}}]");
            }
        }
    }

    @Test
    void jdbcReaderPuffertKeinVollstaendigesAbbild() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("archiv.speicher-test"), "nur in der Ausführung «speicher»");
        int groesse = 128 * 1024 * 1024;
        assertThat(Runtime.getRuntime().maxMemory()).isLessThan(groesse);
        PreparedStatement versionen = versionenAbfrage();
        PreparedStatement bloecke = blockAbfrage(groesse);
        Connection verbindung = proxy(Connection.class, (proxy, methode, argumente) -> switch (methode.getName()) {
            case "prepareStatement" -> ((String) argumente[0]).startsWith("select version") ? versionen : bloecke;
            case "close" -> null;
            default -> throw new AssertionError(methode.getName());
        });
        AgroalDataSource datenquelle = proxy(AgroalDataSource.class, (proxy, methode, argumente) -> {
            if ("getConnection".equals(methode.getName())) {
                return verbindung;
            }
            throw new AssertionError(methode.getName());
        });
        @SuppressWarnings("unchecked")
        Instance<AgroalDataSource> quelle = proxy(Instance.class, (proxy, methode, argumente) -> {
            if ("get".equals(methode.getName())) {
                return datenquelle;
            }
            throw new AssertionError(methode.getName());
        });
        MandantAufloesung aufloesung = new MandantAufloesung(null, null, null) {
            @Override
            public String schema() {
                return "m_test";
            }
        };
        var leser = new JdbcMandantVollexportKonfiguration(quelle, aufloesung);
        long[] bytes = {0};
        int[] versionenGelesen = {0};
        OutputStream zaehler = new OutputStream() {
            @Override
            public void write(int b) {
                bytes[0]++;
            }

            @Override
            public void write(byte[] b, int off, int len) {
                bytes[0] += len;
            }
        };
        leser.versionen((nummer, inhalt) -> {
            assertThat(nummer).isEqualTo(++versionenGelesen[0]);
            inhalt.schreiben(zaehler);
        });
        assertThat(versionenGelesen[0]).isEqualTo(201);
        assertThat(bytes[0]).isEqualTo(groesse + 200L * 2);
    }

    private static PreparedStatement versionenAbfrage() {
        int[] nach = {0};
        return proxy(PreparedStatement.class, (proxy, methode, argumente) -> switch (methode.getName()) {
            case "setInt" -> {
                nach[0] = (int) argumente[1];
                yield null;
            }
            case "executeQuery" -> zeile(nach[0] < 201, nach[0] + 1, null);
            case "setQueryTimeout", "close" -> null;
            default -> throw new AssertionError(methode.getName());
        });
    }

    private static PreparedStatement blockAbfrage(int groesse) {
        int[] parameter = new int[3];
        return proxy(PreparedStatement.class, (proxy, methode, argumente) -> switch (methode.getName()) {
            case "setInt" -> {
                parameter[(int) argumente[0] - 1] = (int) argumente[1];
                yield null;
            }
            case "executeQuery" -> {
                int laenge = parameter[2] == 1 ? groesse : 2;
                int ab = parameter[0] - 1;
                String text = ab >= laenge ? "" : "x".repeat(Math.min(parameter[1], laenge - ab));
                yield zeile(true, 0, text);
            }
            case "setQueryTimeout", "close" -> null;
            default -> throw new AssertionError(methode.getName());
        });
    }

    private static ResultSet zeile(boolean vorhanden, int nummer, String text) {
        boolean[] naechste = {vorhanden};
        return proxy(ResultSet.class, (proxy, methode, argumente) -> switch (methode.getName()) {
            case "next" -> {
                boolean ergebnis = naechste[0];
                naechste[0] = false;
                yield ergebnis;
            }
            case "getInt" -> nummer;
            case "getString" -> text;
            case "close" -> null;
            default -> throw new AssertionError(methode.getName());
        });
    }

    private static <T> T proxy(Class<T> typ, java.lang.reflect.InvocationHandler handler) {
        return typ.cast(Proxy.newProxyInstance(typ.getClassLoader(), new Class<?>[]{typ}, handler));
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
