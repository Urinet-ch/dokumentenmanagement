package ch.urinet.archiv.importe.application;

import ch.urinet.archiv.gemeinsam.domain.FachFehler;
import ch.urinet.archiv.gemeinsam.domain.Fehlercode;
import ch.urinet.archiv.importe.domain.CsvTabelle;
import ch.urinet.archiv.importe.domain.GespeicherteZuordnung;
import ch.urinet.archiv.importe.domain.ImportArt;
import ch.urinet.archiv.importe.domain.ImportLauf;
import ch.urinet.archiv.importe.domain.ImportZeile;
import ch.urinet.archiv.importe.domain.Kopfzeile;
import ch.urinet.archiv.importe.domain.LaufStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Grössengrenze vor dem Parser und Speichern in Blöcken (Req 13.10, Entscheid Fabian 11.11.2026). */
class CsvImportTest {

    private static final long GRENZE = 5L * 1024 * 1024;

    @TempDir
    Path ordner;

    private final ZaehlenderLeser leser = new ZaehlenderLeser();
    private final MerkendesRepository repository = new MerkendesRepository();
    private final CsvImport csvImport = new CsvImport(leser, repository, new ImportEinstellungen(GRENZE, 10_000));

    @Test
    void dateiUeberDerGrenzeWirdOhneParserAbgelehnt() throws IOException {
        Path datei = Files.write(ordner.resolve("gross.csv"), new byte[(int) GRENZE + 1]);

        assertThatThrownBy(() -> csvImport.hochladen(ImportArt.KONTAKT, false, "gross.csv", datei, "inhaber"))
                .isInstanceOf(FachFehler.class).extracting(f -> ((FachFehler) f).code())
                .isEqualTo(Fehlercode.DATEI_ZU_GROSS);
        assertThat(leser.aufrufe).isZero();
        assertThat(repository.laeufe).isEmpty();
    }

    @Test
    void dateiGenauAnDerGrenzeGehtAnDenParser() throws IOException {
        Path datei = Files.write(ordner.resolve("grenze.csv"), new byte[(int) GRENZE]);
        leser.zeilen = 1;

        CsvImport.Hochgeladen ergebnis = csvImport.hochladen(ImportArt.KONTAKT, false, "grenze.csv", datei, "inhaber");

        assertThat(leser.aufrufe).isOne();
        assertThat(ergebnis.lauf().zeilen()).isOne();
    }

    @Test
    void zeilenWerdenInBloeckenGespeichert() throws IOException {
        Path datei = Files.write(ordner.resolve("k.csv"), new byte[]{'x'});
        leser.zeilen = 2 * CsvImport.STAPEL + 1;

        CsvImport.Hochgeladen ergebnis = csvImport.hochladen(ImportArt.KONTAKT, false, "k.csv", datei, "inhaber");

        assertThat(repository.bloecke).containsExactly(CsvImport.STAPEL, CsvImport.STAPEL, 1);
        assertThat(repository.laeufe).hasSize(1);
        assertThat(repository.aktualisiert.zeilen()).isEqualTo(2 * CsvImport.STAPEL + 1);
        assertThat(repository.aktualisiert.status()).isEqualTo(LaufStatus.ZUORDNUNG_NOETIG);
        assertThat(ergebnis.lauf()).isEqualTo(repository.aktualisiert);
    }

    @Test
    void breiteZeilenBegrenzenDenBlockNachWerten() throws IOException {
        Path datei = Files.write(ordner.resolve("k.csv"), new byte[]{'x'});
        leser.spalten = 1_000;
        leser.zeilen = 120;

        csvImport.hochladen(ImportArt.KONTAKT, false, "k.csv", datei, "inhaber");

        assertThat(repository.bloecke).containsExactly(50, 50, 20);
        assertThat(CsvImport.stapel(1_000)).isEqualTo(50);
        assertThat(CsvImport.stapel(151)).isEqualTo(331);
        assertThat(CsvImport.stapel(0)).isEqualTo(CsvImport.STAPEL);
    }

    @Test
    void nurKopfzeileIstLeer() throws IOException {
        Path datei = Files.write(ordner.resolve("k.csv"), new byte[]{'x'});

        assertThatThrownBy(() -> csvImport.hochladen(ImportArt.KONTAKT, false, "k.csv", datei, "inhaber"))
                .isInstanceOf(FachFehler.class).extracting(f -> ((FachFehler) f).code())
                .isEqualTo(Fehlercode.CSV_LEER);
        assertThat(repository.aktualisiert).isNull();
    }

    private static final class ZaehlenderLeser implements CsvLeser {

        int aufrufe;
        int zeilen;
        int spalten = 2;

        @Override
        public void lesen(Path datei, Empfaenger empfaenger) {
            aufrufe++;
            List<String> namen = IntStream.range(0, spalten).mapToObj(i -> "S" + i).toList();
            empfaenger.kopf("UTF-8", ';', new Kopfzeile(namen));
            for (int i = 0; i < zeilen; i++) {
                empfaenger.zeile(new CsvTabelle.Zeile(i + 2, Collections.nCopies(spalten, "N" + i)));
            }
        }
    }

    private static final class MerkendesRepository implements ImportRepository {

        final List<ImportLauf> laeufe = new ArrayList<>();
        final List<Integer> bloecke = new ArrayList<>();
        ImportLauf aktualisiert;

        @Override
        public void laufAnlegen(ImportLauf lauf) {
            laeufe.add(lauf);
        }

        @Override
        public void zeilenAnlegen(UUID laufId, List<ImportZeile> zeilen) {
            bloecke.add(zeilen.size());
        }

        @Override
        public void laufAktualisieren(ImportLauf lauf) {
            aktualisiert = lauf;
        }

        @Override
        public Optional<ImportLauf> lauf(UUID id) {
            return Optional.empty();
        }

        @Override
        public Optional<ImportLauf> laufSperren(UUID id) {
            return Optional.empty();
        }

        @Override
        public int breite(UUID laufId) {
            return 0;
        }

        @Override
        public List<ImportZeile> zeilen(UUID laufId, int nachNr, int anzahl) {
            return List.of();
        }

        @Override
        public void zeilenAktualisieren(UUID laufId, List<ImportZeile> zeilen) {
        }

        @Override
        public Optional<GespeicherteZuordnung> zuordnung(ImportArt art) {
            return Optional.empty();
        }

        @Override
        public void zuordnungSpeichern(GespeicherteZuordnung zuordnung) {
        }
    }
}
