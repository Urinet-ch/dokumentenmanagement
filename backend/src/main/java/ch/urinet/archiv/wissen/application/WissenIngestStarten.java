package ch.urinet.archiv.wissen.application;

import ch.urinet.archiv.dokument.application.DokumentEreignisse;
import ch.urinet.archiv.gemeinsam.domain.FachFehler;
import ch.urinet.archiv.gemeinsam.domain.Fehlercode;
import ch.urinet.archiv.gemeinsam.domain.Seitentext;
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
import java.time.ZoneId;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Erzeugt und reiht einen Ingest ein (Spec 04, Req 5.1, 5.2, 5.6, 5.7, 5.10, 5.13, 13.4, 13.6); jede Prüfung, der
 * Entwurf und der Auftrag gehören zur selben Mandantentransaktion. Die Vorprüfung ändert das Dokument nie (Req 1.4):
 * Hinweise stehen nur im Entwurf ({@code wissens_entwurf.hinweis}).
 */
@ApplicationScoped
public class WissenIngestStarten implements DokumentEreignisse {

    public static final AuftragTyp TYP = new AuftragTyp("WISSEN_INGEST");
    public static final String NUTZLAST_ENTWURF_ID = "entwurfId";
    /** Akteur der Systemprozesse der Wissensbasis (design.md, «Akteure»). */
    public static final String AKTEUR_SYSTEM = "process:wissen-ingest";
    /** Hinweis des Zwischenstands 14.1a; 14.1b holt Quellen mit diesem Hinweis nach (tasks.md 14.1). */
    public static final String HANDLER_FEHLT = "Die Ingest-Verarbeitung ist noch nicht verfügbar. Die Wissensquelle "
            + "ist archiviert; der Ingest lässt sich wiederholen.";
    static final String NICHT_UEBERNOMMEN = "nicht in die Wissensbasis übernommen – ";
    static final String BUDGET_ERSCHOEPFT = "Budget erschöpft";
    private static final String WISSENSQUELLE = "WISSENSQUELLE";
    private static final String ARCHIVIERT = "ARCHIVIERT";
    private static final String PERSONAL = "PERSONAL";
    private static final ZoneId ZUERICH = ZoneId.of("Europe/Zurich");

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

    /**
     * Automatischer Start nach {@code ARCHIVIERT} (Req 5.1) in der Transaktion der Archivierung: Eine Ausnahme hier
     * (z. B. Zeitgrenze der Verbrauchssumme) rollt die Archivierung zurück, der Auftrag {@code ARCHIVIEREN} wird dann
     * wiederholt (design.md, «Ingest»).
     */
    @Transactional
    @Override
    public void archiviert(UUID dokumentId, String art) {
        if (!WISSENSQUELLE.equals(art)) {
            return;
        }
        starten(dokumentId, null, null, AKTEUR_SYSTEM, false);
    }

