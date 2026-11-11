package ch.urinet.archiv.importe.adapter.out.csv;

import ch.urinet.archiv.gemeinsam.domain.FachFehler;
import ch.urinet.archiv.gemeinsam.domain.Fehlercode;
import ch.urinet.archiv.importe.domain.CsvTabelle;
import ch.urinet.archiv.importe.domain.ImportArt;
import ch.urinet.archiv.importe.domain.ImportFeld;
import ch.urinet.archiv.importe.domain.Kopfzeile;
import ch.urinet.archiv.importe.domain.ZeilenPruefung;
import ch.urinet.archiv.importe.domain.Zuordnung;
import ch.urinet.archiv.importe.domain.Zuordnungsvorschlag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Zeichensatz, Trennzeichen und RFC 4180 (Req 13.1, 13.10, 13.11) mit den Beispieldateien aus Aufgabe 0.4 und daraus
 * abgeleiteten oder synthetischen Varianten. Werte der Beispieldateien werden nie ausgegeben, nur verglichen.
 */
class CommonsCsvLeserTest {

    private static final Charset CP1252 = Charset.forName("windows-1252");
    private final CommonsCsvLeser leser = new CommonsCsvLeser();

    @Test
    void beispielKontakteWindows1252MitSemikolon() {
        CsvTabelle tabelle = leser.lesen(datei("csv/21crm-kontakte-beispiel.csv"));

        assertThat(tabelle.zeichensatz()).isEqualTo("windows-1252");
        assertThat(tabelle.trennzeichen()).isEqualTo(';');
        assertThat(tabelle.kopf().spalten()).isEqualTo(151);
        assertThat(tabelle.zeilen()).hasSize(5);
        assertThat(tabelle.zeilen()).extracting(CsvTabelle.Zeile::nr).containsExactly(2, 3, 4, 5, 6);
        assertThat(tabelle.zeilen()).allSatisfy(z -> assertThat(z.werte()).hasSize(151));
        assertThat(tabelle.kopf().namen()).contains("Straße (Geschäft)", "Ist gelöscht", "Telefon (Geschäft)");
    }

    @Test
    void beispielKontakteErgibtProfil21CrmOhneFehlerhafteZeilen() {
        CsvTabelle tabelle = leser.lesen(datei("csv/21crm-kontakte-beispiel.csv"));
        Kopfzeile kopf = tabelle.kopf();

        Zuordnung profil = Zuordnungsvorschlag.fuer(ImportArt.KONTAKT, kopf);

        assertThat(profil.profil()).isEqualTo(Zuordnung.PROFIL_21CRM);
        assertThat(profil.felder()).containsEntry(ImportFeld.CRM_ID, kopf.position("GGUID"))
                .containsEntry(ImportFeld.TYP, kopf.position("Kontakt ist eine Organisation"))
                .containsEntry(ImportFeld.NAME, kopf.position("Name"))
                .containsEntry(ImportFeld.ORGANISATION, kopf.position("Organisation"))
                .containsEntry(ImportFeld.STRASSE, kopf.position("Straße (Geschäft)"))
                .containsEntry(ImportFeld.GELOESCHT, kopf.position("Ist gelöscht"))
                .containsEntry(ImportFeld.STANDARD_TELEFON, kopf.position("Standard-Telefon 1"))
                .hasSize(18);
        assertThat(profil.mangel(ImportArt.KONTAKT, kopf.spalten())).isEmpty();
        assertThat(tabelle.zeilen()).allSatisfy(z -> {
            assertThat(profil.werte(ImportArt.KONTAKT, z.werte())).containsKey(ImportFeld.NAME);
            assertThat(ZeilenPruefung.pruefen(ImportArt.KONTAKT, profil, z.werte(), kopf.spalten(), 10_000)
                    .ergebnis()).isEqualTo(ZeilenPruefung.Ergebnis.OFFEN);
        });
    }

    @Test
    void beispielVerkaufschancenNurKopfzeileMitProfil() {
        CsvTabelle tabelle = leser.lesen(datei("csv/21crm-verkaufschancen-beispiel.csv"));

        assertThat(tabelle.kopf().spalten()).isEqualTo(32);
        assertThat(tabelle.zeilen()).isEmpty();
        Zuordnung profil = Zuordnungsvorschlag.fuer(ImportArt.VERKAUFSCHANCE, tabelle.kopf());
        assertThat(profil.profil()).isEqualTo(Zuordnung.PROFIL_21CRM);
        assertThat(profil.felder()).containsOnlyKeys(ImportFeld.CRM_ID, ImportFeld.KONTAKT_CRM_ID, ImportFeld.TITEL,
                ImportFeld.PHASE, ImportFeld.BETRAG, ImportFeld.DATUM, ImportFeld.GELOESCHT);
    }

