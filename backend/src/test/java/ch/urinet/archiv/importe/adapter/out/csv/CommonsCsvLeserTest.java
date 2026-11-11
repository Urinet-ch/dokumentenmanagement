package ch.urinet.archiv.importe.adapter.out.csv;

import ch.urinet.archiv.gemeinsam.domain.FachFehler;
import ch.urinet.archiv.gemeinsam.domain.Fehlercode;
import ch.urinet.archiv.importe.application.CsvLeser;
import ch.urinet.archiv.importe.domain.CsvTabelle;
import ch.urinet.archiv.importe.domain.ImportArt;
import ch.urinet.archiv.importe.domain.ImportFeld;
import ch.urinet.archiv.importe.domain.Kopfzeile;
import ch.urinet.archiv.importe.domain.ZeilenPruefung;
import ch.urinet.archiv.importe.domain.Zuordnung;
import ch.urinet.archiv.importe.domain.Zuordnungsvorschlag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Zeichensatz, Trennzeichen und RFC 4180 (Req 13.1, 13.10, 13.11) mit den Beispieldateien aus Aufgabe 0.4 und daraus
 * abgeleiteten oder synthetischen Varianten. Werte der Beispieldateien werden nie ausgegeben, nur verglichen.
 */
class CommonsCsvLeserTest {

    private static final Charset CP1252 = Charset.forName("windows-1252");
    private static final int GRENZE = 10_000;
    private final CommonsCsvLeser leser = new CommonsCsvLeser();

    @TempDir
    Path ordner;

