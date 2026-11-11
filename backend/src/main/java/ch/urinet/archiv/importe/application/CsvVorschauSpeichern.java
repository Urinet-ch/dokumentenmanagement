package ch.urinet.archiv.importe.application;

import ch.urinet.archiv.importe.application.ImportRepository.Abgeglichen;
import ch.urinet.archiv.importe.domain.ImportArt;
import ch.urinet.archiv.importe.domain.ImportFeld;
import ch.urinet.archiv.importe.domain.ImportLauf;
import ch.urinet.archiv.importe.domain.ImportZeile;
import ch.urinet.archiv.importe.domain.KontaktAbgleich;
import ch.urinet.archiv.importe.domain.KontaktAbgleich.Befund;
import ch.urinet.archiv.importe.domain.KontaktAbgleich.Bestaende;
import ch.urinet.archiv.importe.domain.KontaktAbgleich.Bestand;
import ch.urinet.archiv.importe.domain.KontaktAbgleich.Schluessel;
import ch.urinet.archiv.importe.domain.LaufStatus;
import ch.urinet.archiv.importe.domain.ZeilenPruefung.Ergebnis;
import ch.urinet.archiv.importe.domain.Zuordnung;
import ch.urinet.archiv.kontakt.application.KontaktRegister;
import ch.urinet.archiv.kontakt.application.KontaktTreffer;
import ch.urinet.archiv.kontakt.domain.Kontakt;
import ch.urinet.archiv.verarbeitung.application.Auftrag;
import ch.urinet.archiv.verarbeitung.application.AuftragService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Kurze Transaktionen des {@link CsvVorschauHandler} (design.md «Pflichten der Handler»): Lesen und Abgleichen ohne
 * Sperre, Schreiben erst nach {@link AuftragService#anspruchSichern} und mit gesperrtem Lauf, dessen Status und
 * Zuordnung sich seit dem Start nicht geändert haben dürfen. Eigene Klasse, weil {@code @Transactional} nur über den
 * Proxy wirkt.
 */
@ApplicationScoped
public class CsvVorschauSpeichern {

    private final ImportRepository repository;
    private final KontaktRegister kontakte;
    private final AuftragService auftraege;
    private final BigDecimal schwelle;

    public CsvVorschauSpeichern(ImportRepository repository, KontaktRegister kontakte, AuftragService auftraege,
                                @ConfigProperty(name = "archiv.abgleich.aehnlichkeit") BigDecimal schwelle) {
        this.repository = repository;
        this.kontakte = kontakte;
        this.auftraege = auftraege;
        this.schwelle = schwelle;
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public Optional<ImportLauf> laden(UUID laufId) {
        return repository.lauf(laufId);
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public int breite(UUID laufId) {
        return repository.breite(laufId);
    }

    /**
     * Liest den nächsten Block nach {@code nachNr} und gleicht seine Zeilen ab (nur Zeilen mit Werten, die nicht
     * fehlerhaft oder übersprungen sind). Eine Abfrage für die exakten Stufen des ganzen Blocks, eine zweite für die
     * Namensähnlichkeit der Zeilen ohne exakten Treffer. Schreibt nichts.
     */
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public Block abgleichen(UUID laufId, Zuordnung zuordnung, int nachNr, int stapel) {
        List<ImportZeile> gelesen = repository.zeilen(laufId, nachNr, stapel, CsvImport.STAPEL_ZEICHEN);
        if (gelesen.isEmpty()) {
            return Block.LEER;
        }
        List<ImportZeile> offen = new ArrayList<>();
        List<Map<String, String>> werte = new ArrayList<>();
        List<Schluessel> schluessel = new ArrayList<>();
        Set<String> crmIds = new LinkedHashSet<>();
        Set<String> emails = new LinkedHashSet<>();
        List<String> namen = new ArrayList<>();
        List<String> plz = new ArrayList<>();
        for (ImportZeile zeile : gelesen) {
            if (zeile.werte() == null || !zeile.ergebnis().abzugleichen()) {
                continue;
            }
            Map<String, String> v = KontaktAbgleich.vergleichswerte(zuordnung.werte(ImportArt.KONTAKT, zeile.werte()));
            Schluessel s = KontaktAbgleich.schluessel(v);
            offen.add(zeile);
            werte.add(v);
            schluessel.add(s);
            if (!s.crmId().isEmpty()) {
                crmIds.add(s.crmId());
            }
            if (!s.email().isEmpty()) {
                emails.add(s.email());
            }
            if (s.namePlz()) {
                namen.add(s.name());
                plz.add(s.plz());
            }
        }
        List<Bestand> bestand = kontakte.mitSchluesseln(crmIds, emails, namen, plz).stream()
                .map(CsvVorschauSpeichern::bestand).toList();
        Bestaende bestaende = new Bestaende(bestand);
        List<Befund> befunde = new ArrayList<>(offen.size());
        Set<String> ohneTreffer = new LinkedHashSet<>();
        for (int i = 0; i < offen.size(); i++) {
            Befund befund = KontaktAbgleich.pruefen(werte.get(i), bestaende, List.of());
            befunde.add(befund);
            if (befund.ergebnis() == Ergebnis.NEU && !schluessel.get(i).name().isEmpty()) {
                ohneTreffer.add(schluessel.get(i).name());
            }
        }
        Map<String, List<UUID>> aehnliche = ohneTreffer.isEmpty() ? Map.of()
                : kontakte.aehnlichsteJe(ohneTreffer, schwelle, KontaktAbgleich.KANDIDATEN);
        List<Abgeglichen> zeilen = new ArrayList<>(offen.size());
        for (int i = 0; i < offen.size(); i++) {
            Befund befund = befunde.get(i);
            List<UUID> kandidaten = aehnliche.getOrDefault(schluessel.get(i).name(), List.of());
            if (befund.ergebnis() == Ergebnis.NEU && !kandidaten.isEmpty()) {
                befund = KontaktAbgleich.pruefen(werte.get(i), bestaende, kandidaten);
            }
            zeilen.add(new Abgeglichen(offen.get(i).mit(befund),
                    KontaktAbgleich.fingerabdruecke(schluessel.get(i), befund.kontaktId())));
        }
        return new Block(zeilen, gelesen.getLast().nr());
    }

    /** Schreibt die Zeilen eines Blocks; {@code false} ohne Schreiben, wenn der Lauf nicht mehr derselbe ist. */
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public boolean schreiben(Auftrag auftrag, ImportLauf lauf, List<Abgeglichen> zeilen) {
        auftraege.anspruchSichern(auftrag);
        if (gesperrt(lauf).isEmpty()) {
            return false;
        }
        if (!zeilen.isEmpty()) {
            repository.abgleichSchreiben(lauf.id(), zeilen);
        }
        return true;
    }

    /**
     * Markiert Zeilen, die in der Datei doppelt vorkommen, zählt die Ergebnisse und setzt den Lauf auf
     * {@code VORSCHAU}. Leer ohne Änderung, wenn der Lauf nicht mehr derselbe ist oder noch offene Zeilen hat (eine
     * neue Zuordnung hat sie zurückgesetzt; ihr eigener Auftrag rechnet sie).
     */
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public Optional<ImportLauf> abschliessen(Auftrag auftrag, ImportLauf lauf) {
        auftraege.anspruchSichern(auftrag);
        Optional<ImportLauf> aktuell = gesperrt(lauf);
        if (aktuell.isEmpty()) {
            return Optional.empty();
        }
        repository.doppelteMarkieren(lauf.id(), KontaktAbgleich.IN_DER_DATEI);
        ImportLauf.Zaehler z = repository.zaehlen(lauf.id());
        int bewertet = z.neu() + z.geaendert() + z.unveraendert() + z.dubletten() + z.fehler() + z.uebersprungen();
        if (bewertet != aktuell.get().zeilen()) {
            return Optional.empty();
        }
        ImportLauf fertig = aktuell.get().mitVorschau(z);
        repository.laufAktualisieren(fertig);
        return Optional.of(fertig);
    }

    private Optional<ImportLauf> gesperrt(ImportLauf lauf) {
        return repository.laufSperren(lauf.id())
                .filter(l -> l.status() == LaufStatus.VORSCHAU_AUSSTEHEND && lauf.zuordnung().equals(l.zuordnung()));
    }

    private static Bestand bestand(KontaktTreffer treffer) {
        Kontakt k = treffer.kontakt();
        Map<String, String> felder = new HashMap<>();
        felder.put(ImportFeld.CRM_ID, k.crmId());
        felder.put(ImportFeld.TYP, k.typ() == null ? null : k.typ().name());
        felder.put(ImportFeld.NAME, k.name());
        felder.put(ImportFeld.VORNAME, k.vorname());
        felder.put(ImportFeld.STRASSE, k.strasse());
        felder.put(ImportFeld.PLZ, k.plz());
        felder.put(ImportFeld.ORT, k.ort());
        felder.put(ImportFeld.EMAIL, k.email());
        felder.put(ImportFeld.TELEFON, k.telefon());
        felder.put("uid", k.uid());
        felder.put("iban", k.iban());
        felder.put("kategorie", k.kategorie());
        felder.values().removeIf(Objects::isNull);
        return new Bestand(k.id(), treffer.nameNormalisiert(), felder);
    }

    /** Abgeglichene Zeilen eines Blocks und die Nummer der letzten gelesenen Zeile (auch wenn sie nicht dabei ist). */
    public record Block(List<Abgeglichen> zeilen, int letzteNr) {

        static final Block LEER = new Block(List.of(), -1);

        public boolean leer() {
            return letzteNr < 0;
        }
    }
}