    @Test
    void utf8MitUndOhneBomErgibtDieselbenWerte() {
        byte[] original = datei("csv/21crm-kontakte-beispiel.csv");
        CsvTabelle cp1252 = leser.lesen(original);
        byte[] ohneBom = new String(original, CP1252).getBytes(StandardCharsets.UTF_8);
        byte[] mitBom = new byte[ohneBom.length + 3];
        mitBom[0] = (byte) 0xEF;
        mitBom[1] = (byte) 0xBB;
        mitBom[2] = (byte) 0xBF;
        System.arraycopy(ohneBom, 0, mitBom, 3, ohneBom.length);

        CsvTabelle utf8 = leser.lesen(mitBom);

        assertThat(utf8.zeichensatz()).isEqualTo("UTF-8");
        assertThat(utf8.kopf()).isEqualTo(cp1252.kopf());
        assertThat(utf8.zeilen()).isEqualTo(cp1252.zeilen());
        assertThat(leser.lesen(ohneBom).zeichensatz()).isEqualTo("UTF-8");
        assertThat(leser.lesen(ohneBom).zeilen()).isEqualTo(cp1252.zeilen());
    }

    @Test
    void kommaUndCrlfAusBeispiel() {
        CsvTabelle semikolon = leser.lesen(datei("csv/21crm-kontakte-beispiel.csv"));
        StringBuilder text = new StringBuilder(zeile(semikolon.kopf().namen(), ','));
        semikolon.zeilen().forEach(z -> text.append(zeile(z.werte(), ',')));

        CsvTabelle komma = leser.lesen(text.toString().getBytes(CP1252));

        assertThat(komma.trennzeichen()).isEqualTo(',');
        assertThat(komma.zeichensatz()).isEqualTo("windows-1252");
        assertThat(komma.kopf()).isEqualTo(semikolon.kopf());
        assertThat(komma.zeilen()).isEqualTo(semikolon.zeilen());
    }

    @Test
    void kurzeDateiInWindows1252() {
        CsvTabelle tabelle = leser.lesen("Name;Ort\nMüller AG;Zürich\nGrüter;Bürglen\n".getBytes(CP1252));

        assertThat(tabelle.zeichensatz()).isEqualTo("windows-1252");
        assertThat(tabelle.zeilen().getFirst().werte()).containsExactly("Müller AG", "Zürich");
    }

    @Test
    void feldInAnfuehrungszeichenMitSemikolonUndZeilenumbruch() {
        String text = "Name;Notiz;PLZ\n\"Muster; AG\";\"erste Zeile\nzweite \"\"Zeile\"\"\";6000\nTest GmbH;;6460\n";

        CsvTabelle tabelle = leser.lesen(text.getBytes(StandardCharsets.UTF_8));

        assertThat(tabelle.trennzeichen()).isEqualTo(';');
        assertThat(tabelle.zeilen()).extracting(CsvTabelle.Zeile::nr).containsExactly(2, 3);
        assertThat(tabelle.zeilen().getFirst().werte())
                .containsExactly("Muster; AG", "erste Zeile\nzweite \"Zeile\"", "6000");
    }

    @Test
    void falscheSpaltenzahlBleibtZeileMitIhrenWerten() {
        CsvTabelle tabelle = leser.lesen("Name;PLZ\nA;6000;zu viel\nB\n".getBytes(StandardCharsets.UTF_8));

        assertThat(tabelle.zeilen()).extracting(z -> z.werte().size()).containsExactly(3, 1);
    }

    @Test
    void leereZeilenZaehlenNicht() {
        CsvTabelle tabelle = leser.lesen("\nName;PLZ\n\nA;6000\n\n".getBytes(StandardCharsets.UTF_8));

        assertThat(tabelle.kopf().namen()).containsExactly("Name", "PLZ");
        assertThat(tabelle.zeilen()).hasSize(1);
    }

