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

import java.io.IOException;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/**
 * CSV nach RFC 4180 mit Apache Commons CSV (Req 13.1). Zeichensatz: UTF-8-BOM → UTF-8 (BOM entfernt); sonst gültiges
 * UTF-8; sonst streng Windows-1252. Abgelehnt werden UTF-16/32 (BOM oder NUL-Bytes), Bytes, die in Windows-1252 nicht
 * belegt sind, und Dateien, für die ICU4J mit hoher Konfidenz einen nicht-lateinischen Zeichensatz erkennt (z. B. Big5,
 * EUC-KR, windows-1256). Andere Latin-Kodierungen, die ICU4J für kurze Windows-1252-Dateien oft nennt (ISO-8859-2,
 * windows-1254 …), gelten als Windows-1252. Trennzeichen: das häufigere von {@code ;} und {@code ,} ausserhalb von
 * Anführungszeichen in der Kopfzeile, bei Gleichstand {@code ;} (21.CRM). Vor dem Parsen zählt das Archiv die Spalten
 * jedes Datensatzes; mehr als {@link CsvTabelle#MAX_SPALTEN} lehnt es ab, ohne die Werte anzulegen. Leere Zeilen
 * zählen nicht; die Nummer einer Zeile ist ihre Datensatznummer (Kopfzeile = 1). Meldungen des Parsers enthalten
 * Dateiinhalt und werden nie weitergegeben oder geloggt.
 */
@ApplicationScoped
public class CommonsCsvLeser implements CsvLeser {

    static final String UTF_8 = "UTF-8";
    static final String WINDOWS_1252 = "windows-1252";
    /** Latin-Kodierungen aus ICU4J; jede davon liest das Archiv als Windows-1252 (Req 13.1). */
    private static final Set<String> LATEINISCH = Set.of("ISO-8859-1", "ISO-8859-2", "ISO-8859-9", "windows-1250",
            "windows-1252", "windows-1254");
    /**
     * Ab dieser Konfidenz gilt ein nicht-lateinischer Treffer von ICU4J als sicher. Gemessen mit ICU4J 79.1: auf
     * Windows-1252-Text höchstens 19, auf Big5, EUC-KR, GB18030 und Arabisch 72 bis 100.
     */
    static final int KONFIDENZ_NICHT_LATEINISCH = 50;

    @Override
    public CsvTabelle lesen(byte[] inhalt) {
        if (inhalt == null || inhalt.length == 0) {
            throw new FachFehler(Fehlercode.CSV_LEER);
        }
        Text text = dekodieren(inhalt);
        char trennzeichen = trennzeichen(text.inhalt());
        if (meisteSpalten(text.inhalt(), trennzeichen) > CsvTabelle.MAX_SPALTEN) {
            throw new FachFehler(Fehlercode.CSV_UNGUELTIG);
        }
        CSVFormat format = CSVFormat.RFC4180.builder().setDelimiter(trennzeichen).setIgnoreEmptyLines(true).get();
        try (CSVParser parser = CSVParser.builder().setReader(new StringReader(text.inhalt())).setFormat(format).get()) {
            Iterator<CSVRecord> datensaetze = parser.iterator();
            if (!datensaetze.hasNext()) {
                throw new FachFehler(Fehlercode.CSV_LEER);
            }
            Kopfzeile kopf = new Kopfzeile(datensaetze.next().toList());
            List<CsvTabelle.Zeile> zeilen = new ArrayList<>();
            while (datensaetze.hasNext()) {
                CSVRecord datensatz = datensaetze.next();
                if (zeilen.size() == CsvTabelle.MAX_ZEILEN) {
                    throw new FachFehler(Fehlercode.CSV_UNGUELTIG);
                }
                zeilen.add(new CsvTabelle.Zeile(Math.toIntExact(datensatz.getRecordNumber()), datensatz.toList()));
            }
            return new CsvTabelle(text.zeichensatz(), trennzeichen, kopf, zeilen);
        } catch (IOException | UncheckedIOException | IllegalStateException e) {
            throw new FachFehler(Fehlercode.CSV_UNGUELTIG);
        }
    }

    record Text(String inhalt, String zeichensatz) {
    }

    static Text dekodieren(byte[] inhalt) {
        if (beginntMit(inhalt, 0xEF, 0xBB, 0xBF)) {
            String text = strikt(inhalt, 3, StandardCharsets.UTF_8);
            if (text == null) {
                throw new FachFehler(Fehlercode.CSV_UNGUELTIG);
            }
            return new Text(text, UTF_8);
        }
        if (beginntMit(inhalt, 0xFF, 0xFE) || beginntMit(inhalt, 0xFE, 0xFF) || enthaeltNul(inhalt)) {
            throw new FachFehler(Fehlercode.CSV_UNGUELTIG);
        }
        String utf8 = strikt(inhalt, 0, StandardCharsets.UTF_8);
        if (utf8 != null) {
            return new Text(utf8, UTF_8);
        }
        String cp1252 = strikt(inhalt, 0, Charset.forName(WINDOWS_1252));
        if (cp1252 == null || nichtLateinisch(inhalt)) {
            throw new FachFehler(Fehlercode.CSV_UNGUELTIG);
        }
        return new Text(cp1252, WINDOWS_1252);
    }

    private static boolean nichtLateinisch(byte[] inhalt) {
        CharsetDetector erkennung = new CharsetDetector();
        erkennung.setText(inhalt);
        CharsetMatch treffer = erkennung.detect();
        return treffer != null && !LATEINISCH.contains(treffer.getName())
                && treffer.getConfidence() >= KONFIDENZ_NICHT_LATEINISCH;
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
     * Höchste Spaltenzahl eines Datensatzes nach RFC 4180, ohne Werte anzulegen; bricht ab, sobald
     * {@link CsvTabelle#MAX_SPALTEN} überschritten ist. Leere Zeilen zählen nicht.
     */
    static int meisteSpalten(String text, char trennzeichen) {
        int meiste = 0;
        int spalten = 1;
        boolean leer = true;
        boolean zitiert = false;
        boolean feldanfang = true;
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
                if (!leer) {
                    meiste = Math.max(meiste, spalten);
                }
                spalten = 1;
                leer = true;
                feldanfang = true;
                continue;
            }
            leer = false;
            if (zeichen == '"' && feldanfang) {
                zitiert = true;
                feldanfang = false;
            } else if (zeichen == trennzeichen) {
                if (++spalten > CsvTabelle.MAX_SPALTEN) {
                    return spalten;
                }
                feldanfang = true;
            } else {
                feldanfang = false;
            }
        }
        return leer ? meiste : Math.max(meiste, spalten);
    }

    private static String strikt(byte[] inhalt, int start, Charset zeichensatz) {
        try {
            return zeichensatz.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(inhalt, start, inhalt.length - start)).toString();
        } catch (CharacterCodingException e) {
            return null;
        }
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
}
