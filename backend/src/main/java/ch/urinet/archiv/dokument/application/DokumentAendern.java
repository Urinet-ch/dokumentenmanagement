package ch.urinet.archiv.dokument.application;

import ch.urinet.archiv.ablage.application.AblagePort;
import ch.urinet.archiv.ablage.domain.AbgelegteDatei;
import ch.urinet.archiv.ablage.domain.SperrAenderung;
import ch.urinet.archiv.ablage.domain.Sperrvorgabe;
import ch.urinet.archiv.dokument.application.DokumentAenderungPort.Metadaten;
import ch.urinet.archiv.dokument.application.DokumentStatusPort.Finanz;
import ch.urinet.archiv.dokument.application.DokumentStatusPort.Vertrag;
import ch.urinet.archiv.dokument.domain.Dokument;
import ch.urinet.archiv.dokument.domain.DokumentStatus;
import ch.urinet.archiv.dokumentart.application.DokumentartVerwaltung;
import ch.urinet.archiv.dokumentart.domain.Aufbewahrung;
import ch.urinet.archiv.dokumentart.domain.Aufbewahrung.Neuberechnung;
import ch.urinet.archiv.dokumentart.domain.Dokumentart;
import ch.urinet.archiv.dokumentart.domain.Vertraulichkeit;
import ch.urinet.archiv.gemeinsam.domain.FachFehler;
import ch.urinet.archiv.gemeinsam.domain.Fehlercode;
import ch.urinet.archiv.gemeinsam.domain.PflichtfelderFehlen;
import ch.urinet.archiv.kontakt.application.KontaktRegister;
import ch.urinet.archiv.mandant.application.MandantAufloesung;
import ch.urinet.archiv.mandant.application.MandantEinstellungen;
import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.sicherheit.domain.Benutzer;
import ch.urinet.archiv.sicherheit.domain.Sichtregel;
import ch.urinet.archiv.verarbeitung.application.AuftragService;
import ch.urinet.archiv.verarbeitung.application.EmbeddingSpeichern;
import ch.urinet.archiv.verarbeitung.application.VerarbeitenHandler;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.MonthDay;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Metadaten ändern (A6, Aufgabe 3.5; Req 2.12, 2.14, 2.23, 9.7, 10.1, 10.4): nur in {@code EINGANGSKORB},
 * {@code FEHLER} und {@code ARCHIVIERT}. Recht A6 (und A8 für Vertraulichkeit und {@code person_benutzer}) und die
 * Sichtbarkeit prüft die REST-Schicht vorher.
 * <p>
 * Bei {@code ARCHIVIERT} ist ein Grund Pflicht, die vorherige Fassung geht nach {@code dokument_version}, kein
 * Pflichtfeld der Art darf neu fehlen, und ändern sich Belegdatum, Art, Geschäftsjahr oder {@code gueltig_bis}, wird
 * die Frist neu berechnet: später oder unbefristet verlängert Frist und Sperre; früher ändert bei Sperre nichts und
 * wird als {@code SPERRE_KONFLIKT} protokolliert, ohne Sperre gilt das neue Ergebnis (design.md «Aufbewahrung»).
 * In {@code EINGANGSKORB} und {@code FEHLER} gelten Frist, Belegnummer und Pflichtfelder erst beim Bestätigen.
 * Ändern sich Titel, Art oder Partner eines archivierten Dokuments, reiht die Änderung {@code EMBEDDING} ein (Spec 02,
 * Aufgabe 6.3), weil diese Angaben in der Kopfzeile jedes Abschnitts stehen; ist für das Dokument schon eines offen,
 * kein zweites.
 */
@ApplicationScoped
@Transactional
public class DokumentAendern {

    public static final int GRUND_MAX = 1000;

    private static final Set<DokumentStatus> AENDERBAR = Set.of(DokumentStatus.EINGANGSKORB, DokumentStatus.FEHLER,
            DokumentStatus.ARCHIVIERT);
    private static final Pattern WAEHRUNG = Pattern.compile("^[A-Z]{3}$");
    private static final Set<String> RICHTUNGEN = Set.of("EINNAHME", "AUSGABE");
    private static final int GESCHAEFTSJAHR_MIN = 1900;
    private static final int GESCHAEFTSJAHR_MAX = 2999;
    private static final String FINANZ = "finanz";
    private static final String VERTRAG = "vertrag";

