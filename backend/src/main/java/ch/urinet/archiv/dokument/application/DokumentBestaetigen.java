package ch.urinet.archiv.dokument.application;

import ch.urinet.archiv.dokument.application.DokumentStatusPort.Bestaetigt;
import ch.urinet.archiv.dokument.application.DokumentStatusPort.Finanz;
import ch.urinet.archiv.dokument.application.DokumentStatusPort.Stand;
import ch.urinet.archiv.dokument.application.DokumentStatusPort.Vertrag;
import ch.urinet.archiv.dokument.domain.Dokument;
import ch.urinet.archiv.dokument.domain.DokumentStatus;
import ch.urinet.archiv.dokumentart.application.DokumentartVerwaltung;
import ch.urinet.archiv.dokumentart.domain.Aufbewahrung;
import ch.urinet.archiv.dokumentart.domain.Dokumentart;
import ch.urinet.archiv.dokumentart.domain.Vertraulichkeit;
import ch.urinet.archiv.gemeinsam.domain.FachFehler;
import ch.urinet.archiv.gemeinsam.domain.Fehlercode;
import ch.urinet.archiv.gemeinsam.domain.PflichtfelderFehlen;
import ch.urinet.archiv.kontakt.application.KontaktRegister;
import ch.urinet.archiv.kontakt.domain.Kontakt;
import ch.urinet.archiv.mandant.application.MandantAufloesung;
import ch.urinet.archiv.mandant.application.MandantEinstellungen;
import ch.urinet.archiv.sicherheit.domain.Benutzer;
import ch.urinet.archiv.sicherheit.domain.Sichtregel;
import ch.urinet.archiv.verarbeitung.application.AuftragService;
import ch.urinet.archiv.verarbeitung.domain.AuftragTyp;
import ch.urinet.archiv.verarbeitung.domain.Nutzlast;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import java.time.LocalDate;
import java.time.MonthDay;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Bestätigen (A7, Req 8.2, 8.3, 8.8, 8.9, 2.22, 5.9, 9.8; design.md «Statusmodell»): Pflichtfelder der Art prüfen,
 * Frist berechnen, bei eigenen Arten die Belegnummer reservieren, Status {@code ARCHIVIERUNG} und Auftrag
 * {@code ARCHIVIEREN} einreihen, alles in einer Transaktion. Recht und Sichtbarkeit prüft die REST-Schicht vorher.
 * <p>
 * Die Prüfungen laufen ohne Zeilensperre; erst das Schreiben sperrt das Dokument und prüft Status und Version erneut.
 * Liegt die Frist in der Vergangenheit, setzt eine eigene Transaktion das Kennzeichen «Frist abgelaufen», und die
 * Bestätigung endet mit 409 {@code FRIST_ABGELAUFEN}; der Inhaber bestätigt erneut mit
 * {@code fristAbgelaufenAkzeptiert} oder verwirft (Req 9.8).
 */
@ApplicationScoped
@Transactional
public class DokumentBestaetigen {

    public static final AuftragTyp ARCHIVIEREN = new AuftragTyp("ARCHIVIEREN");
    public static final String NUTZLAST_DOKUMENT = "dokumentId";

    private static final Pattern WAEHRUNG = Pattern.compile("^[A-Z]{3}$");
    private static final Set<String> RICHTUNGEN = Set.of("EINNAHME", "AUSGABE");
    private static final int GESCHAEFTSJAHR_MIN = 1900;
    private static final int GESCHAEFTSJAHR_MAX = 2999;

    private final DokumentRepository repository;
    private final DokumentStatusPort status;
    private final FristKennzeichen frist;
    private final DokumentartVerwaltung arten;
    private final KontaktRegister kontakte;
    private final MandantAufloesung aufloesung;
    private final MandantEinstellungen einstellungen;
    private final AuftragService auftraege;

    public DokumentBestaetigen(DokumentRepository repository, DokumentStatusPort status, FristKennzeichen frist,
                               DokumentartVerwaltung arten, KontaktRegister kontakte, MandantAufloesung aufloesung,
                               MandantEinstellungen einstellungen, AuftragService auftraege) {
        this.repository = repository;
        this.status = status;
        this.frist = frist;
        this.arten = arten;
        this.kontakte = kontakte;
        this.aufloesung = aufloesung;
        this.einstellungen = einstellungen;
        this.auftraege = auftraege;
    }

