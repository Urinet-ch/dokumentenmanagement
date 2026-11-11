package ch.urinet.archiv.importe.application;

import ch.urinet.archiv.ablage.domain.Pruefsumme;
import ch.urinet.archiv.gemeinsam.domain.FachFehler;
import ch.urinet.archiv.gemeinsam.domain.Fehlercode;
import ch.urinet.archiv.importe.domain.CsvTabelle;
import ch.urinet.archiv.importe.domain.Kopfzeile;
import ch.urinet.archiv.importe.domain.GespeicherteZuordnung;
import ch.urinet.archiv.importe.domain.ImportArt;
import ch.urinet.archiv.importe.domain.ImportLauf;
import ch.urinet.archiv.importe.domain.ImportZeile;
import ch.urinet.archiv.importe.domain.LaufStatus;
import ch.urinet.archiv.importe.domain.ZeilenPruefung;
import ch.urinet.archiv.importe.domain.ZeilenPruefung.Befund;
import ch.urinet.archiv.importe.domain.ZeilenPruefung.Ergebnis;
import ch.urinet.archiv.importe.domain.Zuordnung;
import ch.urinet.archiv.importe.domain.Zuordnungsvorschlag;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * CSV-Import der CRM-Daten, Teil Erkennung und Zuordnung (Aufgabe 4.1; Req 13.1, 13.2, 13.10, 13.11): Upload lesen,
 * Lauf und Zeilen speichern, Zuordnung vorschlagen, bestätigen und speichern. Vorschau mit Abgleich (4.2) und Übernahme
 * (4.3) folgen; ein Lauf mit gültiger Zuordnung wartet im Status {@code VORSCHAU_AUSSTEHEND}. Die Datei wird nie als
 * Ganzes gehalten: Der Leser meldet Zeile für Zeile, gespeichert und neu geprüft wird in Blöcken zu höchstens
 * {@link #STAPEL} Zeilen und {@link #STAPEL_WERTE} Werten (Entscheid Fabian 11.11.2026). Geloggt werden nur IDs, Prüfsumme, Zahlen und Status, nie Feldwerte.
 */
@ApplicationScoped
public class CsvImport {

    static final int DATEINAME_MAX = 255;
    /** Zeilen je Block beim Speichern und beim erneuten Prüfen. */
    static final int STAPEL = 500;
    /** Werte je Block höchstens; begrenzt den Heap bei breiten Dateien (bis 1'000 Spalten). */
    static final int STAPEL_WERTE = 50_000;
    private static final Logger LOG = Logger.getLogger(CsvImport.class);
    private static final EnumSet<LaufStatus> ZUORDENBAR = EnumSet.of(LaufStatus.ZUORDNUNG_NOETIG,
            LaufStatus.VORSCHAU_AUSSTEHEND);

    private final CsvLeser leser;
    private final ImportRepository repository;
    private final ImportEinstellungen einstellungen;

    public CsvImport(CsvLeser leser, ImportRepository repository, ImportEinstellungen einstellungen) {
        this.leser = leser;
        this.repository = repository;
        this.einstellungen = einstellungen;
    }

    /** Ergebnis des Uploads; {@code vorschlag} nur im Status {@code ZUORDNUNG_NOETIG} (Zuordnungsdialog, Req 13.2). */
    public record Hochgeladen(ImportLauf lauf, Optional<Zuordnung> vorschlag) {
    }

    /**
     * Berechnet zuerst die SHA-256 der Datei und loggt sie (Regel 3). Ist die Datei grösser als
     * {@code archiv.import.max-groesse}, lehnt das Archiv sie mit {@code DATEI_ZU_GROSS} ab, bevor der Parser läuft.
     * Sonst liest es die Datei und legt den Lauf mit seinen Zeilen an. Mit gespeicherter Zuordnung, deren Kopfzeile
     * passt, werden die Zeilen geprüft ({@code VORSCHAU_AUSSTEHEND}); sonst gilt {@code ZUORDNUNG_NOETIG} mit Vorschlag.
     * Ohne Datenzeilen {@code CSV_LEER}, ein ungültiges Format oder mehr als 10'000 Datenzeilen {@code CSV_UNGUELTIG};
     * dann bleibt nichts gespeichert (die Transaktion wird zurückgerollt).
     */
    @Transactional
    public Hochgeladen hochladen(ImportArt art, boolean vollexport, String dateiname, Path datei, String benutzer) {
        UUID id = UUID.randomUUID();
        long groesse = groesse(datei);
        String sha256 = sha256(datei);
        LOG.infof("CSV-Import empfangen: lauf=%s art=%s sha256=%s bytes=%d", id, art.code(), sha256, groesse);
        if (groesse > einstellungen.maxGroesse()) {
            LOG.infof("CSV-Import abgelehnt: lauf=%s fehlercode=%s grenze=%d", id, Fehlercode.DATEI_ZU_GROSS,
                    einstellungen.maxGroesse());
            throw new FachFehler(Fehlercode.DATEI_ZU_GROSS);
        }
        Erfassung erfassung = new Erfassung(id, art, vollexport, dateiname(dateiname), sha256, benutzer);
        try {
            leser.lesen(datei, erfassung);
            erfassung.abschliessen();
        } catch (FachFehler fehler) {
            LOG.infof("CSV-Import abgelehnt: lauf=%s fehlercode=%s", id, fehler.code());
            throw fehler;
        }
        ImportLauf lauf = erfassung.lauf;
        LOG.infof("CSV-Import gespeichert: lauf=%s art=%s zeichensatz=%s spalten=%d zeilen=%d fehler=%d "
                        + "uebersprungen=%d status=%s", id, art.code(), lauf.zeichensatz(), lauf.kopf().spalten(),
                lauf.zeilen(), lauf.fehler(), lauf.uebersprungen(), lauf.status());
        return new Hochgeladen(lauf, erfassung.gueltig.isPresent() ? Optional.empty()
                : Optional.of(Zuordnungsvorschlag.fuer(art, lauf.kopf())));
    }

    /** Nimmt Kopf und Zeilen vom Leser entgegen und speichert sie blockweise im Lauf. */
    private final class Erfassung implements CsvLeser.Empfaenger {

        private final UUID id;
        private final ImportArt art;
        private final boolean vollexport;
        private final String dateiname;
        private final String sha256;
        private final String benutzer;
        private final List<ImportZeile> block = new ArrayList<>(STAPEL);
        private Optional<Zuordnung> gueltig = Optional.empty();
        private ImportLauf lauf;
        private int werte;
        private int zeilen;
        private int fehler;
        private int uebersprungen;

        Erfassung(UUID id, ImportArt art, boolean vollexport, String dateiname, String sha256, String benutzer) {
            this.id = id;
            this.art = art;
            this.vollexport = vollexport;
            this.dateiname = dateiname;
            this.sha256 = sha256;
            this.benutzer = benutzer;
        }

        @Override
        public void kopf(String zeichensatz, char trennzeichen, Kopfzeile kopf) {
            if (ZeilenPruefung.kopfZuLang(kopf, einstellungen.feldGrenze())) {
                throw new FachFehler(Fehlercode.CSV_UNGUELTIG);
            }
            gueltig = repository.zuordnung(art).filter(z -> z.passt(kopf)).map(GespeicherteZuordnung::zuordnung);
            LaufStatus status = gueltig.isPresent() ? LaufStatus.VORSCHAU_AUSSTEHEND : LaufStatus.ZUORDNUNG_NOETIG;
            lauf = new ImportLauf(id, art, vollexport, dateiname, sha256, zeichensatz, trennzeichen, kopf, 0, 0, 0,
                    status, benutzer, Instant.now());
            repository.laufAnlegen(lauf);
        }

        @Override
        public void zeile(CsvTabelle.Zeile zeile) {
            int spalten = lauf.kopf().spalten();
            int grenze = einstellungen.feldGrenze();
            Befund befund = gueltig.map(z -> ZeilenPruefung.pruefen(art, z, zeile.werte(), spalten, grenze))
                    .orElseGet(() -> ZeilenPruefung.struktur(zeile.werte(), spalten, grenze));
            ImportZeile gespeichert = CsvImport.zeile(zeile.nr(), zeile.werte(), befund);
            zaehlen(gespeichert.ergebnis());
            block.add(gespeichert);
            werte += zeile.werte().size();
            if (block.size() == STAPEL || werte >= STAPEL_WERTE) {
                speichern();
            }
        }

        private void zaehlen(Ergebnis ergebnis) {
            zeilen++;
            fehler += ergebnis == Ergebnis.FEHLER ? 1 : 0;
            uebersprungen += ergebnis == Ergebnis.UEBERSPRUNGEN ? 1 : 0;
        }

        private void speichern() {
            repository.zeilenAnlegen(id, block);
            block.clear();
            werte = 0;
        }

        void abschliessen() {
            if (zeilen == 0) {
                throw new FachFehler(Fehlercode.CSV_LEER);
            }
            if (!block.isEmpty()) {
                speichern();
            }
            lauf = new ImportLauf(id, art, vollexport, dateiname, sha256, lauf.zeichensatz(), lauf.trennzeichen(),
                    lauf.kopf(), zeilen, fehler, uebersprungen, lauf.status(), benutzer, lauf.erstellt());
            repository.laufAktualisieren(lauf);
        }
    }

    @Transactional
    public Optional<GespeicherteZuordnung> zuordnung(ImportArt art) {
        return repository.zuordnung(art);
    }

    /**
     * Bestätigt die Zuordnung eines Laufs, speichert sie für Mandant und Importart (Req 13.2) und prüft seine Zeilen
     * blockweise neu. Mit {@code profil} rechnet das Archiv die Positionen aus der Kopfzeile des Laufs; {@code felder}
     * übersteuern einzelne Felder. Fehler: Lauf unbekannt → {@code NICHT_GEFUNDEN}; anderer Status →
     * {@code STATUS_KONFLIKT}; Importart, Profil, Feld oder Position ungültig → {@code ANFRAGE_UNGUELTIG}; Profil passt
     * nicht zur Datei oder keine Spalte für {@code name} bzw. {@code titel} → {@code CSV_UNGUELTIG} (Req 13.10), nichts
     * wird gespeichert.
     */
    @Transactional
    public ImportLauf zuordnen(ImportArt art, UUID laufId, String profil, Map<String, Integer> felder) {
        ImportLauf lauf = repository.laufSperren(laufId).orElseThrow(() -> new FachFehler(Fehlercode.NICHT_GEFUNDEN));
        if (lauf.art() != art) {
            throw new FachFehler(Fehlercode.ANFRAGE_UNGUELTIG);
        }
        if (!ZUORDENBAR.contains(lauf.status())) {
            throw new FachFehler(Fehlercode.STATUS_KONFLIKT);
        }
        Zuordnung zuordnung = zuordnung(art, lauf, profil, felder == null ? Map.of() : felder);
        Optional<Zuordnung.Mangel> mangel = zuordnung.mangel(art, lauf.kopf().spalten());
        if (mangel.isPresent()) {
            LOG.infof("Zuordnung abgelehnt: lauf=%s art=%s mangel=%s", laufId, art.code(), mangel.get());
            throw new FachFehler(mangel.get() == Zuordnung.Mangel.PFLICHTFELD_FEHLT ? Fehlercode.CSV_UNGUELTIG
                    : Fehlercode.ANFRAGE_UNGUELTIG);
        }
        repository.zuordnungSpeichern(new GespeicherteZuordnung(art, zuordnung, lauf.kopf().hash(), Instant.now()));
        int fehler = 0;
        int uebersprungen = 0;
        int nachNr = 0;
        int stapel = stapel(repository.breite(laufId));
        List<ImportZeile> block;
        while (!(block = repository.zeilen(laufId, nachNr, stapel)).isEmpty()) {
            List<ImportZeile> geprueft = new ArrayList<>(block.size());
            for (ImportZeile zeile : block) {
                ImportZeile neu = zeile.werte() == null ? zeile : zeile(zeile.nr(), zeile.werte(),
                        ZeilenPruefung.pruefen(art, zuordnung, zeile.werte(), lauf.kopf().spalten(),
                                einstellungen.feldGrenze()));
                fehler += neu.ergebnis() == Ergebnis.FEHLER ? 1 : 0;
                uebersprungen += neu.ergebnis() == Ergebnis.UEBERSPRUNGEN ? 1 : 0;
                geprueft.add(neu);
            }
            repository.zeilenAktualisieren(laufId, geprueft);
            nachNr = block.getLast().nr();
        }
        ImportLauf neu = lauf.mit(LaufStatus.VORSCHAU_AUSSTEHEND, fehler, uebersprungen);
        repository.laufAktualisieren(neu);
        LOG.infof("Zuordnung gespeichert: lauf=%s art=%s profil=%s felder=%s fehler=%d uebersprungen=%d", laufId,
                art.code(), zuordnung.profilName().orElse("-"), zuordnung.felder().keySet(), neu.fehler(),
                neu.uebersprungen());
        return neu;
    }

    private static Zuordnung zuordnung(ImportArt art, ImportLauf lauf, String profil, Map<String, Integer> felder) {
        if (felder.values().stream().anyMatch(Objects::isNull)) {
            throw new FachFehler(Fehlercode.ANFRAGE_UNGUELTIG);
        }
        if (profil == null) {
            return new Zuordnung(null, felder);
        }
        if (!Zuordnung.PROFIL_21CRM.equals(profil) || art != ImportArt.KONTAKT) {
            throw new FachFehler(Fehlercode.ANFRAGE_UNGUELTIG);
        }
        Zuordnung erkannt = Zuordnungsvorschlag.profil21Crm(art, lauf.kopf())
                .orElseThrow(() -> new FachFehler(Fehlercode.CSV_UNGUELTIG));
        Map<String, Integer> zusammen = new LinkedHashMap<>(erkannt.felder());
        zusammen.putAll(felder);
        return new Zuordnung(Zuordnung.PROFIL_21CRM, zusammen);
    }

    /** Zeilen je Block beim erneuten Prüfen, damit ein Block höchstens {@link #STAPEL_WERTE} Werte hält. */
    static int stapel(int breite) {
        return Math.max(1, Math.min(STAPEL, STAPEL_WERTE / Math.max(1, breite)));
    }

    private static ImportZeile zeile(int nr, List<String> werte, Befund befund) {
        return new ImportZeile(nr, befund.werteVerwerfen() ? null : werte, befund.ergebnis(), befund.meldung());
    }

    private static long groesse(Path datei) {
        try {
            return Files.size(datei);
        } catch (IOException e) {
            throw new UncheckedIOException("Upload nicht lesbar", e);
        }
    }

    private static String sha256(Path datei) {
        MessageDigest digest = Pruefsumme.sha256();
        byte[] puffer = new byte[64 * 1024];
        try (InputStream eingabe = Files.newInputStream(datei)) {
            for (int gelesen; (gelesen = eingabe.read(puffer)) > 0; ) {
                digest.update(puffer, 0, gelesen);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Upload nicht lesbar", e);
        }
        return Pruefsumme.hex(digest);
    }

    private static String dateiname(String name) {
        if (name == null) {
            return null;
        }
        String bereinigt = name.codePoints().filter(c -> !Character.isISOControl(c))
                .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append).toString().strip();
        if (bereinigt.isEmpty()) {
            return null;
        }
        return bereinigt.length() > DATEINAME_MAX ? bereinigt.substring(0, DATEINAME_MAX) : bereinigt;
    }
}
