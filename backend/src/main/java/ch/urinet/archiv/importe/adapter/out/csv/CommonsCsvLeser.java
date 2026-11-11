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
 * UTF-8; sonst Windows-1252, wenn ICU4J eine Latin-1-Kodierung erkennt und die Bytes in Windows-1252 gültig sind. UTF-16,
 * NUL-Bytes und andere Kodierungen werden abgelehnt. Trennzeichen: das häufigere von {@code ;} und {@code ,} ausserhalb
 * von Anführungszeichen in der Kopfzeile, bei Gleichstand {@code ;} (21.CRM). Leere Zeilen zählen nicht; die Nummer
 * einer Zeile ist ihre Datensatznummer (Kopfzeile = 1). Meldungen des Parsers enthalten Dateiinhalt und werden nie
 * weitergegeben oder geloggt.
 */
@ApplicationScoped
public class CommonsCsvLeser implements CsvLeser {

    static final String UTF_8 = "UTF-8";
    static final String WINDOWS_1252 = "windows-1252";
    private static final Set<String> LATIN_1 = Set.of("ISO-8859-1", "windows-1252");

    @Override
    public CsvTabelle lesen(byte[] inhalt) {
        if (inhalt == null || inhalt.length == 0) {
            throw new FachFehler(Fehlercode.CSV_LEER);
        }
        Text text = dekodieren(inhalt);
        char trennzeichen = trennzeichen(text.inhalt());
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

    private record Text(String inhalt, String zeichensatz) {
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
        CharsetDetector erkennung = new CharsetDetector();
        erkennung.setText(inhalt);
        CharsetMatch[] treffer = erkennung.detectAll();
        boolean latin1 = treffer != null && treffer.length > 0 && LATIN_1.contains(treffer[0].getName());
        String cp1252 = latin1 ? strikt(inhalt, 0, Charset.forName(WINDOWS_1252)) : null;
        if (cp1252 == null) {
            throw new FachFehler(Fehlercode.CSV_UNGUELTIG);
        }
        return new Text(cp1252, WINDOWS_1252);
    }

    /** Häufigeres Trennzeichen der Kopfzeile ausserhalb von Anführungszeichen; Gleichstand → {@code ;}. */
    static char trennzeichen(String text) {
        int semikolon = 0;
        int komma = 0;
        boolean zitiert = false;
        boolean begonnen = false;
        for (int i = 0; i < text.length(); i++) {
            char zeichen = text.charAt(i);
            if (!zitiert && (zeichen == '\n' || zeichen == '\r')) {
                if (begonnen) {
                    break;
                }
                continue;
            }
            begonnen = true;
            if (zeichen == '"') {
                zitiert = !zitiert;
            } else if (!zitiert && zeichen == ';') {
                semikolon++;
            } else if (!zitiert && zeichen == ',') {
                komma++;
            }
        }
        return komma > semikolon ? ',' : ';';
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
