package ch.urinet.archiv.export.application;

import ch.urinet.archiv.ablage.domain.Kopie;
import ch.urinet.archiv.export.domain.Belegliste;
import ch.urinet.archiv.export.domain.ExportDateiname;
import ch.urinet.archiv.export.domain.Geschaeftsjahr;
import ch.urinet.archiv.export.domain.Pruefsummen;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Schreibt das ZIP eines Jahresexports stromweise (Spec 02, Req 7.1, 7.4; design.md «Jahresexport»):
 * <ol>
 *     <li>{@code belege/<name>} je Datei der Belege des Geschäftsjahres (Status {@code ARCHIVIERT}; PDF/A, bei
 *     {@code pdfa_fehlt} die Originale), seitenweise gelesen und je Datei direkt aus dem Object Storage in das ZIP
 *     kopiert; weicht die SHA-256 von der Datenbank ab, bricht der Export ab.</li>
 *     <li>{@code manifeste/<datum>.<teil>}: die Tages-Manifeste vom ersten Tag des Geschäftsjahres bis zu seinem
 *     letzten Tag oder, falls später, bis zum Tag der letzten Archivierung eines exportierten Belegs (Europe/Zurich),
 *     damit auch spät archivierte Belege des Jahres ihren Nachweis haben; je Manifest JSON, Signatur, Zeitstempel und
 *     Prüfdaten, ebenfalls mit Vergleich der SHA-256.</li>
 *     <li>{@code belegliste.csv} ({@link Belegliste}) und zuletzt {@code pruefsummen.sha256} ({@link Pruefsummen}).</li>
 * </ol>
 * Im Heap liegen nur der Puffer der Kopie, je Datei eine Zeile der Belegliste und der Prüfsummen (rund 300 Byte) und
 * der Puffer des Ziels, nie eine ganze Datei oder das ZIP. Belege komprimiert der Export kaum (PDF), darum
 * {@link Deflater#BEST_SPEED}. Eine Unterbrechung (Zeitgrenze, Herunterfahren) bricht zwischen zwei Dateien ab.
 */
public class ExportSchreiber {

    public static final String BELEGE = "belege/";
    public static final String MANIFESTE = "manifeste/";
    static final ZoneId ZUERICH = ZoneId.of("Europe/Zurich");
    private static final HexFormat HEX = HexFormat.of();

    /** Liest eine Version aus dem Object Storage stromweise in das Ziel (Ablage, {@code AblagePort#kopieren}). */
    @FunctionalInterface
    public interface Quelle {
        Kopie kopieren(String schluessel, String versionId, OutputStream ziel);
    }

    /** Ergebnis: Zahl der Belege (Dokumente) und der Dateien unter {@code belege/} und {@code manifeste/}. */
    public record Ergebnis(int belege, int belegdateien, int manifestdateien) {
    }

    /** Die SHA-256 einer kopierten Datei weicht von der Datenbank ab; nur Schlüssel bzw. Dokument-ID, kein Inhalt. */
    public static final class PruefsummeWeichtAb extends IllegalStateException {
        PruefsummeWeichtAb(String was) {
            super("Prüfsumme weicht ab: " + was);
        }
    }

    private final ExportBelege belege;
    private final Quelle quelle;
    private final int seite;

    public ExportSchreiber(ExportBelege belege, Quelle quelle, int seite) {
        if (seite < 1) {
            throw new IllegalArgumentException("Seitengrösse mindestens 1");
        }
        this.belege = Objects.requireNonNull(belege, "belege");
        this.quelle = Objects.requireNonNull(quelle, "quelle");
        this.seite = seite;
    }

    public Ergebnis schreiben(Geschaeftsjahr jahr, OutputStream ziel) throws IOException, InterruptedException {
        ZipOutputStream zip = new ZipOutputStream(new NichtSchliessend(ziel), StandardCharsets.UTF_8);
        zip.setLevel(Deflater.BEST_SPEED);
        List<Belegliste.Zeile> zeilen = new ArrayList<>();
        List<String[]> summen = new ArrayList<>();
        ExportDateiname.Vergabe namen = new ExportDateiname.Vergabe();
        LocalDate bis = jahr.letzterTag();
        int anzahl = 0;
        Optional<UUID> nach = Optional.empty();
        while (true) {
            List<ExportBelege.Beleg> liste = belege.seite(jahr.jahr(), nach, seite);
            for (ExportBelege.Beleg beleg : liste) {
                if (beleg.dateien().isEmpty()) {
                    throw new IllegalStateException("Archiviertes Dokument ohne Datei: " + beleg.id());
                }
                LocalDate datum = beleg.datum() != null ? beleg.datum() : LocalDate.ofInstant(beleg.eingang(), ZUERICH);
                boolean mehrere = beleg.dateien().size() > 1;
                for (int i = 0; i < beleg.dateien().size(); i++) {
                    unterbrochen();
                    ExportBelege.Datei datei = beleg.dateien().get(i);
                    String name = namen.vergeben(ExportDateiname.fuer(datum, beleg.art(), beleg.belegnummer(),
                            beleg.id(), beleg.partner(), mehrere ? i + 1 : 0, datei.endung()), beleg.id());
                    String pfad = BELEGE + name;
                    Kopie kopie = eintrag(zip, pfad, beleg.archiviert(), datei.schluessel(), datei.versionId());
                    if (!kopie.sha256().equalsIgnoreCase(datei.sha256())) {
                        throw new PruefsummeWeichtAb("Dokument " + beleg.id() + ", " + datei.schluessel());
                    }
                    zeilen.add(new Belegliste.Zeile(beleg.id(), beleg.art(), beleg.datum(), beleg.partner(),
                            beleg.belegnummer(), beleg.richtung(), beleg.betrag(), beleg.waehrung(), kopie.sha256(),
                            pfad));
                    summen.add(new String[]{kopie.sha256(), pfad});
                }
                if (beleg.archiviert() != null) {
                    LocalDate archiviert = LocalDate.ofInstant(beleg.archiviert(), ZUERICH);
                    bis = archiviert.isAfter(bis) ? archiviert : bis;
                }
                anzahl++;
            }
            if (liste.size() < seite) {
                break;
            }
            nach = Optional.of(liste.getLast().id());
        }
        int manifestdateien = 0;
        for (ExportBelege.Manifestdatei m : belege.manifeste(jahr.ersterTag(), bis)) {
            unterbrochen();
            String pfad = MANIFESTE + m.datum() + "." + m.endung();
            Kopie kopie = eintrag(zip, pfad, null, m.schluessel(), m.versionId());
            if (!kopie.sha256().equalsIgnoreCase(m.sha256())) {
                throw new PruefsummeWeichtAb(m.schluessel());
            }
            summen.add(new String[]{kopie.sha256(), pfad});
            manifestdateien++;
        }
        MessageDigest liste = sha256();
        zip.putNextEntry(new ZipEntry(Belegliste.DATEINAME));
        schreiben(zip, liste, Belegliste.BOM);
        schreiben(zip, liste, Belegliste.kopf());
        for (Belegliste.Zeile z : zeilen) {
            schreiben(zip, liste, Belegliste.zeile(z));
        }
        zip.closeEntry();
        summen.add(new String[]{HEX.formatHex(liste.digest()), Belegliste.DATEINAME});
        zip.putNextEntry(new ZipEntry(Pruefsummen.DATEINAME));
        for (String[] s : summen) {
            zip.write(Pruefsummen.zeile(s[0], s[1]));
        }
        zip.closeEntry();
        zip.finish();
        zip.flush();
        return new Ergebnis(anzahl, zeilen.size(), manifestdateien);
    }

    private Kopie eintrag(ZipOutputStream zip, String pfad, Instant zeit, String schluessel, String versionId)
            throws IOException {
        ZipEntry eintrag = new ZipEntry(pfad);
        if (zeit != null) {
            eintrag.setTimeLocal(LocalDateTime.ofInstant(zeit, ZUERICH));
        }
        zip.putNextEntry(eintrag);
        Kopie kopie = quelle.kopieren(schluessel, versionId, zip);
        zip.closeEntry();
        return kopie;
    }

    private static void schreiben(OutputStream aus, MessageDigest digest, byte[] daten) throws IOException {
        digest.update(daten);
        aus.write(daten);
    }

    private static void unterbrochen() throws InterruptedException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedException("Jahresexport unterbrochen");
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Das Ziel bleibt offen: Abschluss oder Abbruch entscheidet der Aufrufer ({@code Exportziel}). */
    private static final class NichtSchliessend extends FilterOutputStream {
        NichtSchliessend(OutputStream aus) {
            super(aus);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            out.write(b, off, len);
        }

        @Override
        public void close() throws IOException {
            flush();
        }
    }
}
