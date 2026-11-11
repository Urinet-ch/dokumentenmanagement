package ch.urinet.archiv.importe.adapter.out.csv;

import ch.urinet.archiv.importe.application.CsvImport;
import ch.urinet.archiv.importe.application.ImportEinstellungen;
import ch.urinet.archiv.importe.application.ImportRepository;
import ch.urinet.archiv.importe.domain.CsvTabelle;
import ch.urinet.archiv.importe.domain.GespeicherteZuordnung;
import ch.urinet.archiv.importe.domain.ImportArt;
import ch.urinet.archiv.importe.domain.ImportFeld;
import ch.urinet.archiv.importe.domain.ImportLauf;
import ch.urinet.archiv.importe.domain.ImportZeile;
import ch.urinet.archiv.importe.domain.ZeilenPruefung;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Heap je CSV-Import (Entscheid Fabian 11.11.2026: höchstens ~32 MiB je Import, unabhängig von der Datei). Jede
 * synthetische Datei läuft in einer eigenen JVM mit {@code -XX:+UseSerialGC -Xmn2m -Xmx}{@value #XMX_MIB}{@code m}
 * durch Leser, {@link CsvImport#hochladen} und {@link CsvImport#zuordnen}. Das Repository verhält sich beim Speicher
 * wie {@code JdbcImportRepository} mit dem PG-Treiber: Es hält je Block alle JSON-Strings und ihre UTF-8-Bytes bis
 * zum Schreiben (Batch bis {@code executeBatch}) und beim erneuten Prüfen alle Bytes des Fensters, bevor es die erste
 * Zeile parst (ResultSet); die Antwort wird als JSON serialisiert. Die kleine Young-Generation nähert den Live-Peak an.
 * Die Kind-JVM bekommt nur die Jars, die der Import braucht ({@link #klassenpfad()}); mit dem vollen Klassenpfad von
 * Surefire misst der Test vor allem die Zentralverzeichnisse der Jars.
 * Gemessen (12.11.2026, kleinste {@code -Xmx}, fester Klassenpfad): Basis ohne nennenswerte Datei 4 MiB (macOS/arm64)
 * bzw. 5 MiB (Linux/amd64, wie die CI); höchster Fall 9 MiB bzw. 13 MiB (Blöcke knapp unter 1 Mi Zeichen plus eine
 * Zeile). Ohne Kopfgrenzen, Kürzen, Zeichengrenze der Blöcke und Zeilengrenze brauchten die Formen mit langen Feldern,
 * Zeilen oder Köpfen mindestens 17 MiB, bis 129 MiB. Die Grenze {@value #XMX_MIB} MiB lässt auf amd64 3 MiB Reserve
 * und schlägt bei jeder dieser Formen an.
 */
class CsvImportSpeicherTest {

    static final int XMX_MIB = 16;
    private static final Charset CP1252 = Charset.forName("windows-1252");
    private static final long MAX_GROESSE = 5L * 1024 * 1024;
    private static final int FELD_GRENZE = 10_000;

    @TempDir
    Path ordner;

    static Stream<String> dateien() {
        return DATEIEN.stream().map(Map.Entry::getKey);
    }

    private static final List<Map.Entry<String, Function<Path, Path>>> DATEIEN = List.of(
            Map.entry("beispielexport", ziel -> kopieren("/csv/21crm-kontakte-beispiel.csv", ziel)),
            Map.entry("10'000 Zeilen x 151 Spalten", ziel -> {
                String kopf = String.join(";", Collections.nCopies(151, "Spalte"));
                String zeile = String.join(";", Collections.nCopies(151, "Mü"));
                return schreiben(ziel, kopf + "\n" + (zeile + "\n").repeat(CsvTabelle.MAX_ZEILEN), CP1252);
            }),
            Map.entry("1'047 Zeilen x 1'000 Felder", ziel -> {
                String kopf = String.join(";", Collections.nCopies(1_000, "S"));
                String zeile = String.join(";", Collections.nCopies(1_000, "€€€€"));
                return schreiben(ziel, kopf + "\n" + (zeile + "\n").repeat(1_047), CP1252);
            }),
            Map.entry("Kopfzeile 1'000 Spalten an der Grenze", ziel -> {
                String name = "€".repeat(CsvTabelle.MAX_KOPF / 1_000 - 1);
                String kopf = String.join(";", Collections.nCopies(1_000, name));
                String zeile = String.join(";", Collections.nCopies(1_000, "x"));
                return schreiben(ziel, kopf + "\n" + (zeile + "\n").repeat(2_000), CP1252);
            }),
            Map.entry("524 Zeilen mit einem Feld von 9'999 Zeichen", ziel -> schreiben(ziel,
                    "Name;Notiz\n" + ("A;" + "€".repeat(FELD_GRENZE - 1) + "\n").repeat(524), CP1252)),
            Map.entry("ein Feld mit 5 Mio. «€»", ziel -> schreiben(ziel,
                    "Name;Notiz\nA;" + "€".repeat(5_000_000) + "\n", CP1252)),
            Map.entry("ein Feld mit 5 Mio. «ä»", ziel -> schreiben(ziel,
                    "Name;Notiz\nA;" + "ä".repeat(5_000_000) + "\n", CP1252)),
            Map.entry("1 Zeile x 520 Felder x 10'000 «€»", ziel -> zeilen(ziel, 1, 520, "€".repeat(FELD_GRENZE))),
            Map.entry("1 Zeile x 520 Felder x 10'000 Steuerzeichen", ziel ->
                    zeilen(ziel, 1, 520, "\u0001".repeat(FELD_GRENZE))),
            Map.entry("5 Zeilen x 104 Felder x 10'000 «€»", ziel -> zeilen(ziel, 5, 104, "€".repeat(FELD_GRENZE))),
            Map.entry("19 Zeilen an der Zeilengrenze («€»)", ziel -> {
                String zeile = String.join(";", Collections.nCopies(26, "€".repeat(FELD_GRENZE))) + ";"
                        + "€".repeat(CsvTabelle.MAX_ZEILE - 26 * FELD_GRENZE);
                return schreiben(ziel, kopf(27) + "\n" + (zeile + "\n").repeat(19), CP1252);
            }),
            Map.entry("24 Zeilen, Block knapp unter 1 Mi Zeichen plus eine Zeile", ziel -> {
                String zeile = String.join(";", Collections.nCopies(20, "€".repeat(FELD_GRENZE))) + ";"
                        + "€".repeat(9_000);
                return schreiben(ziel, kopf(21) + "\n" + (zeile + "\n").repeat(24), CP1252);
            }),
            Map.entry("119 Zeilen an der Zeilengrenze (Steuerzeichen)", ziel ->
                    zeilen(ziel, 119, 5, "\u0001".repeat(CsvTabelle.MAX_ZEILE / 6 / 5))));

    @ParameterizedTest
    @MethodSource("dateien")
    void importBleibtImHeapZiel(String name) throws Exception {
        Path datei = DATEIEN.stream().filter(e -> e.getKey().equals(name)).findFirst().orElseThrow().getValue()
                .apply(ordner.resolve("messung.csv"));
        assertThat(Files.size(datei)).isLessThanOrEqualTo(MAX_GROESSE);

        Process prozess = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-XX:+UseSerialGC", "-Xmn2m", "-Xmx" + XMX_MIB + "m", "-XX:+ExitOnOutOfMemoryError",
                "-cp", klassenpfad(), Lauf.class.getName(), datei.toString(),
                ordner.toString()).redirectErrorStream(true).start();
        String ausgabe = new String(prozess.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(prozess.waitFor(2, TimeUnit.MINUTES)).isTrue();

        assertThat(prozess.exitValue()).as(ausgabe).isZero();
        assertThat(ausgabe).contains("ok");
    }

    /**
     * Fester Klassenpfad der Kind-JVM, nur die Orte der Klassen, die {@link Lauf} lädt: Der Klassenpfad der Tests
     * (Surefire, rund 450 Einträge) brächte das Zentralverzeichnis jedes geöffneten Jars in den Heap, je nach Umgebung
     * verschieden viel (gemessen 12.11.2026, Fall «24 Zeilen …»: 25 MiB auf macOS/arm64, 29 MiB auf Linux/amd64,
     * mit diesem Klassenpfad 9 MiB bzw. 13 MiB). Fehlt hier ein Jar, scheitert die Kind-JVM mit
     * {@code NoClassDefFoundError}, und der Test ist rot.
     */
    private static String klassenpfad() {
        return Stream.of(Lauf.class, CsvImport.class, ObjectMapper.class, com.fasterxml.jackson.core.JsonFactory.class,
                        com.fasterxml.jackson.annotation.JsonProperty.class, org.apache.commons.csv.CSVFormat.class,
                        org.apache.commons.io.IOUtils.class, org.apache.commons.codec.binary.Base64.class,
                        com.ibm.icu.text.CharsetDetector.class, org.jboss.logging.Logger.class,
                        org.assertj.core.api.Assertions.class)
                .map(klasse -> {
                    try {
                        return Path.of(klasse.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
                    } catch (URISyntaxException e) {
                        throw new IllegalStateException(e);
                    }
                })
                .distinct()
                .collect(Collectors.joining(File.pathSeparator));
    }

    /** {@code anzahl} Zeilen mit {@code spalten} gleichen Feldern; Kopfzeile {@code Name;S;S;…}. */
    private static Path zeilen(Path ziel, int anzahl, int spalten, String feld) {
        String zeile = String.join(";", Collections.nCopies(spalten, feld));
        return schreiben(ziel, kopf(spalten) + "\n" + (zeile + "\n").repeat(anzahl), CP1252);
    }

    private static String kopf(int spalten) {
        return "Name" + ";S".repeat(spalten - 1);
    }

    private static Path kopieren(String ressource, Path ziel) {
        try (InputStream eingabe = CsvImportSpeicherTest.class.getResourceAsStream(ressource)) {
            Files.write(ziel, eingabe.readAllBytes());
            return ziel;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Path schreiben(Path ziel, String text, Charset zeichensatz) {
        try {
            return Files.write(ziel, text.getBytes(zeichensatz));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Einstieg der Kind-JVM: {@code <datei> <ordner>}. */
    public static final class Lauf {

        private Lauf() {
        }

        public static void main(String[] args) throws Exception {
            ObjectMapper json = new ObjectMapper();
            try (JsonRepository repository = new JsonRepository(json, Path.of(args[1]).resolve("zeilen.jsonl"))) {
                CsvImport csvImport = new CsvImport(new CommonsCsvLeser(), repository,
                        new ImportEinstellungen(MAX_GROESSE, FELD_GRENZE));
                CsvImport.Hochgeladen hochgeladen = csvImport.hochladen(ImportArt.KONTAKT, false, "messung.csv",
                        Path.of(args[0]), "inhaber");
                String antwort = json.writeValueAsString(Map.of("kopf", hochgeladen.lauf().kopf().namen(),
                        "zeilen", hochgeladen.lauf().zeilen()));
                ImportLauf zugeordnet = csvImport.zuordnen(ImportArt.KONTAKT, hochgeladen.lauf().id(), null,
                        Map.of(ImportFeld.NAME, 0));
                System.out.println("ok zeilen=" + zugeordnet.zeilen() + " fehler=" + zugeordnet.fehler()
                        + " antwort=" + antwort.length());
            }
        }
    }

    /** Hält nur einen Index im Heap; die Zeilen liegen als JSON in einer Datei wie {@code import_zeile.daten}. */
    private static final class JsonRepository implements ImportRepository, AutoCloseable {

        private final ObjectMapper json;
        private final RandomAccessFile datei;
        private final List<long[]> index = new ArrayList<>();
        private final List<String> ergebnisse = new ArrayList<>();
        private ImportLauf lauf;
        private String kopf;
        private GespeicherteZuordnung zuordnung;

        JsonRepository(ObjectMapper json, Path pfad) throws IOException {
            this.json = json;
            this.datei = new RandomAccessFile(pfad.toFile(), "rw");
        }

        @Override
        public void laufAnlegen(ImportLauf lauf) {
            this.lauf = lauf;
            kopf = text(liste(lauf.kopf().namen()));
        }

        @Override
        public void zeilenAnlegen(UUID laufId, List<ImportZeile> zeilen) {
            List<String> block = new ArrayList<>(zeilen.size());
            for (ImportZeile zeile : zeilen) {
                if (zeile.werte() == null) {
                    block.add(null);
                } else {
                    ObjectNode daten = json.createObjectNode();
                    daten.set("werte", liste(zeile.werte()));
                    block.add(text(daten));
                }
            }
            // wie der Batch des PG-Treibers: alle Strings des Blocks und ihre UTF-8-Bytes bis executeBatch
            List<byte[]> gesendet = new ArrayList<>(zeilen.size());
            block.forEach(text -> gesendet.add(text == null ? null : text.getBytes(StandardCharsets.UTF_8)));
            try {
                for (int i = 0; i < zeilen.size(); i++) {
                    byte[] bytes = gesendet.get(i);
                    index.add(new long[]{zeilen.get(i).nr(), datei.getFilePointer(), bytes == null ? -1 : bytes.length,
                            block.get(i) == null ? 0 : block.get(i).length()});
                    ergebnisse.add(zeilen.get(i).ergebnis().name());
                    if (bytes != null) {
                        datei.write(bytes);
                    }
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        @Override
        public void laufAktualisieren(ImportLauf lauf) {
            this.lauf = lauf;
        }

        @Override
        public Optional<ImportLauf> lauf(UUID id) {
            return Optional.of(lauf);
        }

        @Override
        public Optional<ImportLauf> laufSperren(UUID id) {
            try {
                ArrayNode namen = (ArrayNode) json.readTree(kopf);
                assertThat(namen.size()).isEqualTo(lauf.kopf().spalten());
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            return Optional.of(lauf);
        }

        @Override
        public int breite(UUID laufId) {
            return lauf.kopf().spalten();
        }

        @Override
        public List<ImportZeile> zeilen(UUID laufId, int nachNr, int anzahl, int zeichen) {
            // wie das Fenster in JdbcImportRepository (davor < zeichen) und das ResultSet des PG-Treibers: alle
            // Bytes des Fensters liegen im Heap, bevor die erste Zeile geparst wird
            List<Integer> fenster = new ArrayList<>();
            List<byte[]> empfangen = new ArrayList<>();
            long summe = 0;
            List<ImportZeile> zeilen = new ArrayList<>();
            try {
                for (int i = 0; i < index.size() && fenster.size() < anzahl && summe < zeichen; i++) {
                    long[] eintrag = index.get(i);
                    if (eintrag[0] <= nachNr) {
                        continue;
                    }
                    byte[] bytes = null;
                    if (eintrag[2] >= 0) {
                        bytes = new byte[(int) eintrag[2]];
                        datei.seek(eintrag[1]);
                        datei.readFully(bytes);
                    }
                    summe += eintrag[3];
                    fenster.add(i);
                    empfangen.add(bytes);
                }
                for (int j = 0; j < fenster.size(); j++) {
                    int i = fenster.get(j);
                    List<String> werte = null;
                    if (empfangen.get(j) != null) {
                        werte = new ArrayList<>();
                        String text = new String(empfangen.get(j), StandardCharsets.UTF_8);
                        for (JsonNode wert : json.readTree(text).path("werte")) {
                            werte.add(wert.isNull() ? null : wert.asText());
                        }
                    }
                    zeilen.add(new ImportZeile((int) index.get(i)[0], werte, ZeilenPruefung.Ergebnis.valueOf(
                            ergebnisse.get(i)), null));
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            return zeilen;
        }

        @Override
        public void zeilenAktualisieren(UUID laufId, List<ImportZeile> zeilen) {
        }

        @Override
        public Optional<GespeicherteZuordnung> zuordnung(ImportArt art) {
            return Optional.ofNullable(zuordnung);
        }

        @Override
        public void zuordnungSpeichern(GespeicherteZuordnung zuordnung) {
            this.zuordnung = zuordnung;
        }

        @Override
        public void close() throws IOException {
            datei.close();
        }

        private ArrayNode liste(List<String> werte) {
            ArrayNode liste = json.createArrayNode();
            werte.forEach(liste::add);
            return liste;
        }

        private String text(JsonNode knoten) {
            try {
                return json.writeValueAsString(knoten);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }
}