    @Test
    void beispielKontakteWindows1252MitSemikolon() {
        Tabelle tabelle = lesen(datei("csv/21crm-kontakte-beispiel.csv"));

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
        Tabelle tabelle = lesen(datei("csv/21crm-kontakte-beispiel.csv"));
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
    void beispielVerkaufschancenNurKopfzeileOhneProfil() {
        Tabelle tabelle = lesen(datei("csv/21crm-verkaufschancen-beispiel.csv"));

        assertThat(tabelle.kopf().spalten()).isEqualTo(32);
        assertThat(tabelle.zeilen()).isEmpty();
        assertThat(Zuordnungsvorschlag.profil21Crm(ImportArt.VERKAUFSCHANCE, tabelle.kopf())).isEmpty();
        Zuordnung profil = Zuordnungsvorschlag.fuer(ImportArt.VERKAUFSCHANCE, tabelle.kopf());
        assertThat(profil.profil()).isNull();
        assertThat(profil.felder()).containsOnlyKeys(ImportFeld.CRM_ID, ImportFeld.KONTAKT_CRM_ID, ImportFeld.TITEL,
                ImportFeld.PHASE, ImportFeld.BETRAG, ImportFeld.DATUM, ImportFeld.GELOESCHT);
    }

    @Test
    void utf8MitUndOhneBomErgibtDieselbenWerte() {
        byte[] original = datei("csv/21crm-kontakte-beispiel.csv");
        Tabelle cp1252 = lesen(original);
        byte[] ohneBom = new String(original, CP1252).getBytes(StandardCharsets.UTF_8);
        byte[] mitBom = new byte[ohneBom.length + 3];
        mitBom[0] = (byte) 0xEF;
        mitBom[1] = (byte) 0xBB;
        mitBom[2] = (byte) 0xBF;
        System.arraycopy(ohneBom, 0, mitBom, 3, ohneBom.length);

        Tabelle utf8 = lesen(mitBom);

        assertThat(utf8.zeichensatz()).isEqualTo("UTF-8");
        assertThat(utf8.kopf()).isEqualTo(cp1252.kopf());
        assertThat(utf8.zeilen()).isEqualTo(cp1252.zeilen());
        assertThat(lesen(ohneBom).zeichensatz()).isEqualTo("UTF-8");
        assertThat(lesen(ohneBom).zeilen()).isEqualTo(cp1252.zeilen());
    }

    @Test
    void kommaUndCrlfAusBeispiel() {
        Tabelle semikolon = lesen(datei("csv/21crm-kontakte-beispiel.csv"));
        StringBuilder text = new StringBuilder(zeile(semikolon.kopf().namen(), ','));
        semikolon.zeilen().forEach(z -> text.append(zeile(z.werte(), ',')));

        Tabelle komma = lesen(text.toString().getBytes(CP1252));

        assertThat(komma.trennzeichen()).isEqualTo(',');
        assertThat(komma.zeichensatz()).isEqualTo("windows-1252");
        assertThat(komma.kopf()).isEqualTo(semikolon.kopf());
        assertThat(komma.zeilen()).isEqualTo(semikolon.zeilen());
    }

    @Test
    void kurzeDateiInWindows1252() {
        Tabelle tabelle = lesen("Name;Ort\nMüller AG;Zürich\nGrüter;Bürglen\n".getBytes(CP1252));

        assertThat(tabelle.zeichensatz()).isEqualTo("windows-1252");
        assertThat(tabelle.zeilen().getFirst().werte()).containsExactly("Müller AG", "Zürich");
    }

    @Test
    void kurzeWindows1252DateienMitAnderemIcuTrefferBleibenWindows1252() {
        for (String zeile : List.of("A;Schönenberg", "Café Crème;Genève", "Škoda Garage;Bern", "Niño Peña;Lugano",
                "Gül;Yilmaz", "Grüter;Bürglen", "Müller AG;Zürich")) {
            Tabelle tabelle = lesen(("Name;Ort\n" + zeile + "\n").getBytes(CP1252));

            assertThat(tabelle.zeichensatz()).as("Zeile %s", zeile).isEqualTo("windows-1252");
            assertThat(String.join(";", tabelle.zeilen().getFirst().werte())).isEqualTo(zeile);
        }
    }

    @Test
    void varianteDerKontaktBeispieldateiBleibenWindows1252() {
        String text = new String(datei("csv/21crm-kontakte-beispiel.csv"), CP1252);
        List<String> zeilen = text.lines().toList();
        List<String> varianten = new ArrayList<>();
        for (int i = 1; i < zeilen.size(); i++) {
            varianten.add(zeilen.getFirst() + "\n" + zeilen.get(i) + "\n");
            varianten.add(String.join("\n", zeilen.subList(0, i + 1)) + "\n");
            varianten.add(String.join("\n", zeilen.subList(i, zeilen.size())) + "\n");
        }
        for (int laenge = 40; laenge < text.length(); laenge += 97) {
            varianten.add(text.substring(0, laenge));
        }
        for (String variante : varianten) {
            byte[] inhalt = variante.getBytes(CP1252);
            boolean nurAscii = variante.chars().allMatch(c -> c < 0x80);

            assertThat(CommonsCsvLeser.zeichensatz(inhalt, true)).as("Variante der Länge %d", inhalt.length)
                    .isEqualTo(nurAscii ? "UTF-8" : "windows-1252");
        }
    }

    @Test
    void synthetischeSchweizerDateienBleibenWindows1252() {
        List<String> woerter = List.of("Müller", "Zürich", "Bürglen", "Göschenen", "Altdorf", "Genève", "Neuchâtel",
                "Délémont", "Biel/Bienne", "Café", "Crème", "Fâcheux", "Château-d'Œx", "Lugano", "Niño", "Peña",
                "Gül", "Škoda", "Ærø", "Straße", "Hôtel", "Säntis", "Flüelen", "Erstfeld", "Seedorf", "Gärtnerei",
                "Bäckerei", "Malerei", "AG", "GmbH", "Gebr.", "Söhne", "Œuvre", "François", "Jürg", "Rüegg", "Kälin",
                "Föhn", "Spiringen", "Bürgi", "6460", "6454", "1204", "€ 120.50", "Tel. 041 870 00 00", "",
                "«Zitat»", "Nr. 5", "Ü-Wagen", "Ölwechsel", "Ärzte", "José", "Zoë", "Müsli", "Brünig");
        Random zufall = new Random(4711);
        for (int datei = 0; datei < 2_000; datei++) {
            int spalten = 2 + zufall.nextInt(5);
            int zeilen = 1 + zufall.nextInt(5);
            StringBuilder text = new StringBuilder();
            for (int z = 0; z <= zeilen; z++) {
                for (int s = 0; s < spalten; s++) {
                    text.append(s > 0 ? ";" : "").append(woerter.get(zufall.nextInt(woerter.size())));
                    if (zufall.nextInt(3) == 0) {
                        text.append(' ').append(woerter.get(zufall.nextInt(woerter.size())));
                    }
                }
                text.append(zufall.nextBoolean() ? "\n" : "\r\n");
            }
            byte[] inhalt = text.toString().getBytes(CP1252);

            assertThat(CommonsCsvLeser.zeichensatz(inhalt, true)).as("Datei %d", datei)
                    .isEqualTo("windows-1252");
        }
    }

    @Test
    void nichtLateinischeDateienWerdenAbgelehnt() {
        List<byte[]> ungueltig = List.of(
                "姓名;城市\n張偉;台北\n王芳;高雄\n".getBytes(Charset.forName("Big5")),
                "姓名;城市\n张伟;北京\n王芳;上海\n".getBytes(Charset.forName("GB18030")),
                "이름;도시\n김민수;서울\n이영희;부산\n".getBytes(Charset.forName("EUC-KR")),
                "الاسم;المدينة\nمحمد;القاهرة\nأحمد;دبي\n".getBytes(Charset.forName("windows-1256")),
                "名前;都市\n田中;東京\n鈴木;大阪\n".getBytes(Charset.forName("Shift_JIS")),
                mitPraefix("Name;Ort\nA;".getBytes(CP1252), new byte[]{(byte) 0x81, '\n'}));
        for (byte[] inhalt : ungueltig) {
            assertThatThrownBy(() -> lesen(inhalt)).isInstanceOf(FachFehler.class)
                    .extracting(f -> ((FachFehler) f).code()).isEqualTo(Fehlercode.CSV_UNGUELTIG);
        }
    }

    @Test
    void mehrAlsTausendSpaltenWerdenVorDemParsenAbgelehnt() {
        String tausend = ";".repeat(CsvTabelle.MAX_SPALTEN - 1);
        assertThat(lesen(("N" + tausend + "\nA" + tausend + "\n").getBytes(StandardCharsets.UTF_8)).kopf()
                .spalten()).isEqualTo(CsvTabelle.MAX_SPALTEN);
        assertThat(lesen(("N;O\nA;\"" + ";".repeat(5_000) + "\"\n").getBytes(StandardCharsets.UTF_8)).zeilen()
                .getFirst().werte()).containsExactly("A", ";".repeat(5_000));
        for (String text : List.of("N" + tausend + ";\nA\n", "N;O\nA" + tausend + ";\n",
                "N;O\n\nA;B\n" + ";".repeat(1_000_000) + "\n")) {
            assertThatThrownBy(() -> lesen(text.getBytes(StandardCharsets.UTF_8))).isInstanceOf(FachFehler.class)
                    .extracting(f -> ((FachFehler) f).code()).isEqualTo(Fehlercode.CSV_UNGUELTIG);
        }
    }

    @Test
    void anfuehrungszeichenMittenImFeldBeginntKeinZitat() {
        String text = "Name,Firma \"X;Ort;PLZ;Land;Kanton\nA,B Y;Altdorf;6460;CH;UR\n";

        Tabelle tabelle = lesen(text.getBytes(StandardCharsets.UTF_8));

        assertThat(CommonsCsvLeser.trennzeichen(text)).isEqualTo(';');
        assertThat(tabelle.trennzeichen()).isEqualTo(';');
        assertThat(tabelle.kopf().spalten()).isEqualTo(5);
        assertThat(tabelle.zeilen().getFirst().werte()).hasSize(5);
    }

    @Test
    void feldInAnfuehrungszeichenMitSemikolonUndZeilenumbruch() {
        String text = "Name;Notiz;PLZ\n\"Muster; AG\";\"erste Zeile\nzweite \"\"Zeile\"\"\";6000\nTest GmbH;;6460\n";

        Tabelle tabelle = lesen(text.getBytes(StandardCharsets.UTF_8));

        assertThat(tabelle.trennzeichen()).isEqualTo(';');
        assertThat(tabelle.zeilen()).extracting(CsvTabelle.Zeile::nr).containsExactly(2, 3);
        assertThat(tabelle.zeilen().getFirst().werte())
                .containsExactly("Muster; AG", "erste Zeile\nzweite \"Zeile\"", "6000");
    }

    @Test
    void falscheSpaltenzahlBleibtZeileMitIhrenWerten() {
        Tabelle tabelle = lesen("Name;PLZ\nA;6000;zu viel\nB\n".getBytes(StandardCharsets.UTF_8));

        assertThat(tabelle.zeilen()).extracting(z -> z.werte().size()).containsExactly(3, 1);
    }

    @Test
    void leereZeilenZaehlenNicht() {
        Tabelle tabelle = lesen("\nName;PLZ\n\nA;6000\n\n".getBytes(StandardCharsets.UTF_8));

        assertThat(tabelle.kopf().namen()).containsExactly("Name", "PLZ");
        assertThat(tabelle.zeilen()).hasSize(1);
    }

    @Test
    void formelnBleibenUnveraendert() {
        Tabelle tabelle = lesen("Name;Telefon\n=1+1;+41 41 000 00 00\n@SUM(A1);-5\n"
                .getBytes(StandardCharsets.UTF_8));

        assertThat(tabelle.zeilen()).extracting(CsvTabelle.Zeile::werte).containsExactly(
                List.of("=1+1", "+41 41 000 00 00"), List.of("@SUM(A1)", "-5"));
    }

    @Test
    void nurKopfzeileHatKeineDatenzeilen() {
        assertThat(lesen(datei("csv/nur-kopfzeile.csv")).zeilen()).isEmpty();
    }

    @Test
    void leereDateiIstLeer() {
        for (byte[] inhalt : List.of(new byte[0], "\n\n".getBytes(StandardCharsets.UTF_8))) {
            assertThatThrownBy(() -> lesen(inhalt)).isInstanceOf(FachFehler.class)
                    .extracting(f -> ((FachFehler) f).code()).isEqualTo(Fehlercode.CSV_LEER);
        }
    }

    @Test
    void zehntausendDatenzeilenGehenMehrNicht() {
        assertThat(lesen(zeilen(CsvTabelle.MAX_ZEILEN)).zeilen()).hasSize(CsvTabelle.MAX_ZEILEN);
        assertThatThrownBy(() -> lesen(zeilen(CsvTabelle.MAX_ZEILEN + 1))).isInstanceOf(FachFehler.class)
                .extracting(f -> ((FachFehler) f).code()).isEqualTo(Fehlercode.CSV_UNGUELTIG);
    }

    @Test
    void riesigesFeldKommtAufGrenzePlusEinsGekuerztUndBleibtUeberDerGrenze() {
        String riesig = "x".repeat(200_000);

        Tabelle tabelle = lesen(("Name;Notiz;PLZ\nA;\"" + riesig + "\";6000\nB;" + riesig + ";6460\n")
                .getBytes(StandardCharsets.UTF_8));

        assertThat(tabelle.zeilen()).extracting(CsvTabelle.Zeile::nr).containsExactly(2, 3);
        assertThat(tabelle.zeilen()).allSatisfy(z -> {
            assertThat(z.werte()).hasSize(3);
            assertThat(z.werte().get(1)).isEqualTo("x".repeat(GRENZE + 1));
        });
        assertThat(tabelle.zeilen()).extracting(z -> z.werte().get(2)).containsExactly("6000", "6460");
    }

    @Test
    void kuerzenZaehltCodepointsUndVerwirftDoppelteAnfuehrungszeichenAlsPaar() {
        String emoji = "\uD83D\uDE00";
        String zitiert = "\"\"".repeat(GRENZE) + "\"\";\nx";
        String text = "A;B;C\n" + "\"" + emoji.repeat(GRENZE + 5) + "\";\"" + zitiert + "\";\"kurz \"\"x\"\"\"\n"
                + "ä".repeat(GRENZE) + ";" + "ä".repeat(GRENZE + 1) + ";z\n";

        Tabelle tabelle = lesen(text.getBytes(StandardCharsets.UTF_8));

        assertThat(tabelle.zeilen()).extracting(CsvTabelle.Zeile::nr).containsExactly(2, 3);
        List<String> erste = tabelle.zeilen().getFirst().werte();
        assertThat(erste.get(0)).isEqualTo(emoji.repeat(GRENZE + 1));
        assertThat(erste.get(1)).isEqualTo("\"".repeat(GRENZE + 1));
        assertThat(erste.get(2)).isEqualTo("kurz \"x\"");
        assertThat(tabelle.zeilen().getLast().werte())
                .containsExactly("ä".repeat(GRENZE), "ä".repeat(GRENZE + 1), "z");
    }

    @Test
    void kuerzenLaesstFormatfehlerNachDemZitatStehen() {
        String lang = "x".repeat(GRENZE + 10);

        for (String zeile : List.of("\"abc\"x;1", "\"" + lang + "\"x;1")) {
            assertThatThrownBy(() -> lesen(("A;B\n" + zeile + "\n").getBytes(StandardCharsets.UTF_8)))
                    .isInstanceOf(FachFehler.class).extracting(f -> ((FachFehler) f).code())
                    .isEqualTo(Fehlercode.CSV_UNGUELTIG);
        }
        assertThat(lesen(("A;B\n\"" + lang + "\"  ;1\n").getBytes(StandardCharsets.UTF_8)).zeilen().getFirst()
                .werte()).containsExactly("x".repeat(GRENZE + 1), "1");
    }

    @Test
    void spaltennameUeber256ZeichenWirdAbgelehnt() {
        String erlaubt = "€".repeat(CsvTabelle.MAX_KOPF_NAME);
        String zitiert = "\"" + "\"\"".repeat(CsvTabelle.MAX_KOPF_NAME) + "\"";

        assertThat(lesen(("A;" + erlaubt + ";" + zitiert + "\n1;2;3\n").getBytes(StandardCharsets.UTF_8)).kopf()
                .namen()).containsExactly("A", erlaubt, "\"".repeat(CsvTabelle.MAX_KOPF_NAME));
        for (String name : List.of(erlaubt + "€", "\"" + erlaubt + "€\"", "\uD83D\uDE00".repeat(257))) {
            Sammler sammler = new Sammler();
            byte[] inhalt = ("A;" + name + "\n1;2\n").getBytes(StandardCharsets.UTF_8);
            assertThatThrownBy(() -> leser.lesen(schreiben(inhalt), GRENZE, sammler)).isInstanceOf(FachFehler.class)
                    .extracting(f -> ((FachFehler) f).code()).isEqualTo(Fehlercode.CSV_UNGUELTIG);
            assertThat(sammler.kopf).isNull();
        }
        assertThat(lesen(("A\n" + "x".repeat(CsvTabelle.MAX_KOPF_NAME + 1) + "\n").getBytes(StandardCharsets.UTF_8))
                .zeilen()).hasSize(1);
    }

    @Test
    void kopfzeileUeber64KiZeichenWirdAbgelehnt() {
        String name = "€".repeat(CsvTabelle.MAX_KOPF_NAME - 1);
        int spalten = CsvTabelle.MAX_KOPF / CsvTabelle.MAX_KOPF_NAME;
        String kopf = String.join(";", java.util.Collections.nCopies(spalten, name));
        String genau = kopf + "x".repeat(CsvTabelle.MAX_KOPF - kopf.length());
        assertThat(genau).hasSize(CsvTabelle.MAX_KOPF);

        assertThat(lesen((genau + "\r\n1\r\n").getBytes(StandardCharsets.UTF_8)).kopf().spalten()).isEqualTo(spalten);
        Sammler sammler = new Sammler();
        byte[] zuLang = (genau + ";\n1\n").getBytes(CP1252);
        assertThatThrownBy(() -> leser.lesen(schreiben(zuLang), GRENZE, sammler)).isInstanceOf(FachFehler.class)
                .extracting(f -> ((FachFehler) f).code()).isEqualTo(Fehlercode.CSV_UNGUELTIG);
        assertThat(sammler.kopf).isNull();
    }

    @Test
    void ungueltigeDateienWerdenAbgelehnt() {
        List<byte[]> ungueltig = List.of(
                "Name;PLZ\nA;6000\n".getBytes(StandardCharsets.UTF_16LE),
                mitPraefix(new byte[]{(byte) 0xFF, (byte) 0xFE}, "Name;PLZ\nA;6000\n".getBytes(StandardCharsets.UTF_16LE)),
                "Name;PLZ\nA;\"offen\n".getBytes(StandardCharsets.UTF_8),
                mitPraefix(new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF}, "N;O\nM\u00fcller;x\n".getBytes(CP1252)));
        for (byte[] inhalt : ungueltig) {
            assertThatThrownBy(() -> lesen(inhalt)).isInstanceOf(FachFehler.class)
                    .extracting(f -> ((FachFehler) f).code()).isEqualTo(Fehlercode.CSV_UNGUELTIG);
        }
    }

