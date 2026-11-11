package ch.urinet.archiv.dokumentart.application;

import ch.urinet.archiv.dokumentart.application.DokumentartAngaben.Feld;
import ch.urinet.archiv.dokumentart.domain.AufbewahrungAb;
import ch.urinet.archiv.dokumentart.domain.Aufbewahrungsregel;
import ch.urinet.archiv.dokumentart.domain.Dokumentart;
import ch.urinet.archiv.dokumentart.domain.Vertraulichkeit;
import ch.urinet.archiv.gemeinsam.domain.FachFehler;
import ch.urinet.archiv.gemeinsam.domain.Fehlercode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import java.util.List;

/**
 * Dokumentarten des eigenen Mandanten verwalten (Req 9.1, 9.4, 9.5, 9.10; Recht A11 prüft die REST-Schicht). Der Mandant
 * kommt nur aus der Anmeldung: Das Repository arbeitet im Schema der laufenden Anfrage.
 */
@ApplicationScoped
@Transactional
public class DokumentartVerwaltung {

    private final DokumentartRepository repository;
    private final ZusatzSchemaPruefer schemaPruefer;
    private final DokumentartPruefung pruefung;
    private final DokumentZaehlerPort zaehler;
    private final RegelProtokollPort regelProtokoll;

    public DokumentartVerwaltung(DokumentartRepository repository, ZusatzSchemaPruefer schemaPruefer,
                                 DokumentartPruefung pruefung, DokumentZaehlerPort zaehler,
                                 RegelProtokollPort regelProtokoll) {
        this.repository = repository;
        this.schemaPruefer = schemaPruefer;
        this.pruefung = pruefung;
        this.zaehler = zaehler;
        this.regelProtokoll = regelProtokoll;
    }

    public List<Dokumentart> alle() {
        return repository.alle();
    }

    public Dokumentart finden(String code) {
        return repository.finden(code).orElseThrow(() -> new FachFehler(Fehlercode.NICHT_GEFUNDEN));
    }

    /**
     * Legt eine Art an. Pflicht sind {@code code}, {@code name}, {@code vertraulichkeit} und
     * {@code gesetzlichePflicht}. Ohne {@code aufbewahrungJahre} gelten {@value Aufbewahrungsregel#JAHRE_STANDARD}
     * Jahre (Req 9.3), ohne {@code aufbewahrungAb} das Geschäftsjahr; ausdrücklich {@code null} heisst unbefristet.
     * Ohne Schema gilt {@link Dokumentart#OHNE_ZUSATZ}. Ein vergebener Code ergibt 409
     * {@code DOKUMENTART_EXISTIERT}.
     */
    public Dokumentart anlegen(DokumentartAngaben angaben) {
        for (Feld pflicht : List.of(Feld.CODE, Feld.NAME, Feld.VERTRAULICHKEIT, Feld.GESETZLICHE_PFLICHT)) {
            if (!angaben.hat(pflicht)) {
                throw ungueltig();
            }
        }
        Integer jahre = angaben.hat(Feld.AUFBEWAHRUNG_JAHRE) ? angaben.aufbewahrungJahre()
                : Integer.valueOf(Aufbewahrungsregel.JAHRE_STANDARD);
        String ab = angaben.hat(Feld.AUFBEWAHRUNG_AB) ? angaben.aufbewahrungAb()
                : jahre == null ? null : AufbewahrungAb.GESCHAEFTSJAHR.name();
        Dokumentart art = new Dokumentart(angaben.code(), angaben.name(),
                angaben.hat(Feld.BESCHREIBUNG) ? angaben.beschreibung() : null,
                angaben.hat(Feld.GRUPPEN) ? pflichtwert(angaben.gruppen()) : List.of(),
                angaben.hat(Feld.PFLICHTFELDER) ? pflichtwert(angaben.pflichtfelder()) : List.of(),
                angaben.hat(Feld.ZUSATZ_SCHEMA) ? pflichtwert(angaben.zusatzSchema()) : Dokumentart.OHNE_ZUSATZ,
                vertraulichkeit(angaben.vertraulichkeit()),
                regel(jahre, ab),
                pflichtwert(angaben.gesetzlichePflicht()),
                !angaben.hat(Feld.AKTIV) || pflichtwert(angaben.aktiv()));
        pruefung.pruefen(art);
        if (repository.existiert(art.code())) {
            throw new FachFehler(Fehlercode.DOKUMENTART_EXISTIERT);
        }
        repository.anlegen(art);
        return art;
    }