    private final DokumentRepository repository;
    private final DokumentAenderungPort port;
    private final DokumentStatusPort status;
    private final SperrKonfliktProtokollPort konfliktProtokoll;
    private final AblagePort ablage;
    private final DokumentartVerwaltung arten;
    private final KontaktRegister kontakte;
    private final MandantAufloesung aufloesung;
    private final MandantEinstellungen einstellungen;
    private final AuftragService auftraege;

    public DokumentAendern(DokumentRepository repository, DokumentAenderungPort port, DokumentStatusPort status,
                           SperrKonfliktProtokollPort konfliktProtokoll, AblagePort ablage,
                           DokumentartVerwaltung arten, KontaktRegister kontakte, MandantAufloesung aufloesung,
                           MandantEinstellungen einstellungen, AuftragService auftraege) {
        this.repository = repository;
        this.port = port;
        this.status = status;
        this.konfliktProtokoll = konfliktProtokoll;
        this.ablage = ablage;
        this.arten = arten;
        this.kontakte = kontakte;
        this.aufloesung = aufloesung;
        this.einstellungen = einstellungen;
        this.auftraege = auftraege;
    }

    /**
     * Die Angaben werden erst nach Sichtbarkeit, Version und Status gelesen: ein ungültiger Körper (400) überholt so
     * weder 412 noch 409 (design.md «Umsetzung (Aufgabe 3.5)», Reihenfolge).
     *
     * @return die neue Version des Dokuments (für das ETag)
     */
    public int aendern(Benutzer benutzer, UUID id, int erwarteteVersion, Supplier<Aenderung> angaben) {
        Dokument dokument = repository.finden(id, Sichtregel.fuer(benutzer))
                .orElseThrow(() -> new FachFehler(Fehlercode.NICHT_GEFUNDEN));
        if (dokument.version() != erwarteteVersion) {
            throw new FachFehler(Fehlercode.VERSION_KONFLIKT);
        }
        Metadaten alt = port.ladenUndSperren(id).orElseThrow(() -> new FachFehler(Fehlercode.NICHT_GEFUNDEN));
        if (alt.version() != erwarteteVersion) {
            throw new FachFehler(Fehlercode.VERSION_KONFLIKT);
        }
        if (!AENDERBAR.contains(alt.status())) {
            throw new FachFehler(Fehlercode.STATUS_KONFLIKT);
        }
        Aenderung a = angaben.get();
        boolean archiviert = alt.status() == DokumentStatus.ARCHIVIERT;
        String grund = a.grund() == null || a.grund().isBlank() ? null : a.grund().strip();
        if (archiviert && grund == null) {
            throw new PflichtfelderFehlen(List.of("grund"));
        }
        if (grund != null && grund.length() > GRUND_MAX) {
            throw new FachFehler(Fehlercode.ANFRAGE_UNGUELTIG);
        }

        werte(a, archiviert);
        String artCode = a.hat(Aenderung.ART) ? a.art() : alt.art();
        Dokumentart neueArt = artCode == null ? null
                : a.hat(Aenderung.ART) ? aktiveArt(artCode) : arten.finden(artCode);
        boolean artGewechselt = !Objects.equals(artCode, alt.art());
        gruppenPruefen(a, neueArt);
        String partnerName = !a.hat(Aenderung.PARTNER) ? alt.partnerName() : a.partnerId() == null ? null
                : DokumentBestaetigen.partnerName(kontakte, a.partnerId(), alt.partnerId());

        String zusatz = a.hat(Aenderung.ZUSATZ) ? a.zusatz() : alt.zusatz();
        if (zusatz != null && (a.hat(Aenderung.ZUSATZ) || artGewechselt)) {
            if (neueArt == null) {
                throw new FachFehler(Fehlercode.ZUSATZ_UNGUELTIG);
            }
            arten.zusatzPruefen(neueArt.code(), zusatz);
        }

        Metadaten neu = zusammenfuehren(alt, a, partnerName, zusatz);
        if (archiviert) {
            pflichtfelderPruefen(neueArt, alt, neu, artGewechselt);
        }

        MandantCode mandant = aufloesung.pruefen();
        if (archiviert && (a.hat("belegnummer") || artGewechselt)) {
            belegnummer(id, neueArt, neu, mandant);
        }
        if (archiviert) {
            neu = fristUndSperre(id, a, alt, neu, neueArt, artGewechselt, mandant);
            port.versionSichern(id, benutzer.id(), grund);
        }
        int version = port.speichern(neu);
        // ein schon offenes EMBEDDING liest beim Lauf den neuen Stand; die Zeilensperre des Dokuments
        // (ladenUndSperren) ordnet zwei Änderungen, die zweite sieht den Auftrag der ersten
        if (archiviert && kopfzeileGeaendert(alt, neu) && auftraege.hatHandler(EmbeddingSpeichern.EMBEDDING)
                && !auftraege.offenMit(EmbeddingSpeichern.EMBEDDING, VerarbeitenHandler.DOKUMENT_ID, id)) {
            auftraege.einreihen(EmbeddingSpeichern.EMBEDDING, EmbeddingSpeichern.einzeln(id));
        }
        return version;
    }

