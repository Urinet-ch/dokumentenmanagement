package ch.urinet.archiv.importe.adapter.out.csv;

import ch.urinet.archiv.gemeinsam.domain.FachFehler;
import ch.urinet.archiv.gemeinsam.domain.Fehlercode;
import ch.urinet.archiv.importe.application.CsvLeser;
import ch.urinet.archiv.importe.domain.CsvTabelle;
import ch.urinet.archiv.importe.domain.Kopfzeile;
import com.ibm.icu.text.CharsetDetector;
import com.ibm.icu.text.CharsetMatch;
import jakarta.enterprise.context.ApplicationScoped;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CoderResult;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Iterator;
import java.util.Set;

/**
 * CSV nach RFC 4180 mit Apache Commons CSV (Req 13.1), streamend: Die Datei wird nie als Ganzes gehalten (Entscheid
 * Fabian 11.11.2026). Zeichensatz und Trennzeichen erkennt das Archiv am Anfang der Datei ({@link #ERKENNUNG} Bytes
 * bzw. Zeichen), danach liest es die Datei zweimal: Die Vorprüfung dekodiert streng und zählt Spalten und Datensätze,
 * ohne Werte anzulegen; erst wenn sie besteht, parst Commons CSV Zeile für Zeile und meldet jede an den Empfänger.
 * <p>
 * Zeichensatz: UTF-8-BOM → UTF-8 (BOM entfernt); sonst gültiges UTF-8; sonst streng Windows-1252. Entschieden wird auf
 * dem ersten Block mit Nicht-ASCII-Bytes ({@link #ERKENNUNG} Bytes am Anfang oder um das erste solche Byte); eine Datei
 * nur aus ASCII ist UTF-8. Abgelehnt werden UTF-16/32 (BOM oder NUL-Bytes), Bytes, die im erkannten Zeichensatz nicht
 * gültig sind (auch nach dem Erkennungsblock), NUL-Zeichen, und Dateien, für die ICU4J auf dem Erkennungsblock mit hoher
 * Konfidenz einen nicht-lateinischen Zeichensatz erkennt (z. B. Big5, EUC-KR, windows-1256). Andere Latin-Kodierungen,
 * die ICU4J für kurze Windows-1252-Dateien oft nennt (ISO-8859-2, windows-1254 …), gelten als Windows-1252.
 * Trennzeichen: das häufigere von {@code ;} und {@code ,} ausserhalb von Anführungszeichen in der Kopfzeile, bei
 * Gleichstand {@code ;} (21.CRM). Mehr als {@link CsvTabelle#MAX_SPALTEN} Spalten in einem Datensatz oder mehr als
 * {@link CsvTabelle#MAX_ZEILEN} Datenzeilen lehnt die Vorprüfung ab. Leere Zeilen zählen nicht; die Nummer einer Zeile
 * ist ihre Datensatznummer (Kopfzeile = 1). Meldungen des Parsers enthalten Dateiinhalt und werden nie weitergegeben
 * oder geloggt.
 */
@ApplicationScoped
public class CommonsCsvLeser implements CsvLeser {

    static final String UTF_8 = "UTF-8";
    static final String WINDOWS_1252 = "windows-1252";
    /** Bytes für die Erkennung des Zeichensatzes und Zeichen für die des Trennzeichens (64 KiB). */
    static final int ERKENNUNG = 64 * 1024;
    private static final Charset CP1252 = Charset.forName(WINDOWS_1252);
    /** Latin-Kodierungen aus ICU4J; jede davon liest das Archiv als Windows-1252 (Req 13.1). */
    private static final Set<String> LATEINISCH = Set.of("ISO-8859-1", "ISO-8859-2", "ISO-8859-9", "windows-1250",
            "windows-1252", "windows-1254");
    /**
     * Ab dieser Konfidenz gilt ein nicht-lateinischer Treffer von ICU4J als sicher. Gemessen mit ICU4J 79.1: auf
     * Windows-1252-Text höchstens 19, auf Big5, EUC-KR, GB18030 und Arabisch 72 bis 100.
     */
    static final int KONFIDENZ_NICHT_LATEINISCH = 50;
    private static final int PUFFER = 8 * 1024;

    @Override
    public void lesen(Path datei, Empfaenger empfaenger) {
        Erkennung erkennung = erkennen(datei);
        vorpruefen(datei, erkennung);
        parsen(datei, erkennung, empfaenger);
    }