    /** @return die neue Version des Dokuments (für das ETag) */
    public int bestaetigen(Benutzer benutzer, UUID id, int erwarteteVersion, Bestaetigung b) {
        Dokument dokument = repository.finden(id, Sichtregel.fuer(benutzer))
                .orElseThrow(() -> new FachFehler(Fehlercode.NICHT_GEFUNDEN));
        if (dokument.version() != erwarteteVersion) {
            throw new FachFehler(Fehlercode.VERSION_KONFLIKT);
        }
        if (dokument.status() != DokumentStatus.EINGANGSKORB) {
            throw new FachFehler(Fehlercode.STATUS_KONFLIKT);
        }
        Stand stand = status.stand(id, false).orElseThrow(() -> new FachFehler(Fehlercode.NICHT_GEFUNDEN));
        if (b.art() == null) {
            throw new PflichtfelderFehlen(List.of("art"));
        }
        Dokumentart art = art(b.art());
        pflichtfelderPruefen(art, b, stand);
        if (stand.pdfaFehlt() && !b.ohnePdfa()) {
            throw new PflichtfelderFehlen(List.of("ohnePdfa"));
        }
        werte(b);
        String partnerName = b.partnerId() == null ? null : kontakte.finden(b.partnerId()).map(Kontakt::name)
                .orElseThrow(() -> new FachFehler(Fehlercode.PARTNER_UNBEKANNT));

        MandantEinstellungen.Werte mandant = einstellungen.lesen(aufloesung.pruefen());
        boolean eigeneArt = mandant.eigeneArten().contains(art.code());
        boolean finanz = art.gruppen().contains("finanz");
        if (eigeneArt && finanz && b.belegnummer() != null) {
            Optional<UUID> inhaber = status.belegnummerInhaber(art.code(), b.belegnummer());
            if (inhaber.isPresent() && !inhaber.get().equals(id)) {
                throw new FachFehler(Fehlercode.BELEGNUMMER_VERGEBEN);
            }
        }

        MonthDay gjEnde = mandant.geschaeftsjahresende().monatTag();
        int geschaeftsjahr = b.geschaeftsjahr() != null ? b.geschaeftsjahr()
                : Aufbewahrung.geschaeftsjahrVorschlagen(b.datum(), stand.eingang(), gjEnde);
        boolean vertrag = art.gruppen().contains("vertrag");
        Aufbewahrung aufbewahrung = Aufbewahrung.berechne(art.aufbewahrung(), b.datum(), stand.eingang(),
                geschaeftsjahr, vertrag ? b.gueltigBis() : null, gjEnde, LocalDate.now(Aufbewahrung.ZUERICH));
        if (aufbewahrung.fristAbgelaufen() && !b.fristAbgelaufenAkzeptiert()) {
            frist.markieren(id);
            throw new FachFehler(Fehlercode.FRIST_ABGELAUFEN);
        }

        String vertraulichkeit = b.vertraulichkeit() != null ? b.vertraulichkeit() : art.vertraulichkeit().name();
        Bestaetigt daten = new Bestaetigt(art.code(), b.titel(), b.datum(), b.partnerId(), partnerName, b.projekt(),
                b.tags(), vertraulichkeit, b.personBenutzer(), benutzer.id(), geschaeftsjahr, aufbewahrung.bis(),
                aufbewahrung.fristAbgelaufen(),
                finanz ? Optional.of(new Finanz(b.belegnummer(), b.richtung(), b.betrag(),
                        b.waehrung() != null ? b.waehrung() : "CHF", b.faelligkeit(), b.qrReferenz(), b.konto()))
                        : Optional.empty(),
                vertrag ? Optional.of(new Vertrag(b.gueltigAb(), b.gueltigBis(), b.kuendigungsfrist(),
                        b.naechsteFrist())) : Optional.empty());
        if (!status.bestaetigen(id, erwarteteVersion, daten)) {
            // inzwischen geändert: zuerst die Version, dann der Status (wie oben)
            Stand jetzt = status.stand(id, false).orElseThrow(() -> new FachFehler(Fehlercode.NICHT_GEFUNDEN));
            throw new FachFehler(jetzt.version() != erwarteteVersion ? Fehlercode.VERSION_KONFLIKT
                    : Fehlercode.STATUS_KONFLIKT);
        }
        if (eigeneArt && finanz && b.belegnummer() != null) {
            if (!status.belegnummerReservieren(art.code(), b.belegnummer(), id)) {
                throw new FachFehler(Fehlercode.BELEGNUMMER_VERGEBEN);
            }
        } else {
            status.belegnummerFreigeben(id);
        }
        auftraege.einreihen(ARCHIVIEREN, new Nutzlast(Map.of(NUTZLAST_DOKUMENT, id)));
        return erwarteteVersion + 1;
    }