    @Test
    void formelnBleibenUnveraendert() {
        CsvTabelle tabelle = leser.lesen("Name;Telefon\n=1+1;+41 41 000 00 00\n@SUM(A1);-5\n"
                .getBytes(StandardCharsets.UTF_8));

        assertThat(tabelle.zeilen()).extracting(CsvTabelle.Zeile::werte).containsExactly(
                List.of("=1+1", "+41 41 000 00 00"), List.of("@SUM(A1)", "-5"));
    }

    @Test
    void nurKopfzeileHatKeineDatenzeilen() {
        assertThat(leser.lesen(datei("csv/nur-kopfzeile.csv")).zeilen()).isEmpty();
    }

    @Test
    void leereDateiIstLeer() {
        for (byte[] inhalt : List.of(new byte[0], "\n\n".getBytes(StandardCharsets.UTF_8))) {
            assertThatThrownBy(() -> leser.lesen(inhalt)).isInstanceOf(FachFehler.class)
                    .extracting(f -> ((FachFehler) f).code()).isEqualTo(Fehlercode.CSV_LEER);
        }
    }

    @Test
    void zehntausendDatenzeilenGehenMehrNicht() {
        assertThat(leser.lesen(zeilen(CsvTabelle.MAX_ZEILEN)).zeilen()).hasSize(CsvTabelle.MAX_ZEILEN);
        assertThatThrownBy(() -> leser.lesen(zeilen(CsvTabelle.MAX_ZEILEN + 1))).isInstanceOf(FachFehler.class)
                .extracting(f -> ((FachFehler) f).code()).isEqualTo(Fehlercode.CSV_UNGUELTIG);
    }

    @Test
    void riesigesFeldWirdGelesen() {
        String riesig = "x".repeat(200_000);

        CsvTabelle tabelle = leser.lesen(("Name;Notiz\nA;\"" + riesig + "\"\n").getBytes(StandardCharsets.UTF_8));

        assertThat(tabelle.zeilen().getFirst().werte().get(1)).hasSize(200_000);
    }

    @Test
    void ungueltigeDateienWerdenAbgelehnt() {
        List<byte[]> ungueltig = List.of(
                "Name;PLZ\nA;6000\n".getBytes(StandardCharsets.UTF_16LE),
                mitPraefix(new byte[]{(byte) 0xFF, (byte) 0xFE}, "Name;PLZ\nA;6000\n".getBytes(StandardCharsets.UTF_16LE)),
                "Name;PLZ\nA;\"offen\n".getBytes(StandardCharsets.UTF_8),
                mitPraefix(new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF}, "N;O\nM\u00fcller;x\n".getBytes(CP1252)));
        for (byte[] inhalt : ungueltig) {
            assertThatThrownBy(() -> leser.lesen(inhalt)).isInstanceOf(FachFehler.class)
                    .extracting(f -> ((FachFehler) f).code()).isEqualTo(Fehlercode.CSV_UNGUELTIG);
        }
    }

    private static byte[] mitPraefix(byte[] praefix, byte[] text) {
        byte[] ergebnis = new byte[praefix.length + text.length];
        System.arraycopy(praefix, 0, ergebnis, 0, praefix.length);
        System.arraycopy(text, 0, ergebnis, praefix.length, text.length);
        return ergebnis;
    }

    private static byte[] zeilen(int anzahl) {
        StringBuilder text = new StringBuilder("Name;PLZ\n");
        for (int i = 0; i < anzahl; i++) {
            text.append('N').append(i).append(";6000\n");
        }
        return text.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static String zeile(List<String> werte, char trenner) {
        StringBuilder zeile = new StringBuilder();
        for (int i = 0; i < werte.size(); i++) {
            if (i > 0) {
                zeile.append(trenner);
            }
            String wert = werte.get(i);
            boolean zitieren = wert.indexOf(trenner) >= 0 || wert.indexOf('"') >= 0 || wert.indexOf('\n') >= 0
                    || wert.indexOf('\r') >= 0;
            zeile.append(zitieren ? '"' + wert.replace("\"", "\"\"") + '"' : wert);
        }
        return zeile.append("\r\n").toString();
    }

    static byte[] datei(String pfad) {
        try (InputStream inhalt = CommonsCsvLeserTest.class.getClassLoader().getResourceAsStream(pfad)) {
            if (inhalt == null) {
                throw new IllegalStateException("Testdatei fehlt: " + pfad);
            }
            return inhalt.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