    /**
     * Titel, Art und Partner bilden die Kopfzeile jedes Abschnitts (Spec 02, design.md «Chunker»); ändert sich eines
     * davon an einem archivierten Dokument, werden die Abschnitte in derselben Transaktion neu eingereiht.
     */
    private static boolean kopfzeileGeaendert(Metadaten alt, Metadaten neu) {
        return !Objects.equals(alt.titel(), neu.titel()) || !Objects.equals(alt.art(), neu.art())
                || !Objects.equals(alt.partnerName(), neu.partnerName());
    }

    /** Unbekannte oder inaktive Art: 400, weil die Oberfläche nur aktive Arten anbietet (wie beim Bestätigen). */
    private Dokumentart aktiveArt(String code) {
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

    /** Werte mit festem Wertebereich und Felder, die nicht leer sein dürfen; sonst 400. */
    private static void werte(Aenderung a, boolean archiviert) {
        if (a.hat(Aenderung.VERTRAULICHKEIT)
                && (a.vertraulichkeit() == null || Vertraulichkeit.aus(a.vertraulichkeit()).isEmpty())
                || a.hat(Aenderung.WAEHRUNG) && (a.waehrung() == null || !WAEHRUNG.matcher(a.waehrung()).matches())
                || archiviert && a.hat(Aenderung.ART) && a.art() == null
                || archiviert && a.hat(Aenderung.GESCHAEFTSJAHR) && a.geschaeftsjahr() == null
                || a.richtung() != null && !RICHTUNGEN.contains(a.richtung())
                || a.geschaeftsjahr() != null
                && (a.geschaeftsjahr() < GESCHAEFTSJAHR_MIN || a.geschaeftsjahr() > GESCHAEFTSJAHR_MAX)
                || a.betrag() != null && (a.betrag().scale() > 2 || a.betrag().precision() - a.betrag().scale() > 10)
                || a.tags() != null && a.tags().stream().anyMatch(t -> t == null || t.isBlank())) {
            throw new FachFehler(Fehlercode.ANFRAGE_UNGUELTIG);
        }
    }

    /** Finanz- oder Vertragsfelder nur, wenn die Art (nach der Änderung) die Gruppe hat; sonst 400. */
    private static void gruppenPruefen(Aenderung a, Dokumentart art) {
        if (a.hatEines(Aenderung.FINANZ) && (art == null || !art.gruppen().contains(FINANZ))
                || a.hatEines(Aenderung.VERTRAG) && (art == null || !art.gruppen().contains(VERTRAG))) {
            throw new FachFehler(Fehlercode.ANFRAGE_UNGUELTIG);
        }
    }

    private static Metadaten zusammenfuehren(Metadaten alt, Aenderung a, String partnerName, String zusatz) {
        Optional<Finanz> finanz = alt.finanz();
        if (a.hatEines(Aenderung.FINANZ)) {
            Finanz f = alt.finanz().orElse(new Finanz(null, null, null, "CHF", null, null, null));
            finanz = Optional.of(new Finanz(wert(a, "belegnummer", a.belegnummer(), f.belegnummer()),
                    wert(a, "richtung", a.richtung(), f.richtung()), wert(a, "betrag", a.betrag(), f.betrag()),
                    wert(a, Aenderung.WAEHRUNG, a.waehrung(), f.waehrung()),
                    wert(a, "faelligkeit", a.faelligkeit(), f.faelligkeit()),
                    wert(a, "qrReferenz", a.qrReferenz(), f.qrReferenz()), wert(a, "konto", a.konto(), f.konto())));
        }
        Optional<Vertrag> vertrag = alt.vertrag();
        if (a.hatEines(Aenderung.VERTRAG)) {
            Vertrag v = alt.vertrag().orElse(new Vertrag(null, null, null, null));
            vertrag = Optional.of(new Vertrag(wert(a, "gueltigAb", a.gueltigAb(), v.gueltigAb()),
                    wert(a, Aenderung.GUELTIG_BIS, a.gueltigBis(), v.gueltigBis()),
                    wert(a, "kuendigungsfrist", a.kuendigungsfrist(), v.kuendigungsfrist()),
                    wert(a, "naechsteFrist", a.naechsteFrist(), v.naechsteFrist())));
        }
        return new Metadaten(alt.id(), alt.status(), alt.version(), alt.eingang(),
                wert(a, Aenderung.ART, a.art(), alt.art()), wert(a, Aenderung.TITEL, a.titel(), alt.titel()),
                wert(a, Aenderung.DATUM, a.datum(), alt.datum()),
                wert(a, Aenderung.PARTNER, a.partnerId(), alt.partnerId()), partnerName,
                wert(a, Aenderung.PROJEKT, a.projekt(), alt.projekt()), wert(a, Aenderung.TAGS, a.tags(), alt.tags()),
                wert(a, Aenderung.VERTRAULICHKEIT, a.vertraulichkeit(), alt.vertraulichkeit()),
                wert(a, Aenderung.PERSON, a.personBenutzer(), alt.personBenutzer()),
                wert(a, Aenderung.GESCHAEFTSJAHR, a.geschaeftsjahr(), alt.geschaeftsjahr()), alt.aufbewahrenBis(),
                alt.fristAbgelaufen(), alt.sperreBis(), zusatz,
                a.hat(Aenderung.ZUSATZ) ? a.zusatzFelder() : alt.zusatzFelder(), finanz, vertrag);
    }

    private static <T> T wert(Aenderung a, String feld, T neu, T bisher) {
        return a.hat(feld) ? neu : bisher;
    }

    /**
     * Archiviert darf kein Pflichtfeld der Art neu fehlen; nach einem Wechsel der Art gelten alle Pflichtfelder der
     * neuen Art (Req 8.3). Ein Pflichtfeld, das schon vorher fehlte, bleibt erlaubt (Altbestand).
     */
    static void pflichtfelderPruefen(Dokumentart art, Metadaten alt, Metadaten neu, boolean artGewechselt) {
        if (art == null) {
            return;
        }
        List<String> fehlen = new ArrayList<>();
        for (String feld : art.pflichtfelder()) {
            if (!vorhanden(feld, neu) && (artGewechselt || vorhanden(feld, alt))) {
                fehlen.add(feld);
            }
        }
        if (!fehlen.isEmpty()) {
            throw new PflichtfelderFehlen(fehlen);
        }
    }

    private static boolean vorhanden(String feld, Metadaten m) {
        if (feld.startsWith(Dokumentart.ZUSATZ)) {
            return m.zusatzFelder().contains(feld.substring(Dokumentart.ZUSATZ.length()));
        }
        Optional<Finanz> f = m.finanz();
        Optional<Vertrag> v = m.vertrag();
        Object wert = switch (feld) {
            case "titel" -> m.titel();
            case "datum" -> m.datum();
            case "partner" -> m.partnerId();
            case "projekt" -> m.projekt();
            case "tags" -> m.tags().isEmpty() ? null : m.tags();
            case "vertraulichkeit" -> m.vertraulichkeit();
            case "person_benutzer" -> m.personBenutzer();
            case "belegnummer" -> f.map(Finanz::belegnummer).orElse(null);
            case "richtung" -> f.map(Finanz::richtung).orElse(null);
            case "betrag" -> f.map(Finanz::betrag).orElse(null);
            case "waehrung" -> f.map(Finanz::waehrung).orElse(null);
            case "faelligkeit" -> f.map(Finanz::faelligkeit).orElse(null);
            case "qr_referenz" -> f.map(Finanz::qrReferenz).orElse(null);
            case "konto" -> f.map(Finanz::konto).orElse(null);
            case "gueltig_ab" -> v.map(Vertrag::gueltigAb).orElse(null);
            case "gueltig_bis" -> v.map(Vertrag::gueltigBis).orElse(null);
            case "kuendigungsfrist" -> v.map(Vertrag::kuendigungsfrist).orElse(null);
            case "naechste_frist" -> v.map(Vertrag::naechsteFrist).orElse(null);
            default -> throw new IllegalStateException("Unbekanntes Pflichtfeld der Art: " + feld);
        };
        return wert != null;
    }

    /** Belegnummer eigener Arten mit Gruppe {@code finanz} bleibt eindeutig (Req 8.8); sonst wird sie freigegeben. */
    private void belegnummer(UUID id, Dokumentart art, Metadaten neu, MandantCode mandant) {
        String nummer = neu.finanz().map(Finanz::belegnummer).orElse(null);
        boolean eigeneFinanz = art != null && art.gruppen().contains(FINANZ)
                && einstellungen.lesen(mandant).eigeneArten().contains(art.code());
        if (eigeneFinanz && nummer != null) {
            Optional<UUID> inhaber = status.belegnummerInhaber(art.code(), nummer);
            if (inhaber.isPresent() && !inhaber.get().equals(id)
                    || !status.belegnummerReservieren(art.code(), nummer, id)) {
                throw new FachFehler(Fehlercode.BELEGNUMMER_VERGEBEN);
            }
        } else {
            status.belegnummerFreigeben(id);
        }
    }

    /** Neuberechnung nach Req 9.7 (design.md «Aufbewahrung», «Ablage»). */
    private Metadaten fristUndSperre(UUID id, Aenderung a, Metadaten alt, Metadaten neu, Dokumentart art,
                                     boolean artGewechselt, MandantCode mandant) {
        boolean ausloeser = a.hat(Aenderung.DATUM) || artGewechselt || a.hat(Aenderung.GESCHAEFTSJAHR)
                || a.hat(Aenderung.GUELTIG_BIS);
        if (!ausloeser || art == null) {
            return neu;
        }
        MonthDay gjEnde = einstellungen.lesen(mandant).geschaeftsjahresende().monatTag();
        boolean vertrag = art.gruppen().contains(VERTRAG);
        LocalDate gueltigBis = vertrag ? neu.vertrag().map(Vertrag::gueltigBis).orElse(null) : null;
        Aufbewahrung berechnet = Aufbewahrung.berechne(art.aufbewahrung(), neu.datum(), alt.eingang(),
                neu.geschaeftsjahr(), gueltigBis, gjEnde, LocalDate.now(Aufbewahrung.ZUERICH));
        boolean altPflicht = alt.art() != null
                && (artGewechselt ? arten.finden(alt.art()).gesetzlichePflicht() : art.gesetzlichePflicht());
        boolean gesperrt = altPflicht || art.gesetzlichePflicht() || alt.sperreBis() != null;
        Neuberechnung entscheid = Aufbewahrung.entscheiden(alt.aufbewahrenBis(), berechnet.bis(), gesperrt);

        LocalDate bis = alt.aufbewahrenBis();
        boolean abgelaufen = alt.fristAbgelaufen();
        switch (entscheid) {
            case GLEICH -> {
            }
            case VERLAENGERN, FRUEHER_UEBERNEHMEN -> {
                bis = berechnet.bis();
                abgelaufen = berechnet.fristAbgelaufen();
            }
            case KONFLIKT -> konfliktProtokoll.sperreKonflikt(id, alt.aufbewahrenBis(), berechnet.bis());
        }
        LocalDate sperreBis = alt.sperreBis();
        // verlängern, oder bei einer Art mit gesetzlicher Pflicht ohne Sperre jetzt sperren (auch beim Konflikt mit
        // der bisherigen, späteren Frist)
        boolean sperrbar = art.gesetzlichePflicht() || alt.sperreBis() != null;
        boolean setzen = entscheid == Neuberechnung.VERLAENGERN
                || art.gesetzlichePflicht() && alt.sperreBis() == null;
        if (sperrbar && setzen) {
            List<AbgelegteDatei> dateien = port.archivDateien(id);
            if (!dateien.isEmpty()) {
                List<SperrAenderung> ergebnis = ablage.sperreVerlaengern(mandant, dateien,
                        new Sperrvorgabe(true, bis, abgelaufen));
                sperreBis = spaeter(sperreBis, letzterTag(ergebnis));
            }
        }
        return new Metadaten(neu.id(), neu.status(), neu.version(), neu.eingang(), neu.art(), neu.titel(),
                neu.datum(), neu.partnerId(), neu.partnerName(), neu.projekt(), neu.tags(), neu.vertraulichkeit(),
                neu.personBenutzer(), neu.geschaeftsjahr(), bis, abgelaufen, sperreBis, neu.zusatz(),
                neu.zusatzFelder(), neu.finanz(), neu.vertrag());
    }

    /** Letzter gesperrter Tag in Europe/Zurich über alle Dateien: die Sperre läuft bis zum Beginn des Folgetags. */
    static Optional<LocalDate> letzterTag(List<SperrAenderung> ergebnis) {
        return ergebnis.stream().map(SperrAenderung::sperreBis).flatMap(Optional::stream).max(Instant::compareTo)
                .map(i -> i.minusNanos(1).atZone(Aufbewahrung.ZUERICH).toLocalDate());
    }

    private static LocalDate spaeter(LocalDate bisher, Optional<LocalDate> neu) {
        return neu.filter(n -> bisher == null || n.isAfter(bisher)).orElse(bisher);
    }
}
