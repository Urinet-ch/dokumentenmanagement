package ch.urinet.archiv.importe.application;

import ch.urinet.archiv.gemeinsam.domain.FachFehler;
import ch.urinet.archiv.importe.domain.DublettenEntscheid;
import ch.urinet.archiv.importe.domain.ImportArt;
import ch.urinet.archiv.importe.domain.ImportFeld;
import ch.urinet.archiv.importe.domain.ImportLauf;
import ch.urinet.archiv.importe.domain.ImportZeile;
import ch.urinet.archiv.importe.domain.LaufStatus;
import ch.urinet.archiv.importe.domain.VerkaufschanceAbgleich;
import ch.urinet.archiv.importe.domain.ZeilenPruefung.Ergebnis;
import ch.urinet.archiv.importe.domain.Zuordnung;
import ch.urinet.archiv.kontakt.application.KontaktUebernahme;
import ch.urinet.archiv.kontakt.domain.Kontakt;
import ch.urinet.archiv.kontakt.domain.KontaktAngaben;
import ch.urinet.archiv.kontakt.domain.KontaktTyp;
import ch.urinet.archiv.kontakt.domain.Verkaufschance;
import ch.urinet.archiv.verarbeitung.application.Auftrag;
import ch.urinet.archiv.verarbeitung.application.AuftragService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Die Transaktion des {@link CsvUebernehmenHandler} (Aufgabe 4.3; Req 13.6, 13.7, 13.9): sperrt den Lauf, schreibt
 * alle Zeilen blockweise und setzt am Ende Status, Zeit und Zähler (Protokoll, Req 13.8) – alles oder nichts. Den
 * Anspruch auf den Auftrag sichert sie erst unmittelbar vor dem Abschluss, damit die Zeile des Auftrags nicht während
 * der ganzen Übernahme gesperrt ist (das Lebenszeichen schreibt sie weiter); ist er verloren, rollt alles zurück.
 * Jede Anweisung und jedes Warten auf eine Sperre ist einzeln begrenzt ({@link ImportRepository#zeitgrenzenSetzen()}).
 * Eigene Klasse, weil {@code @Transactional} nur über den Proxy wirkt. Geloggt werden nur IDs und Zahlen.
 */
@ApplicationScoped
public class CsvUebernehmenSpeichern {

    private static final Logger LOG = Logger.getLogger(CsvUebernehmenSpeichern.class);

    private final ImportRepository repository;
    private final KontaktUebernahme kontakte;
    private final AuftragService auftraege;

    public CsvUebernehmenSpeichern(ImportRepository repository, KontaktUebernahme kontakte,
                                   AuftragService auftraege) {
        this.repository = repository;
        this.kontakte = kontakte;
        this.auftraege = auftraege;
    }

    /**
     * Übernimmt einen Lauf im Status {@code VORSCHAU}; leer ohne Änderung, wenn der Lauf fehlt oder nicht (mehr) in
     * diesem Status ist (schon übernommen). Eine Unterbrechung rollt die ganze Übernahme zurück.
     */
    @Transactional(value = Transactional.TxType.REQUIRES_NEW, rollbackOn = Exception.class)
    public Optional<ImportLauf.Bilanz> uebernehmen(Auftrag auftrag, UUID laufId) throws InterruptedException {
        repository.zeitgrenzenSetzen();
        Optional<ImportLauf> gefunden = repository.laufSperren(laufId)
                .filter(l -> l.status() == LaufStatus.VORSCHAU && l.zuordnung() != null);
        if (gefunden.isEmpty()) {
            return Optional.empty();
        }
        ImportLauf lauf = gefunden.get();
        Zuordnung zuordnung = lauf.zuordnung();
        Map<Integer, DublettenEntscheid> entscheide = repository.entscheide(laufId);
        Zaehlung zaehlung = new Zaehlung();
        Set<String> imExport = new HashSet<>();
        boolean ohneWerte = false;
        int stapel = CsvImport.stapel(repository.breite(laufId));
        int nachNr = 0;
        List<ImportZeile> block;
        while (!(block = repository.zeilen(laufId, nachNr, stapel, CsvImport.STAPEL_ZEICHEN)).isEmpty()) {
            unterbrochen();
            if (lauf.art() == ImportArt.KONTAKT) {
                for (ImportZeile zeile : block) {
                    zaehlung.zaehlen(kontakt(zeile, zuordnung, entscheide.get(zeile.nr())));
                    if (zeile.werte() == null) {
                        ohneWerte = true;
                    } else if (zeile.ergebnis() != Ergebnis.UEBERSPRUNGEN) {
                        // im CRM gelöschte Zeilen zählen für die Kennzeichnung als fehlend (Req 13.7)
                        String crmId = zuordnung.werte(ImportArt.KONTAKT, zeile.werte()).get(ImportFeld.CRM_ID);
                        if (crmId != null) {
                            imExport.add(crmId);
                        }
                    }
                }
            } else {
                verkaufschancen(laufId, block, zuordnung, zaehlung);
            }
            nachNr = block.getLast().nr();
        }
        unterbrochen();
        int gekennzeichnet = 0;
        if (lauf.art() == ImportArt.KONTAKT && lauf.vollexport()) {
            if (ohneWerte || imExport.isEmpty() || !zuordnung.felder().containsKey(ImportFeld.CRM_ID)) {
                LOG.infof("CSV-Übernahme ohne Kennzeichnung «nicht mehr im CRM-Export»: lauf=%s zeilenOhneWerte=%s "
                        + "crmIds=%d", laufId, ohneWerte, imExport.size());
            } else {
                gekennzeichnet = kontakte.exportAbgleichen(imExport);
            }
        }
        auftraege.anspruchSichern(auftrag);
        ImportLauf.Bilanz bilanz = new ImportLauf.Bilanz(zaehlung.angelegt, zaehlung.aktualisiert,
                zaehlung.ausgelassen, gekennzeichnet);
        repository.uebernahmeAbschliessen(laufId, bilanz);
        return Optional.of(bilanz);
    }

    /** Eine Kontakt-Zeile nach ihrem Ergebnis aus der Vorschau und, bei einer Dublette, nach dem Entscheid. */
    private Ausgang kontakt(ImportZeile zeile, Zuordnung zuordnung, DublettenEntscheid entscheid) {
        if (zeile.werte() == null) {
            return Ausgang.AUSGELASSEN;
        }
        Map<String, String> werte = zuordnung.werte(ImportArt.KONTAKT, zeile.werte());
        return switch (zeile.ergebnis()) {
            case NEU -> anlegen(werte);
            case GEAENDERT -> aktualisieren(zeile.kontaktId(), werte);
            case UNVERAENDERT -> Ausgang.UNVERAENDERT;
            case DUBLETTE -> {
                if (entscheid == null || !entscheid.passt(zeile)) {
                    yield Ausgang.AUSGELASSEN;
                }
                yield switch (entscheid.art()) {
                    case NEU -> anlegen(werte);
                    case ZUSAMMENFUEHREN -> aktualisieren(entscheid.kontaktId(), werte);
                    case UEBERSPRINGEN -> Ausgang.AUSGELASSEN;
                };
            }
            default -> Ausgang.AUSGELASSEN;
        };
    }

    private Ausgang anlegen(Map<String, String> werte) {
        KontaktAngaben angaben;
        try {
            angaben = angaben(werte, true).bereinigt();
        } catch (FachFehler e) {
            return Ausgang.AUSGELASSEN;
        }
        // eine Kontakt-ID aus 21.CRM, die inzwischen ein anderer Kontakt trägt, legt nichts an
        return kontakte.anlegen(werte.get(ImportFeld.CRM_ID), angaben, angaben.nameNormalisiert()).isPresent()
                ? Ausgang.ANGELEGT : Ausgang.AUSGELASSEN;
    }

    /** CSV gewinnt; leere Zellen fehlen in {@code werte} und überschreiben nichts (Req 13.6). */
    private Ausgang aktualisieren(UUID kontaktId, Map<String, String> werte) {
        if (kontaktId == null) {
            return Ausgang.AUSGELASSEN;
        }
        Optional<Kontakt> bestehend = kontakte.sperren(kontaktId);
        if (bestehend.isEmpty()) {
            return Ausgang.AUSGELASSEN;
        }
        KontaktAngaben angaben;
        try {
            angaben = bestehend.get().angaben().mit(angaben(werte, false)).bereinigt();
        } catch (FachFehler e) {
            return Ausgang.AUSGELASSEN;
        }
        kontakte.aktualisieren(kontaktId, werte.get(ImportFeld.CRM_ID), angaben, angaben.nameNormalisiert());
        return Ausgang.AKTUALISIERT;
    }

    /**
     * Ohne zugeordneten Typ gilt ein neuer Kontakt mit Vorname als Person, sonst als Organisation; ein bestehender
     * behält seinen Typ.
     */
    static KontaktAngaben angaben(Map<String, String> werte, boolean typErgaenzen) {
        String typ = werte.get(ImportFeld.TYP);
        KontaktTyp kontaktTyp = typ != null ? KontaktTyp.valueOf(typ) : !typErgaenzen ? null
                : werte.containsKey(ImportFeld.VORNAME) ? KontaktTyp.PERSON : KontaktTyp.ORGANISATION;
        return new KontaktAngaben(kontaktTyp, werte.get(ImportFeld.NAME), werte.get(ImportFeld.VORNAME),
                werte.get(ImportFeld.STRASSE), werte.get(ImportFeld.PLZ), werte.get(ImportFeld.ORT),
                werte.get(ImportFeld.EMAIL), werte.get(ImportFeld.TELEFON), werte.get("uid"), werte.get("iban"),
                werte.get("kategorie"));
    }

    /**
     * Verkaufschancen eines Blocks (Req 13.9): neue und geänderte Zeilen aus der Vorschau; der Kontakt wird über die
     * Kontakt-ID aus 21.CRM neu aufgelöst. Dubletten (doppelt in der Datei), fehlerhafte und übersprungene Zeilen
     * bleiben aus.
     */
    private void verkaufschancen(UUID laufId, List<ImportZeile> block, Zuordnung zuordnung, Zaehlung zaehlung) {
        Set<String> kontaktIds = new LinkedHashSet<>();
        Map<Integer, VerkaufschanceAbgleich.Werte> gelesen = new HashMap<>();
        for (ImportZeile zeile : block) {
            if (zeile.werte() == null || (zeile.ergebnis() != Ergebnis.NEU
                    && zeile.ergebnis() != Ergebnis.GEAENDERT)) {
                continue;
            }
            VerkaufschanceAbgleich.Gelesen g = VerkaufschanceAbgleich.lesen(
                    zuordnung.werte(ImportArt.VERKAUFSCHANCE, zeile.werte()));
            if (g.werte() != null && g.werte().kontaktCrmId() != null) {
                gelesen.put(zeile.nr(), g.werte());
                kontaktIds.add(g.werte().kontaktCrmId());
            }
        }
        Map<String, UUID> kontaktZu = kontakte.kontakteMitCrmId(kontaktIds);
        for (ImportZeile zeile : block) {
            if (zeile.ergebnis() == Ergebnis.UNVERAENDERT) {
                zaehlung.zaehlen(Ausgang.UNVERAENDERT);
                continue;
            }
            VerkaufschanceAbgleich.Werte w = gelesen.get(zeile.nr());
            UUID kontaktId = w == null ? null : kontaktZu.get(w.kontaktCrmId());
            if (kontaktId == null) {
                zaehlung.zaehlen(Ausgang.AUSGELASSEN);
                continue;
            }
            boolean neu = kontakte.verkaufschanceSpeichern(new Verkaufschance(UUID.randomUUID(), w.crmId(), kontaktId,
                    w.titel(), w.phase(), w.betrag(), Verkaufschance.CHF, w.datum(), laufId));
            zaehlung.zaehlen(neu ? Ausgang.ANGELEGT : Ausgang.AKTUALISIERT);
        }
    }

    private static void unterbrochen() throws InterruptedException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedException("CSV-Übernahme unterbrochen");
        }
    }

    private enum Ausgang {
        ANGELEGT,
        AKTUALISIERT,
        UNVERAENDERT,
        AUSGELASSEN
    }

    private static final class Zaehlung {
        private int angelegt;
        private int aktualisiert;
        private int ausgelassen;

        void zaehlen(Ausgang ausgang) {
            switch (ausgang) {
                case ANGELEGT -> angelegt++;
                case AKTUALISIERT -> aktualisiert++;
                case AUSGELASSEN -> ausgelassen++;
                case UNVERAENDERT -> {
                }
            }
        }
    }
}