    /** Erkannter Zeichensatz, Länge des BOM und Trennzeichen. */
    record Erkennung(String zeichensatz, int bom, char trennzeichen) {
    }

    static Erkennung erkennen(Path datei) {
        String zeichensatz;
        int bom = 0;
        try (InputStream eingabe = new BufferedInputStream(Files.newInputStream(datei), ERKENNUNG)) {
            byte[] anfang = eingabe.readNBytes(ERKENNUNG);
            if (anfang.length == 0) {
                throw new FachFehler(Fehlercode.CSV_LEER);
            }
            if (beginntMit(anfang, 0xEF, 0xBB, 0xBF)) {
                zeichensatz = UTF_8;
                bom = 3;
            } else if (beginntMit(anfang, 0xFF, 0xFE) || beginntMit(anfang, 0xFE, 0xFF) || enthaeltNul(anfang)) {
                throw new FachFehler(Fehlercode.CSV_UNGUELTIG);
            } else if (nichtAscii(anfang) >= 0) {
                zeichensatz = zeichensatz(anfang, anfang.length < ERKENNUNG || eingabe.read() < 0);
            } else {
                zeichensatz = ersterNichtAsciiBlock(datei, eingabe, anfang.length);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("CSV nicht lesbar", e);
        }
        return new Erkennung(zeichensatz, bom, trennzeichen(anfangText(datei, zeichensatz, bom)));
    }

    /**
     * Nach einem reinen ASCII-Anfang: Block von {@link #ERKENNUNG} Bytes um das erste Byte ≥ 0x80 (die Hälfte davor als
     * Kontext für ICU4J); eine Datei nur aus ASCII ist UTF-8.
     */
    private static String ersterNichtAsciiBlock(Path datei, InputStream eingabe, long gelesen) throws IOException {
        long position = gelesen;
        for (int zeichen; (zeichen = eingabe.read()) >= 0; position++) {
            if (zeichen == 0) {
                throw new FachFehler(Fehlercode.CSV_UNGUELTIG);
            }
            if (zeichen >= 0x80) {
                long start = Math.max(0, position - ERKENNUNG / 2);
                try (SeekableByteChannel kanal = Files.newByteChannel(datei)) {
                    ByteBuffer block = ByteBuffer.allocate(ERKENNUNG);
                    kanal.position(start);
                    while (block.hasRemaining() && kanal.read(block) > 0) {
                        // weiterlesen, bis der Block voll oder die Datei zu Ende ist
                    }
                    return zeichensatz(Arrays.copyOf(block.array(), block.position()),
                            start + block.position() >= kanal.size());
                }
            }
        }
        return UTF_8;
    }

    /**
     * Zeichensatz eines Blocks: gültiges UTF-8 (eine am Blockende abgeschnittene Sequenz zählt nur am Dateiende als
     * Fehler), sonst Windows-1252, wenn alle Bytes belegt sind und ICU4J keinen nicht-lateinischen Zeichensatz sicher
     * erkennt; sonst {@code CSV_UNGUELTIG}.
     */
    static String zeichensatz(byte[] block, boolean dateiende) {
        if (gueltig(block, StandardCharsets.UTF_8, dateiende)) {
            return UTF_8;
        }
        if (!gueltig(block, CP1252, true) || nichtLateinisch(block)) {
            throw new FachFehler(Fehlercode.CSV_UNGUELTIG);
        }
        return WINDOWS_1252;
    }

    private static boolean gueltig(byte[] block, Charset zeichensatz, boolean dateiende) {
        CharsetDecoder decoder = strikt(zeichensatz);
        ByteBuffer eingabe = ByteBuffer.wrap(block);
        CharBuffer ausgabe = CharBuffer.allocate(block.length + 1);
        CoderResult ergebnis = decoder.decode(eingabe, ausgabe, dateiende);
        if (ergebnis.isError()) {
            return false;
        }
        return !dateiende || !decoder.flush(ausgabe).isError();
    }

    private static boolean nichtLateinisch(byte[] block) {
        CharsetDetector erkennung = new CharsetDetector();
        erkennung.setText(block);
        CharsetMatch treffer = erkennung.detect();
        return treffer != null && !LATEINISCH.contains(treffer.getName())
                && treffer.getConfidence() >= KONFIDENZ_NICHT_LATEINISCH;
    }

    private static String anfangText(Path datei, String zeichensatz, int bom) {
        char[] puffer = new char[ERKENNUNG];
        int gelesen = 0;
        try (Reader leser = leser(datei, zeichensatz, bom)) {
            for (int n; gelesen < puffer.length && (n = leser.read(puffer, gelesen, puffer.length - gelesen)) > 0; ) {
                gelesen += n;
            }
        } catch (CharacterCodingException e) {
            throw new FachFehler(Fehlercode.CSV_UNGUELTIG);
        } catch (IOException e) {
            throw new UncheckedIOException("CSV nicht lesbar", e);
        }
        return new String(puffer, 0, gelesen);
    }

    /**
     * Häufigeres Trennzeichen der Kopfzeile ausserhalb von Anführungszeichen; Gleichstand → {@code ;}. Wie bei RFC 4180
     * beginnt ein zitiertes Feld nur mit einem {@code "} am Feldanfang; {@code ""} darin ist ein Anführungszeichen.
     */
    static char trennzeichen(String text) {
        int semikolon = 0;
        int komma = 0;
        boolean zitiert = false;
        boolean feldanfang = true;
        boolean begonnen = false;
        for (int i = 0; i < text.length(); i++) {
            char zeichen = text.charAt(i);
            if (zitiert) {
                if (zeichen == '"') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '"') {
                        i++;
                    } else {
                        zitiert = false;
                    }
                }
                continue;
            }
            if (zeichen == '\n' || zeichen == '\r') {
                if (begonnen) {
                    break;
                }
                continue;
            }
            begonnen = true;
            if (zeichen == '"' && feldanfang) {
                zitiert = true;
                feldanfang = false;
            } else if (zeichen == ';' || zeichen == ',') {
                semikolon += zeichen == ';' ? 1 : 0;
                komma += zeichen == ',' ? 1 : 0;
                feldanfang = true;
            } else {
                feldanfang = false;
            }
        }
        return komma > semikolon ? ',' : ';';
    }

    /**
     * Durchgang 1 ohne Werte: dekodiert die ganze Datei streng und zählt nach RFC 4180 die Spalten jedes Datensatzes
     * und die Datensätze. Leere Zeilen zählen nicht. {@code CSV_LEER} ohne Datensatz; {@code CSV_UNGUELTIG} bei
     * ungültigem Byte, NUL-Zeichen, mehr als {@link CsvTabelle#MAX_SPALTEN} Spalten, mehr als
     * {@link CsvTabelle#MAX_ZEILEN} Datenzeilen oder einem nicht geschlossenen Anführungszeichen am Dateiende.
     */
    static void vorpruefen(Path datei, Erkennung erkennung) {
        Zaehler zaehler = new Zaehler(erkennung.trennzeichen());
        char[] puffer = new char[PUFFER];
        try (Reader leser = leser(datei, erkennung.zeichensatz(), erkennung.bom())) {
            for (int n; (n = leser.read(puffer)) > 0; ) {
                for (int i = 0; i < n; i++) {
                    zaehler.zeichen(puffer[i]);
                }
            }
        } catch (CharacterCodingException e) {
            throw new FachFehler(Fehlercode.CSV_UNGUELTIG);
        } catch (IOException e) {
            throw new UncheckedIOException("CSV nicht lesbar", e);
        }
        zaehler.ende();
    }

    /** Zustandsautomat der Vorprüfung; verarbeitet ein Zeichen nach dem anderen (auch über Puffergrenzen). */
    private static final class Zaehler {

        private final char trennzeichen;
        private int datensaetze;
        private int spalten = 1;
        private boolean leer = true;
        private boolean zitiert;
        private boolean anfuehrungImZitat;
        private boolean feldanfang = true;

        Zaehler(char trennzeichen) {
            this.trennzeichen = trennzeichen;
        }

        void zeichen(char zeichen) {
            if (zeichen == 0) {
                throw new FachFehler(Fehlercode.CSV_UNGUELTIG);
            }
            if (anfuehrungImZitat) {
                anfuehrungImZitat = false;
                if (zeichen == '"') {
                    return;
                }
                zitiert = false;
            } else if (zitiert) {
                anfuehrungImZitat = zeichen == '"';
                return;
            }
            if (zeichen == '\n' || zeichen == '\r') {
                datensatzEnde();
                return;
            }
            leer = false;
            if (zeichen == '"' && feldanfang) {
                zitiert = true;
                feldanfang = false;
            } else if (zeichen == trennzeichen) {
                if (++spalten > CsvTabelle.MAX_SPALTEN) {
                    throw new FachFehler(Fehlercode.CSV_UNGUELTIG);
                }
                feldanfang = true;
            } else {
                feldanfang = false;
            }
        }

        private void datensatzEnde() {
            if (!leer && ++datensaetze > CsvTabelle.MAX_ZEILEN + 1) {
                throw new FachFehler(Fehlercode.CSV_UNGUELTIG);
            }
            spalten = 1;
            leer = true;
            feldanfang = true;
        }

        void ende() {
            if (zitiert && !anfuehrungImZitat) {
                throw new FachFehler(Fehlercode.CSV_UNGUELTIG);
            }
            datensatzEnde();
            if (datensaetze == 0) {
                throw new FachFehler(Fehlercode.CSV_LEER);
            }
        }
    }

    /**
     * Durchgang 2: parst Zeile für Zeile und meldet Kopf und Datenzeilen. Nur Fehler des Parsers werden zu
     * {@code CSV_UNGUELTIG}; Fehler des Empfängers (z. B. der Datenbank) gehen unverändert weiter.
     */
    private static void parsen(Path datei, Erkennung erkennung, Empfaenger empfaenger) {
        CSVFormat format = CSVFormat.RFC4180.builder().setDelimiter(erkennung.trennzeichen())
                .setIgnoreEmptyLines(true).get();
        try (Reader leser = new BufferedReader(leser(datei, erkennung.zeichensatz(), erkennung.bom()), PUFFER);
             CSVParser parser = CSVParser.builder().setReader(leser).setFormat(format).get()) {
            Iterator<CSVRecord> datensaetze = parser.iterator();
            CSVRecord kopf = naechster(datensaetze);
            if (kopf == null) {
                throw new FachFehler(Fehlercode.CSV_LEER);
            }
            empfaenger.kopf(erkennung.zeichensatz(), erkennung.trennzeichen(), new Kopfzeile(kopf.toList()));
            int zeilen = 0;
            for (CSVRecord datensatz; (datensatz = naechster(datensaetze)) != null; ) {
                if (++zeilen > CsvTabelle.MAX_ZEILEN) {
                    throw new FachFehler(Fehlercode.CSV_UNGUELTIG);
                }
                empfaenger.zeile(new CsvTabelle.Zeile(Math.toIntExact(datensatz.getRecordNumber()),
                        datensatz.toList()));
            }
        } catch (IOException e) {
            throw new FachFehler(Fehlercode.CSV_UNGUELTIG);
        }
    }

    private static CSVRecord naechster(Iterator<CSVRecord> datensaetze) {
        try {
            return datensaetze.hasNext() ? datensaetze.next() : null;
        } catch (UncheckedIOException | IllegalStateException e) {
            throw new FachFehler(Fehlercode.CSV_UNGUELTIG);
        }
    }

    private static Reader leser(Path datei, String zeichensatz, int bom) throws IOException {
        InputStream eingabe = Files.newInputStream(datei);
        try {
            eingabe.skipNBytes(bom);
        } catch (IOException e) {
            eingabe.close();
            throw e;
        }
        Charset charset = UTF_8.equals(zeichensatz) ? StandardCharsets.UTF_8 : CP1252;
        return new InputStreamReader(eingabe, strikt(charset));
    }

    private static CharsetDecoder strikt(Charset zeichensatz) {
        return zeichensatz.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
    }

    private static boolean beginntMit(byte[] inhalt, int... bytes) {
        if (inhalt.length < bytes.length) {
            return false;
        }
        for (int i = 0; i < bytes.length; i++) {
            if ((inhalt[i] & 0xFF) != bytes[i]) {
                return false;
            }
        }
        return true;
    }

    private static boolean enthaeltNul(byte[] inhalt) {
        for (byte b : inhalt) {
            if (b == 0) {
                return true;
            }
        }
        return false;
    }

    private static int nichtAscii(byte[] inhalt) {
        for (int i = 0; i < inhalt.length; i++) {
            if (inhalt[i] < 0) {
                return i;
            }
        }
        return -1;
    }
}