    private Ergebnis starten(UUID dokumentId, Integer seiteVon, Integer seiteBis, String akteur, boolean manuell) {
        auftraege.offen(TYP, Optional.empty(), true);
        // Reihenfolge nach design.md: 404, (403 im ZugangFilter), 409, 422/400
        Quelle quelle = port.quelleLaden(dokumentId)
                .orElseThrow(() -> new FachFehler(Fehlercode.NICHT_GEFUNDEN));
        if (!manuell && !WISSENSQUELLE.equals(quelle.art())) {
            return null;
        }
        if (!ARCHIVIERT.equals(quelle.status())) {
            throw new FachFehler(Fehlercode.STATUS_KONFLIKT);
        }
        if (port.entwurfFuerDokumentLaufend(dokumentId)) {
            if (manuell) {
                throw new FachFehler(Fehlercode.STATUS_KONFLIKT);
            }
            return null;
        }
        if (!WISSENSQUELLE.equals(quelle.art())) {
            throw new FachFehler(Fehlercode.KEINE_WISSENSQUELLE);
        }
        seitenbereichPruefen(seiteVon, seiteBis, quelle.seiten());

        WissenIngestGrenzen grenzen = konfiguration.ingest();
        Optional<String> hindernis = hindernis(quelle).map(grund -> NICHT_UEBERNOMMEN + grund);
        long geschaetzt = 0;
        if (hindernis.isEmpty()) {
            long monatsbudget = quelle.monatsbudgetTokens() == null
                    ? grenzen.monatsbudgetTokens() : quelle.monatsbudgetTokens();
            Zeitraum monat = monat(Instant.now());
            long rest = monatsbudget - port.verbrauchImZeitraum(monat.von(), monat.bis());
            if (rest <= 0) {
                hindernis = budgetErschoepft(manuell);
            }
            if (hindernis.isEmpty()) {
                try {
                    geschaetzt = WissenIngestSchaetzung.schaetzen(quelle.volltext(), quelle.seiten(), seiteVon,
                            seiteBis, "NORMAL".equals(quelle.vertraulichkeit()), grenzen,
                            systempromptZeichen(grenzen)).tokens();
                } catch (IllegalArgumentException e) {
                    throw new FachFehler(Fehlercode.ANFRAGE_UNGUELTIG);
                }
                long budget = quelle.budgetTokens() == null ? grenzen.budgetTokens() : quelle.budgetTokens();
                if (geschaetzt > budget) {
                    hindernis = Optional.of("Die Unterlage ist für einen Durchgang zu gross (geschätzt "
                            + tausender(geschaetzt) + " Tokens). Bitte einen Seitenbereich wählen.");
                } else if (geschaetzt > rest) {
                    // strenger als «erschöpft» (Req 13.6): kein Start, dessen Schätzung den Rest übersteigt
                    hindernis = budgetErschoepft(manuell);
                }
            }
        }
        if (hindernis.isEmpty() && !auftraege.hatHandler(TYP)) {
            hindernis = Optional.of(HANDLER_FEHLT);
        }

        String hinweis = hindernis.orElse(null);
        String status;
        if (hinweis != null) {
            status = "FEHLER";
        } else {
            status = port.warteschlangeBelegt() ? "WARTEND" : "IN_ARBEIT";
        }
        UUID entwurfId = UUID.randomUUID();
        String schritt = status.equals("FEHLER") ? null : "KERNAUSSAGEN";
        Integer gespeicherteSeiteVon = seiteVon == null && seiteBis != null ? Integer.valueOf(1) : seiteVon;
        port.entwurfAnlegen(new NeuerEntwurf(entwurfId, dokumentId, gespeicherteSeiteVon, seiteBis, status, schritt,
                konfiguration.modell(), akteur, hinweis));
        if (status.equals("IN_ARBEIT")) {
            auftraege.einreihen(TYP, new Nutzlast(Map.of(NUTZLAST_ENTWURF_ID, entwurfId)));
        }
        return new Ergebnis(entwurfId, status, geschaetzt, hinweis);
    }

    private static Optional<String> budgetErschoepft(boolean manuell) {
        if (manuell) {
            throw new FachFehler(Fehlercode.BUDGET);
        }
        return Optional.of(BUDGET_ERSCHOEPFT);
    }

    /**
     * Länge des Systemprompts eines Teilauftrags für die Schätzung. Den Prompt baut erst 14.1b; bis dahin gilt die
     * Obergrenze {@code werkzeug-max-zeichen} als Platzhalter (design.md, «Zwischenstand 14.1a»).
     */
    static int systempromptZeichen(WissenIngestGrenzen grenzen) {
        return grenzen.werkzeugMaxZeichen();
    }

    /** Kalendermonat in {@code Europe/Zurich} (Req 13.6, tech.md Regel 10) als Zeitraum in UTC. */
    static Zeitraum monat(Instant jetzt) {
        YearMonth monat = YearMonth.from(jetzt.atZone(ZUERICH));
        return new Zeitraum(monat.atDay(1).atStartOfDay(ZUERICH).toInstant(),
                monat.plusMonths(1).atDay(1).atStartOfDay(ZUERICH).toInstant());
    }

    private static String tausender(long zahl) {
        return String.format(Locale.ROOT, "%,d", zahl).replace(',', '\'');
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
        if (PERSONAL.equals(quelle.vertraulichkeit())) {
            return Optional.of("Vertraulichkeit PERSONAL");
        }
        if (quelle.volltext() == null || quelle.volltext().isBlank()) {
            return Optional.of("kein Volltext");
        }
        if (!quelle.pdfaVorhanden() || quelle.seiten() == null || quelle.seiten() < 1
                || Seitentext.von(quelle.volltext(), quelle.seiten()).isEmpty()) {
            return Optional.of("keine Seitenangaben möglich");
        }
        return Optional.empty();
    }

    record Zeitraum(Instant von, Instant bis) {
    }

    public record Ergebnis(UUID entwurfId, String status, long geschaetzteTokens, String hinweis) {
    }
}
