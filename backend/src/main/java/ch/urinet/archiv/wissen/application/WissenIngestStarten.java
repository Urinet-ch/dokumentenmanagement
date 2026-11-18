package ch.urinet.archiv.wissen.application;

import ch.urinet.archiv.dokument.application.DokumentEreignisse;
import ch.urinet.archiv.gemeinsam.domain.FachFehler;
import ch.urinet.archiv.gemeinsam.domain.Fehlercode;
import ch.urinet.archiv.ki.application.KiVerbrauchPort;
import ch.urinet.archiv.verarbeitung.application.AuftragService;
import ch.urinet.archiv.verarbeitung.domain.AuftragTyp;
import ch.urinet.archiv.verarbeitung.domain.Nutzlast;
import ch.urinet.archiv.wissen.application.WissenIngestPort.NeuerEntwurf;
import ch.urinet.archiv.wissen.application.WissenIngestPort.Quelle;
import ch.urinet.archiv.wissen.domain.WissenIngestGrenzen;
import ch.urinet.archiv.wissen.domain.WissenIngestSchaetzung;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Erzeugt und reiht einen Ingest ein; jede Prüfung und jeder Auftrag gehören zur selben Mandantentransaktion. */
@ApplicationScoped
public class WissenIngestStarten implements DokumentEreignisse {

    public static final AuftragTyp TYP = new AuftragTyp("WISSEN_INGEST");
    public static final String NUTZLAST_ENTWURF_ID = "entwurfId";
    private static final String WISSENSQUELLE = "WISSENSQUELLE";
    private static final String ARCHIVIERT = "ARCHIVIERT";
    private static final String PERSONAL = "PERSONAL";
    private static final String HANDLER_FEHLT = "Die Ingest-Verarbeitung ist noch nicht verfügbar.";

    private final WissenIngestPort port;
    private final WissenKonfiguration konfiguration;
    private final AuftragService auftraege;

    public WissenIngestStarten(WissenIngestPort port, WissenKonfiguration konfiguration, AuftragService auftraege) {
        this.port = port;
        this.konfiguration = konfiguration;
        this.auftraege = auftraege;
    }

    @Transactional
    public Ergebnis manuell(UUID dokumentId, Integer seiteVon, Integer seiteBis, String akteur) {
        return starten(dokumentId, seiteVon, seiteBis, akteur, true);
    }

    @Transactional
    @Override
    public void archiviert(UUID dokumentId, String art) {
        if (!WISSENSQUELLE.equals(art)) {
            return;
        }
        starten(dokumentId, null, null, "system:archivierung", false);
    }