    /** Unbekannte oder inaktive Art: 400, weil die Oberfläche nur aktive Arten anbietet. */
    private Dokumentart art(String code) {
        Dokumentart art;
        try {
            art = arten.finden(code);
        } catch (FachFehler e) {
            if (e.code() == Fehlercode.NICHT_GEFUNDEN) {
                throw new FachFehler(Fehlercode.ANFRAGE_UNGUELTIG);
            }
            throw e;
        }
        if (!art.aktiv()) {
            throw new FachFehler(Fehlercode.ANFRAGE_UNGUELTIG);
        }
        return art;
    }

    /** Req 8.3: alle fehlenden Pflichtfelder der Art, in der Reihenfolge der Art; nur Namen, nie Werte. */
    static void pflichtfelderPruefen(Dokumentart art, Bestaetigung b, Stand stand) {
        List<String> fehlen = new ArrayList<>();
        for (String feld : art.pflichtfelder()) {
            if (!vorhanden(feld, b, stand)) {
                fehlen.add(feld);
            }
        }
        if (!fehlen.isEmpty()) {
            throw new PflichtfelderFehlen(fehlen);
        }
    }

    private static boolean vorhanden(String feld, Bestaetigung b, Stand stand) {
        if (feld.startsWith(Dokumentart.ZUSATZ)) {
            return stand.zusatzFelder().contains(feld.substring(Dokumentart.ZUSATZ.length()));
        }
        Object wert = switch (feld) {
            case "titel" -> b.titel();
            case "datum" -> b.datum();
            case "partner" -> b.partnerId();
            case "projekt" -> b.projekt();
            case "tags" -> b.tags().isEmpty() ? null : b.tags();
            // ohne Angabe gilt die Standard-Vertraulichkeit der Art (Req 2.22)
            case "vertraulichkeit" -> Boolean.TRUE;
            case "person_benutzer" -> b.personBenutzer();
            case "belegnummer" -> b.belegnummer();
            case "richtung" -> b.richtung();
            case "betrag" -> b.betrag();
            case "waehrung" -> b.waehrung();
            case "faelligkeit" -> b.faelligkeit();
            case "qr_referenz" -> b.qrReferenz();
            case "konto" -> b.konto();
            case "gueltig_ab" -> b.gueltigAb();
            case "gueltig_bis" -> b.gueltigBis();
            case "kuendigungsfrist" -> b.kuendigungsfrist();
            case "naechste_frist" -> b.naechsteFrist();
            default -> throw new IllegalStateException("Unbekanntes Pflichtfeld der Art: " + feld);
        };
        return wert != null;
    }

    /** Werte mit festem Wertebereich; ein ungültiger Wert ist eine falsche Anfrage (400). */
    private static void werte(Bestaetigung b) {
        if (b.vertraulichkeit() != null && Vertraulichkeit.aus(b.vertraulichkeit()).isEmpty()
                || b.richtung() != null && !RICHTUNGEN.contains(b.richtung())
                || b.waehrung() != null && !WAEHRUNG.matcher(b.waehrung()).matches()
                || b.geschaeftsjahr() != null
                && (b.geschaeftsjahr() < GESCHAEFTSJAHR_MIN || b.geschaeftsjahr() > GESCHAEFTSJAHR_MAX)
                || b.betrag() != null && (b.betrag().scale() > 2 || b.betrag().precision() - b.betrag().scale() > 10)) {
            throw new FachFehler(Fehlercode.ANFRAGE_UNGUELTIG);
        }
    }
}