    /** Gesammelte Meldungen des Lesers. */
    record Tabelle(String zeichensatz, char trennzeichen, Kopfzeile kopf, List<CsvTabelle.Zeile> zeilen) {
    }

    private static final class Sammler implements CsvLeser.Empfaenger {

        private String zeichensatz;
        private char trennzeichen;
        private Kopfzeile kopf;
        private final List<CsvTabelle.Zeile> zeilen = new ArrayList<>();

        @Override
        public void kopf(String zeichensatz, char trennzeichen, Kopfzeile kopf) {
            assertThat(this.kopf).as("Kopfzeile nur einmal").isNull();
            this.zeichensatz = zeichensatz;
            this.trennzeichen = trennzeichen;
            this.kopf = kopf;
        }

        @Override
        public void zeile(CsvTabelle.Zeile zeile) {
            assertThat(kopf).as("Kopfzeile vor den Datenzeilen").isNotNull();
            zeilen.add(zeile);
        }
    }

    private Path schreiben(byte[] inhalt) {
        try {
            return Files.write(ordner.resolve(UUID.randomUUID() + ".csv"), inhalt);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private Tabelle lesen(byte[] inhalt) {
        Sammler sammler = new Sammler();
        leser.lesen(schreiben(inhalt), GRENZE, sammler);
        return new Tabelle(sammler.zeichensatz, sammler.trennzeichen, sammler.kopf, sammler.zeilen);
    }

    @Test
    void zeichensatzEntscheidetDerErsteBlockMitNichtAscii() {
        String ascii = "Name;Ort\n" + "Meier;Altdorf\n".repeat(9_999);
        assertThat(ascii.length()).isGreaterThan(2 * CommonsCsvLeser.ERKENNUNG);

        Tabelle cp1252 = lesen((ascii + "Müller;Zürich\n").getBytes(CP1252));
        Tabelle utf8 = lesen((ascii + "Müller;Zürich\n").getBytes(StandardCharsets.UTF_8));

        assertThat(cp1252.zeichensatz()).isEqualTo("windows-1252");
        assertThat(utf8.zeichensatz()).isEqualTo("UTF-8");
        assertThat(cp1252.zeilen().getLast().werte()).containsExactly("Müller", "Zürich");
        assertThat(utf8.zeilen()).isEqualTo(cp1252.zeilen());
    }

    @Test
    void ungueltigesByteNachDemErkennungsblockLehntAbOhneZeileZuMelden() {
        String anfang = "Name;Ort\nMüller;Zürich\n" + "Meier;Altdorf\n".repeat(10_000);
        byte[] gemischt = mitPraefix(anfang.getBytes(StandardCharsets.UTF_8), "Grüter;Bürglen\n".getBytes(CP1252));
        Sammler sammler = new Sammler();

        assertThatThrownBy(() -> leser.lesen(schreiben(gemischt), GRENZE, sammler)).isInstanceOf(FachFehler.class)
                .extracting(f -> ((FachFehler) f).code()).isEqualTo(Fehlercode.CSV_UNGUELTIG);
        assertThat(sammler.kopf).isNull();
        assertThat(sammler.zeilen).isEmpty();
    }

    @Test
    void zuVieleZeilenWerdenAbgelehntBevorEineZeileGemeldetWird() {
        Sammler sammler = new Sammler();

        assertThatThrownBy(() -> leser.lesen(schreiben(zeilen(CsvTabelle.MAX_ZEILEN + 1)), GRENZE, sammler))
                .isInstanceOf(FachFehler.class);
        assertThat(sammler.kopf).isNull();
    }

    @Test
    void fehlerDesEmpfaengersBleibenUnveraendert() {
        IllegalStateException datenbank = new IllegalStateException("Datenbank");
        CsvLeser.Empfaenger empfaenger = new CsvLeser.Empfaenger() {
            @Override
            public void kopf(String zeichensatz, char trennzeichen, Kopfzeile kopf) {
            }

            @Override
            public void zeile(CsvTabelle.Zeile zeile) {
                throw datenbank;
            }
        };

        assertThatThrownBy(() -> leser.lesen(schreiben("Name\nA\n".getBytes(StandardCharsets.UTF_8)), GRENZE, empfaenger))
                .isSameAs(datenbank);
    }

    @Test
    void nulZeichenAuchMitBomUngueltig() {
        byte[] inhalt = mitPraefix(new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF},
                "Name;PLZ\nA\u0000;6000\n".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> lesen(inhalt)).isInstanceOf(FachFehler.class)
                .extracting(f -> ((FachFehler) f).code()).isEqualTo(Fehlercode.CSV_UNGUELTIG);
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