    private Ergebnis starten(UUID dokumentId, Integer seiteVon, Integer seiteBis, String akteur, boolean manuell) {
        auftraege.offen(TYP, Optional.empty(), true);
        Quelle quelle = port.quelleLaden(dokumentId)
                .orElseThrow(() -> new FachFehler(Fehlercode.NICHT_GEFUNDEN));
        if (!WISSENSQUELLE.equals(quelle.art())) {
            if (manuell) {
                throw new FachFehler(Fehlercode.KEINE_WISSENSQUELLE);
            }
            return null;
        }
        seitenbereichPruefen(seiteVon, seiteBis, quelle.seiten());
        if (!ARCHIVIERT.equals(quelle.status())) {
            throw new FachFehler(Fehlercode.STATUS_KONFLIKT);
        }
        if (port.entwurfFuerDokumentOffen(dokumentId)) {
            if (manuell) {
                throw new FachFehler(Fehlercode.STATUS_KONFLIKT);
            }
            return null;
        }

        WissenIngestGrenzen grenzen = konfiguration.ingest();
        Optional<String> hindernis = hindernis(quelle);
        WissenIngestSchaetzung.Schaetzung schaetzung = null;
        if (hindernis.isEmpty()) {
            try {
                schaetzung = WissenIngestSchaetzung.schaetzen(quelle.volltext(), quelle.seiten(), seiteVon,
                        seiteBis, "NORMAL".equals(quelle.vertraulichkeit()), grenzen);
            } catch (IllegalArgumentException e) {
                throw new FachFehler(Fehlercode.ANFRAGE_UNGUELTIG);
            }
            long budget = quelle.budgetTokens() == null ? grenzen.budgetTokens() : quelle.budgetTokens();
            if (schaetzung.tokens() > budget) {
                hindernis = Optional.of("Die Unterlage ist für einen Durchgang zu gross (geschätzt "
                        + schaetzung.tokens() + " Tokens). Bitte wählen Sie einen Seitenbereich.");
            }
        }

        long monatsbudget = quelle.monatsbudgetTokens() == null
                ? grenzen.monatsbudgetTokens() : quelle.monatsbudgetTokens();
        if (hindernis.isEmpty()) {
            YearMonth monat = YearMonth.now(ZoneOffset.UTC);
            Instant von = monat.atDay(1).atStartOfDay().toInstant(ZoneOffset.UTC);
            Instant bis = monat.plusMonths(1).atDay(1).atStartOfDay().toInstant(ZoneOffset.UTC);
            long verbrauch = port.verbrauchImZeitraum(von, bis);
            if (verbrauch >= monatsbudget || schaetzung.tokens() > monatsbudget - verbrauch) {
                if (manuell) {
                    throw new FachFehler(Fehlercode.BUDGET);
                }
                hindernis = Optional.of("Budget erschöpft");
            }
        }
        if (hindernis.isEmpty() && !auftraege.hatHandler(TYP)) {
            hindernis = Optional.of(HANDLER_FEHLT);
        }

        String hinweis = hindernis.map(grund -> "nicht in die Wissensbasis übernommen: " + grund).orElse(null);
        String status;
        if (hindernis.isPresent()) {
            status = "FEHLER";
        } else {
            status = port.hatAktivenIngest() ? "WARTEND" : "IN_ARBEIT";
        }
        UUID entwurfId = UUID.randomUUID();
        String schritt = status.equals("FEHLER") ? null : "KERNAUSSAGEN";
        Integer gespeicherteSeiteVon = seiteVon == null && seiteBis != null ? Integer.valueOf(1) : seiteVon;
        port.entwurfAnlegen(new NeuerEntwurf(entwurfId, dokumentId, gespeicherteSeiteVon, seiteBis, status, schritt,
                konfiguration.modell(), akteur, hinweis));
        if (hinweis != null) {
            port.dokumentHinweisSetzen(dokumentId, hinweis);
        } else if (status.equals("IN_ARBEIT")) {
            auftraege.einreihen(TYP, new Nutzlast(Map.of(NUTZLAST_ENTWURF_ID, entwurfId)));
        }
        return new Ergebnis(entwurfId, status, schaetzung == null ? 0 : schaetzung.tokens(), hinweis);
    }

    private static void seitenbereichPruefen(Integer seiteVon, Integer seiteBis, Integer seiten) {
        if ((seiteVon != null && seiteVon < 1) || (seiteBis != null && seiteBis < 1)
                || (seiteVon != null && seiteBis != null && seiteBis < seiteVon)
                || (seiten != null && ((seiteVon != null && seiteVon > seiten)
                || (seiteBis != null && seiteBis > seiten)))) {
            throw new FachFehler(Fehlercode.ANFRAGE_UNGUELTIG);
        }
    }

    private static Optional<String> hindernis(Quelle quelle) {
        if (!quelle.bestaetigt()) {
            return Optional.of("Dokumentart ist noch nicht bestätigt.");
        }
        if (PERSONAL.equals(quelle.vertraulichkeit())) {
            return Optional.of("Personalunterlagen werden nicht in die Wissensbasis übernommen.");
        }
        if (quelle.volltext() == null || quelle.volltext().isBlank()) {
            return Optional.of("kein Volltext vorhanden");
        }
        if (!quelle.pdfaVorhanden()) {
            return Optional.of("keine Seitenangaben möglich: PDF/A fehlt");
        }
        if (quelle.seiten() == null || quelle.seiten() < 1
                || quelle.volltext().split("\\f", -1).length != quelle.seiten()) {
            return Optional.of("keine Seitenangaben möglich");
        }
        return Optional.empty();
    }

    public record Ergebnis(UUID entwurfId, String status, long geschaetzteTokens, String hinweis) {
    }
}
