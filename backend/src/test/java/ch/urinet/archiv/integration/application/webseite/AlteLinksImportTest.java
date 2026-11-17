package ch.urinet.archiv.integration.application.webseite;

import ch.urinet.archiv.gemeinsam.domain.FachFehler;
import ch.urinet.archiv.gemeinsam.domain.Fehlercode;
import ch.urinet.archiv.importe.adapter.out.csv.CommonsCsvLeser;
import ch.urinet.archiv.importe.application.ImportEinstellungen;
import ch.urinet.archiv.protokoll.domain.ZugriffEintrag;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AlteLinksImportTest {

    private static final UUID DOKUMENT = UUID.fromString("055104af-5c0c-4c69-a2d5-30750a88555d");
    private static final UUID NICHT_ARCHIVIERT = UUID.fromString("48b516bc-1c35-4c0c-b27f-b2296383bf88");
    private static final UUID ANDERES_DOKUMENT = UUID.fromString("9abfca9b-0c39-4ab8-a730-c7376c001cbe");

    @TempDir
    Path verzeichnis;

    private final TestPort port = new TestPort();
    private final List<ZugriffEintrag> protokoll = new ArrayList<>();
    private AlteLinksImport importer;

    @BeforeEach
    void vorbereiten() {
        port.dokumente.put(DOKUMENT, "ARCHIVIERT");
        port.dokumente.put(ANDERES_DOKUMENT, "ARCHIVIERT");
        port.dokumente.put(NICHT_ARCHIVIERT, "NEU");
        importer = new AlteLinksImport(new CommonsCsvLeser(), port, new ImportEinstellungen(5 * 1024 * 1024, 10_000),
                protokoll::add);
    }

    @Test
    void trockenlaufSchreibtNichtsUndAusfuehrungIstIdempotent() throws Exception {
        Path datei = csv("\uFEFFalte_url;dokument_id\r\n\"/alt/füsse;heft\";" + DOKUMENT + "\r\n");

        AlteLinksImport.Bericht vorschau = importer.importieren(datei, false, null);

        assertThat(vorschau.angelegt()).isEqualTo(1);
        assertThat(vorschau.zeilen()).singleElement().satisfies(zeile -> {
            assertThat(zeile.url()).isEqualTo("/alt/füsse;heft");
            assertThat(zeile.status()).isEqualTo(AlteLinksImport.Status.ANGELEGT);
        });
        assertThat(port.zuordnungen).isEmpty();
        assertThat(protokoll).isEmpty();

        AlteLinksImport.Anlass anlass = new AlteLinksImport.Anlass("synthetisch", "Importtest");
        AlteLinksImport.Bericht ausgefuehrt = importer.importieren(datei, true, anlass);
        AlteLinksImport.Bericht erneut = importer.importieren(datei, true, anlass);

        assertThat(ausgefuehrt.angelegt()).isEqualTo(1);
        assertThat(erneut.schonVorhanden()).isEqualTo(1);
        assertThat(port.zuordnungen).containsEntry("/alt/füsse;heft", DOKUMENT);
        assertThat(protokoll).singleElement().satisfies(eintrag -> {
            assertThat(eintrag.benutzer()).contains("cli:synthetisch");
            assertThat(eintrag.details().grund()).isEqualTo("Importtest");
            assertThat(eintrag.dokumentId()).contains(DOKUMENT);
        });
    }

    @Test
    void mehrfachNormalisierteUrlsVerwerfenDieGanzeDatei() throws Exception {
        Path datei = csv("alte_url,dokument_id\n/alt//doppelt/," + DOKUMENT + "\n/alt/doppelt,"
                + ANDERES_DOKUMENT + "\n");

        AlteLinksImport.Bericht bericht = importer.importieren(datei, true,
                new AlteLinksImport.Anlass("synthetisch", "Importtest"));

        assertThat(bericht.abgelehnt()).isEqualTo(2);
        assertThat(bericht.zeilen()).allSatisfy(zeile -> {
            assertThat(zeile.status()).isEqualTo(AlteLinksImport.Status.ABGELEHNT);
            assertThat(zeile.url()).isEqualTo("/alt/doppelt");
        });
        assertThat(port.zuordnungen).isEmpty();
        assertThat(protokoll).isEmpty();
    }

    @Test
    void widerspruchZuEinerBestehendenZuordnungWirdAbgelehnt() throws Exception {
        port.zuordnungen.put("/alt/bestehend", ANDERES_DOKUMENT);
        Path datei = csv("alte_url,dokument_id\n/alt/bestehend," + DOKUMENT + "\n");

        AlteLinksImport.Bericht bericht = importer.importieren(datei, true,
                new AlteLinksImport.Anlass("synthetisch", "Importtest"));

        assertThat(bericht.abgelehnt()).isEqualTo(1);
        assertThat(bericht.zeilen().getFirst().grund())
                .isEqualTo("Alte URL ist bereits einem anderen Dokument zugeordnet");
        assertThat(port.zuordnungen).containsEntry("/alt/bestehend", ANDERES_DOKUMENT);
        assertThat(protokoll).isEmpty();
    }

    @Test
    void mehrzeiligesFeldBleibtEinDatensatzUndVerhindertTeilimport() throws Exception {
        Path datei = csv("alte_url,dokument_id\n\"/alt/erste\nzweite\"," + DOKUMENT + "\n/alt/gueltig," + DOKUMENT + "\n");

        AlteLinksImport.Bericht bericht = importer.importieren(datei, true,
                new AlteLinksImport.Anlass("synthetisch", "Importtest"));

        assertThat(bericht.zeilen()).hasSize(2);
        assertThat(bericht.zeilen().get(0).zeile()).isEqualTo(2);
        assertThat(bericht.zeilen().get(0).url()).isNull();
        assertThat(bericht.zeilen().get(0).grund()).isEqualTo("alte_url ist ungültig");
        assertThat(bericht.zeilen().get(1).zeile()).isEqualTo(3);
        assertThat(bericht.zeilen().get(1).status()).isEqualTo(AlteLinksImport.Status.ABGELEHNT);
        assertThat(port.zuordnungen).isEmpty();
        assertThat(protokoll).isEmpty();
    }

    @Test
    void fehlendeOderNichtArchivierteZieldokumenteWerdenAbgelehnt() throws Exception {
        UUID fehlt = UUID.fromString("e5c7b5af-ae4c-4e31-b07f-327c875d0e4b");
        Path datei = csv("alte_url;dokument_id\n/alt/fehlt;" + fehlt + "\n/alt/neu;" + NICHT_ARCHIVIERT + "\n");

        AlteLinksImport.Bericht bericht = importer.importieren(datei, true,
                new AlteLinksImport.Anlass("synthetisch", "Importtest"));

        assertThat(bericht.abgelehnt()).isEqualTo(2);
        assertThat(bericht.zeilen()).extracting(AlteLinksImport.ZeilenErgebnis::grund)
                .containsExactly("Zieldokument existiert nicht im Mandanten", "Zieldokument ist nicht archiviert");
        assertThat(port.zuordnungen).isEmpty();
        assertThat(protokoll).isEmpty();
    }

    @Test
    void nichtKanonischeUuidVerhindertDenGesamtenImport() throws Exception {
        String grossgeschrieben = DOKUMENT.toString().toUpperCase(Locale.ROOT);
        Path datei = csv("alte_url,dokument_id\n/alt/uuid," + grossgeschrieben + "\n/alt/gueltig," + DOKUMENT + "\n");

        AlteLinksImport.Bericht bericht = importer.importieren(datei, true,
                new AlteLinksImport.Anlass("synthetisch", "Importtest"));

        assertThat(bericht.abgelehnt()).isEqualTo(2);
        assertThat(bericht.zeilen().getFirst().grund()).isEqualTo("dokument_id ist ungültig");
        assertThat(port.zuordnungen).isEmpty();
        assertThat(protokoll).isEmpty();
    }

    @Test
    void csvGroesseUndZeilenlimitWerdenVorDemSchreibenGeprueft() throws Exception {
        Path zuGross = csv("alte_url,dokument_id\n/alt/x," + DOKUMENT + "\n");
        AlteLinksImport zuGrossImporter = new AlteLinksImport(new CommonsCsvLeser(), port,
                new ImportEinstellungen(4, 10_000), protokoll::add);

        assertThatThrownBy(() -> zuGrossImporter.importieren(zuGross, true,
                new AlteLinksImport.Anlass("synthetisch", "Importtest")))
                .isInstanceOf(FachFehler.class).extracting(fehler -> ((FachFehler) fehler).code())
                .isEqualTo(Fehlercode.DATEI_ZU_GROSS);

        StringBuilder vieleZeilen = new StringBuilder("alte_url,dokument_id\n");
        for (int i = 0; i <= 10_000; i++) {
            vieleZeilen.append("/alt/").append(i).append(',').append(DOKUMENT).append('\n');
        }
        Path zuViele = csv(vieleZeilen.toString());

        assertThatThrownBy(() -> importer.importieren(zuViele, true,
                new AlteLinksImport.Anlass("synthetisch", "Importtest")))
                .isInstanceOf(FachFehler.class).extracting(fehler -> ((FachFehler) fehler).code())
                .isEqualTo(Fehlercode.CSV_UNGUELTIG);
        assertThat(port.zuordnungen).isEmpty();
        assertThat(protokoll).isEmpty();
    }

    private Path csv(String text) throws Exception {
        Path datei = Files.createTempFile(verzeichnis, "alte-links-", ".csv");
        Files.writeString(datei, text);
        return datei;
    }

    private static final class TestPort implements AlteLinksImportPort {
        private final Map<UUID, String> dokumente = new HashMap<>();
        private final Map<String, UUID> zuordnungen = new HashMap<>();

        @Override
        public Map<UUID, String> dokumentstatus(List<UUID> ids) {
            Map<UUID, String> vorhanden = new HashMap<>();
            ids.forEach(id -> {
                if (dokumente.containsKey(id)) {
                    vorhanden.put(id, dokumente.get(id));
                }
            });
            return vorhanden;
        }

        @Override
        public Map<String, UUID> zuordnungen(Set<String> urls) {
            Map<String, UUID> vorhanden = new HashMap<>();
            urls.forEach(url -> {
                if (zuordnungen.containsKey(url)) {
                    vorhanden.put(url, zuordnungen.get(url));
                }
            });
            return vorhanden;
        }

        @Override
        public void anlegen(List<Zuordnung> neueZuordnungen) {
            neueZuordnungen.forEach(zuordnung -> zuordnungen.put(zuordnung.url(), zuordnung.dokumentId()));
        }
    }
}
