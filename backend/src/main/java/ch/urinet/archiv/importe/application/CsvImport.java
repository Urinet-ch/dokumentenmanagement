package ch.urinet.archiv.importe.application;

import ch.urinet.archiv.ablage.domain.Pruefsumme;
import ch.urinet.archiv.gemeinsam.domain.FachFehler;
import ch.urinet.archiv.gemeinsam.domain.Fehlercode;
import ch.urinet.archiv.importe.domain.CsvTabelle;
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
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

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
 * (4.3) folgen; ein Lauf mit gültiger Zuordnung wartet im Status {@code VORSCHAU_AUSSTEHEND}. Geloggt werden nur IDs,
 * Prüfsumme, Zahlen und Status, nie Feldwerte.
 */
@ApplicationScoped
public class CsvImport {

    static final int DATEINAME_MAX = 255;
    private static final Logger LOG = Logger.getLogger(CsvImport.class);
    private static final EnumSet<LaufStatus> ZUORDENBAR = EnumSet.of(LaufStatus.ZUORDNUNG_NOETIG,
            LaufStatus.VORSCHAU_AUSSTEHEND);

    private final CsvLeser leser;
    private final ImportRepository repository;
    private final int feldGrenze;

    public CsvImport(CsvLeser leser, ImportRepository repository,
                     @ConfigProperty(name = "archiv.import.feld-grenze", defaultValue = "10000") int feldGrenze) {
        this.leser = leser;
        this.repository = repository;
        this.feldGrenze = feldGrenze;
    }

    /** Ergebnis des Uploads; {@code vorschlag} nur im Status {@code ZUORDNUNG_NOETIG} (Zuordnungsdialog, Req 13.2). */
    public record Hochgeladen(ImportLauf lauf, Optional<Zuordnung> vorschlag) {
    }

    /**
     * Liest die Datei und legt den Lauf mit seinen Zeilen an. Mit gespeicherter Zuordnung, deren Kopfzeile passt, werden
     * die Zeilen geprüft ({@code VORSCHAU_AUSSTEHEND}); sonst gilt {@code ZUORDNUNG_NOETIG} mit Vorschlag. Ohne Datenzeilen
     * {@code CSV_LEER}, ein ungültiges Format oder mehr als 10'000 Datenzeilen {@code CSV_UNGUELTIG}; dann bleibt nichts
     * gespeichert.
     */
    @Transactional
    public Hochgeladen hochladen(ImportArt art, boolean vollexport, String dateiname, byte[] inhalt, String benutzer) {
        UUID id = UUID.randomUUID();
        String sha256 = Pruefsumme.von(inhalt);
        LOG.infof("CSV-Import empfangen: lauf=%s art=%s sha256=%s bytes=%d", id, art.code(), sha256, inhalt.length);
        CsvTabelle tabelle;
        try {
            tabelle = leser.lesen(inhalt);
        } catch (FachFehler fehler) {
            LOG.infof("CSV-Import abgelehnt: lauf=%s fehlercode=%s", id, fehler.code());
            throw fehler;
        }
        if (tabelle.zeilen().isEmpty()) {
            LOG.infof("CSV-Import abgelehnt: lauf=%s fehlercode=%s", id, Fehlercode.CSV_LEER);
            throw new FachFehler(Fehlercode.CSV_LEER);
        }
        Optional<Zuordnung> gueltig = repository.zuordnung(art).filter(z -> z.passt(tabelle.kopf()))
                .map(GespeicherteZuordnung::zuordnung);
        List<ImportZeile> zeilen = new ArrayList<>(tabelle.zeilen().size());
        for (CsvTabelle.Zeile zeile : tabelle.zeilen()) {
            Befund befund = gueltig
                    .map(z -> ZeilenPruefung.pruefen(art, z, zeile.werte(), tabelle.kopf().spalten(), feldGrenze))
                    .orElseGet(() -> ZeilenPruefung.struktur(zeile.werte(), tabelle.kopf().spalten(), feldGrenze));
            zeilen.add(zeile(zeile.nr(), zeile.werte(), befund));
        }
        LaufStatus status = gueltig.isPresent() ? LaufStatus.VORSCHAU_AUSSTEHEND : LaufStatus.ZUORDNUNG_NOETIG;
        ImportLauf lauf = new ImportLauf(id, art, vollexport, dateiname(dateiname), sha256, tabelle.zeichensatz(),
                tabelle.trennzeichen(), tabelle.kopf(), zeilen.size(), anzahl(zeilen, Ergebnis.FEHLER),
                anzahl(zeilen, Ergebnis.UEBERSPRUNGEN), status, benutzer, Instant.now());
        repository.laufAnlegen(lauf, zeilen);
        LOG.infof("CSV-Import gespeichert: lauf=%s art=%s zeichensatz=%s spalten=%d zeilen=%d fehler=%d "
                        + "uebersprungen=%d status=%s", id, art.code(), lauf.zeichensatz(), tabelle.kopf().spalten(),
                lauf.zeilen(), lauf.fehler(), lauf.uebersprungen(), status);
        return new Hochgeladen(lauf, gueltig.isPresent() ? Optional.empty()
                : Optional.of(Zuordnungsvorschlag.fuer(art, tabelle.kopf())));
    }

