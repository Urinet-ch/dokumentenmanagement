package ch.urinet.archiv.wissen.application;

import ch.urinet.archiv.mandant.application.MandantKontext;
import ch.urinet.archiv.mandant.application.MandantVerzeichnis;
import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.protokoll.application.ZugriffProtokoll;
import ch.urinet.archiv.protokoll.domain.ProtokollAktion;
import ch.urinet.archiv.protokoll.domain.ProtokollDetails;
import ch.urinet.archiv.protokoll.domain.ZugriffEintrag;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Optional;

/**
 * Wissen je Mandant ein- oder ausschalten (Spec 04, Req 22.2): einziger Weg neben der Migration (Req 22.3), aufgerufen
 * nur vom Betreiber-Kommando {@code archiv mandant wissen <code> [ein|aus]}. Zustand und Protokolleintrag (Akteur
 * Betreiber, Aktion {@code WISSEN_FREIGESCHALTET} bzw. {@code WISSEN_ABGESCHALTET}, ohne Inhalte) in einer Transaktion;
 * entspricht der Zustand schon dem Wunsch, ändert und protokolliert es nichts. Gelöscht wird nichts (Req 22.10).
 */
@ApplicationScoped
public class WissenFreischalten {

    /** Kennung des Akteurs im Zugriffsprotokoll: der Betreiber über das Kommando, keine Person des Mandanten. */
    public static final String BETREIBER = "betreiber";

    public enum Ergebnis { FREIGESCHALTET, ABGESCHALTET, UNVERAENDERT }

    private final WissensfreischaltungPort port;
    private final MandantVerzeichnis verzeichnis;
    private final MandantKontext kontext;
    private final ZugriffProtokoll protokoll;
    private final Wissensfreischaltung freischaltung;
    private final WissenFreischaltenTransaktion transaktion;

    public WissenFreischalten(WissensfreischaltungPort port, MandantVerzeichnis verzeichnis, MandantKontext kontext,
                              ZugriffProtokoll protokoll, Wissensfreischaltung freischaltung,
                              WissenFreischaltenTransaktion transaktion) {
        this.transaktion = transaktion;
        this.port = port;
        this.verzeichnis = verzeichnis;
        this.kontext = kontext;
        this.protokoll = protokoll;
        this.freischaltung = freischaltung;
    }

    /** Zustand eines aktiven Mandanten; leer, wenn er unbekannt oder nicht aktiv ist. */
    public Optional<Boolean> zustand(MandantCode mandant) {
        return verzeichnis.aktivesSchema(mandant).isEmpty() ? Optional.empty() : port.lesen(mandant);
    }

    /** Setzt den Zustand; leer, wenn der Mandant unbekannt oder nicht aktiv ist (dann ändert sich nichts). */
    public Optional<Ergebnis> setzen(MandantCode mandant, boolean freigeschaltet) {
        if (verzeichnis.aktivesSchema(mandant).isEmpty()) {
            return Optional.empty();
        }
        Optional<Ergebnis> ergebnis = kontext.mit(mandant, () -> transaktion.ausfuehren(() -> {
            Optional<Boolean> bisher = port.sperrenUndLesen(mandant);
            if (bisher.isEmpty()) {
                return Optional.<Ergebnis>empty();
            }
            if (bisher.get() == freigeschaltet) {
                return Optional.of(Ergebnis.UNVERAENDERT);
            }
            port.setzen(mandant, freigeschaltet);
            protokoll.schreiben(new ZugriffEintrag(Optional.of(BETREIBER), Optional.empty(),
                    freigeschaltet ? ProtokollAktion.WISSEN_FREIGESCHALTET : ProtokollAktion.WISSEN_ABGESCHALTET,
                    Optional.empty(), Optional.empty(), ProtokollDetails.KEINE));
            return Optional.of(freigeschaltet ? Ergebnis.FREIGESCHALTET : Ergebnis.ABGESCHALTET);
        }));
        freischaltung.vergessen(mandant);
        return ergebnis;
    }
}