    /**
     * Ändert die angegebenen Felder; der Code bleibt. {@code aktiv = false} deaktiviert die Art (Req 9.4). Wird
     * {@code aufbewahrungJahre} auf {@code null} gesetzt, entfällt {@code aufbewahrungAb} mit. Die Aufbewahrungsregel
     * (Jahre, «ab») darf sich auch ändern, wenn die Art schon Dokumente hat; jede solche Änderung kommt mit alter und
     * neuer Regel ins Zugriffsprotokoll (Req 9.10, entschieden von Fabian am 10.11.2026). Eine Änderung der
     * gesetzlichen Pflicht wird ebenfalls protokolliert; auch eine pflichtige Art darf gesenkt werden (Pflicht auf
     * «nein», weniger als 10 Jahre), sie wird dabei nur protokolliert (entschieden von Fabian am 11.11.2026). Sie
     * wirkt auf neue Dokumente und Neuberechnungen nach Req 9.7, bestehende Sperren werden nur verlängert.
     */
    public Dokumentart aendern(String code, DokumentartAngaben angaben) {
        Dokumentart bisher = finden(code);
        if (angaben.hat(Feld.CODE) && !code.equals(angaben.code())) {
            throw ungueltig();
        }
        Integer jahre = angaben.hat(Feld.AUFBEWAHRUNG_JAHRE) ? angaben.aufbewahrungJahre() : bisher.aufbewahrung().jahre();
        String ab = angaben.hat(Feld.AUFBEWAHRUNG_AB) ? angaben.aufbewahrungAb()
                : jahre == null ? null : name(bisher.aufbewahrung().ab());
        Dokumentart art = new Dokumentart(code,
                angaben.hat(Feld.NAME) ? angaben.name() : bisher.name(),
                angaben.hat(Feld.BESCHREIBUNG) ? angaben.beschreibung() : bisher.beschreibung(),
                angaben.hat(Feld.GRUPPEN) ? pflichtwert(angaben.gruppen()) : bisher.gruppen(),
                angaben.hat(Feld.PFLICHTFELDER) ? pflichtwert(angaben.pflichtfelder()) : bisher.pflichtfelder(),
                angaben.hat(Feld.ZUSATZ_SCHEMA) ? pflichtwert(angaben.zusatzSchema()) : bisher.zusatzSchema(),
                angaben.hat(Feld.VERTRAULICHKEIT) ? vertraulichkeit(angaben.vertraulichkeit()) : bisher.vertraulichkeit(),
                regel(jahre, ab),
                angaben.hat(Feld.GESETZLICHE_PFLICHT) ? pflichtwert(angaben.gesetzlichePflicht())
                        : bisher.gesetzlichePflicht(),
                angaben.hat(Feld.AKTIV) ? pflichtwert(angaben.aktiv()) : bisher.aktiv());
        pruefung.pruefen(art);
        if (!art.aufbewahrung().equals(bisher.aufbewahrung()) || art.gesetzlichePflicht() != bisher.gesetzlichePflicht()) {
            regelProtokoll.regelGeaendert(bisher, art);
        }
        repository.speichern(art);
        return art;
    }

    /** Löscht eine Art ohne Dokumente; sonst 409 {@code DOKUMENTART_IN_GEBRAUCH}, erlaubt bleibt «inaktiv» (Req 9.4). */
    public void loeschen(String code) {
        finden(code);
        if (zaehler.anzahl(code) > 0) {
            throw new FachFehler(Fehlercode.DOKUMENTART_IN_GEBRAUCH);
        }
        repository.loeschen(code);
    }

    /** Prüft Zusatzwerte (JSON-Objekt) gegen das Schema der Art (Req 9.5); für das Speichern von Dokumenten (2.2). */
    public void zusatzPruefen(String code, String werteJson) {
        schemaPruefer.wertePruefen(finden(code).zusatzSchema(), werteJson);
    }

    private static Aufbewahrungsregel regel(Integer jahre, String ab) {
        if (ab == null) {
            return new Aufbewahrungsregel(jahre, null);
        }
        return new Aufbewahrungsregel(jahre, AufbewahrungAb.aus(ab).orElseThrow(DokumentartVerwaltung::ungueltig));
    }

    private static Vertraulichkeit vertraulichkeit(String text) {
        return Vertraulichkeit.aus(text).orElseThrow(DokumentartVerwaltung::ungueltig);
    }

    private static String name(Enum<?> wert) {
        return wert == null ? null : wert.name();
    }

    private static <T> T pflichtwert(T wert) {
        if (wert == null) {
            throw ungueltig();
        }
        return wert;
    }

    private static FachFehler ungueltig() {
        return new FachFehler(Fehlercode.ANFRAGE_UNGUELTIG);
    }
}