    @Transactional
    public Optional<GespeicherteZuordnung> zuordnung(ImportArt art) {
        return repository.zuordnung(art);
    }

    /**
     * Bestätigt die Zuordnung eines Laufs, speichert sie für Mandant und Importart (Req 13.2) und prüft seine Zeilen neu.
     * Mit {@code profil} rechnet das Archiv die Positionen aus der Kopfzeile des Laufs; {@code felder} übersteuern
     * einzelne Felder. Fehler: Lauf unbekannt → {@code NICHT_GEFUNDEN}; anderer Status → {@code STATUS_KONFLIKT};
     * Importart, Profil, Feld oder Position ungültig → {@code ANFRAGE_UNGUELTIG}; Profil passt nicht zur Datei oder keine
     * Spalte für {@code name} bzw. {@code titel} → {@code CSV_UNGUELTIG} (Req 13.10), nichts wird gespeichert.
     */
    @Transactional
    public ImportLauf zuordnen(ImportArt art, UUID laufId, String profil, Map<String, Integer> felder) {
        ImportLauf lauf = repository.lauf(laufId).orElseThrow(() -> new FachFehler(Fehlercode.NICHT_GEFUNDEN));
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
        List<ImportZeile> geprueft = new ArrayList<>();
        for (ImportZeile zeile : repository.zeilen(laufId)) {
            geprueft.add(zeile.werte() == null ? zeile : zeile(zeile.nr(), zeile.werte(), ZeilenPruefung.pruefen(art,
                    zuordnung, zeile.werte(), lauf.kopf().spalten(), feldGrenze)));
        }
        ImportLauf neu = lauf.mit(LaufStatus.VORSCHAU_AUSSTEHEND, anzahl(geprueft, Ergebnis.FEHLER),
                anzahl(geprueft, Ergebnis.UEBERSPRUNGEN));
        repository.pruefungAktualisieren(neu, geprueft);
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
        if (!Zuordnung.PROFIL_21CRM.equals(profil)) {
            throw new FachFehler(Fehlercode.ANFRAGE_UNGUELTIG);
        }
        Zuordnung erkannt = Zuordnungsvorschlag.profil21Crm(art, lauf.kopf())
                .orElseThrow(() -> new FachFehler(Fehlercode.CSV_UNGUELTIG));
        Map<String, Integer> zusammen = new LinkedHashMap<>(erkannt.felder());
        zusammen.putAll(felder);
        return new Zuordnung(Zuordnung.PROFIL_21CRM, zusammen);
    }

    private static ImportZeile zeile(int nr, List<String> werte, Befund befund) {
        return new ImportZeile(nr, befund.werteVerwerfen() ? null : werte, befund.ergebnis(), befund.meldung());
    }

    private static int anzahl(List<ImportZeile> zeilen, Ergebnis ergebnis) {
        return (int) zeilen.stream().filter(z -> z.ergebnis() == ergebnis).count();
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
