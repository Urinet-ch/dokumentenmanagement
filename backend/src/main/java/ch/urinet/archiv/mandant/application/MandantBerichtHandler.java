package ch.urinet.archiv.mandant.application;

import ch.urinet.archiv.mandant.domain.MandantBericht;
import ch.urinet.archiv.mandant.domain.MandantBerichtFenster;
import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.verarbeitung.application.Auftrag;
import ch.urinet.archiv.verarbeitung.application.AuftragHandler;
import ch.urinet.archiv.verarbeitung.domain.AuftragTyp;
import ch.urinet.archiv.verarbeitung.domain.Nutzlast;
import jakarta.enterprise.context.ApplicationScoped;

import java.time.LocalDate;
import java.time.YearMonth;

/**
 * Handler für {@code MANDANT_BERICHT}; gespeicherte Kennzahlen bleiben bei Mailfehlern wiederverwendbar.
 * Die Betreiber-Mail ist bewusst mindestens-einmal: Versand und Versandmarke sind nicht atomar. Scheitert die Marke
 * nach erfolgreichem Versand, geht dieselbe Mail bei der Wiederholung erneut an den Betreiber (nur Zahlen).
 */
@ApplicationScoped
public class MandantBerichtHandler implements AuftragHandler {

    private final MandantBerichtDaten daten;
    private final MandantBerichtSpeicher speicher;
    private final MandantBerichtMail mail;

    public MandantBerichtHandler(MandantBerichtDaten daten, MandantBerichtSpeicher speicher,
                                 MandantBerichtMail mail) {
        this.daten = daten;
        this.speicher = speicher;
        this.mail = mail;
    }

    @Override
    public AuftragTyp typ() {
        return MandantBerichtEinreihen.TYP;
    }

    @Override
    public void ausfuehren(Auftrag auftrag) {
        YearMonth monat = monat(auftrag.nutzlast());
        MandantCode mandant = auftrag.mandant();
        MandantBerichtSpeicher.Eintrag eintrag = speicher.lesen(monat)
                .orElseGet(() -> speicher.speichernFallsFehlt(
                        daten.erheben(mandant, MandantBerichtFenster.fuer(monat))));
        if (!eintrag.mailVersandt() && mail.senden(eintrag.bericht())) {
            speicher.mailVersandt(monat);
        }
    }

    private static YearMonth monat(Nutzlast nutzlast) {
        long epochDay = nutzlast.zahl(MandantBerichtEinreihen.MONAT)
                .orElseThrow(() -> new IllegalArgumentException("Monat des Mandantenberichts fehlt"));
        LocalDate ersterTag = LocalDate.ofEpochDay(epochDay);
        if (ersterTag.getDayOfMonth() != 1) {
            throw new IllegalArgumentException("Monat des Mandantenberichts ist ungültig");
        }
        return YearMonth.from(ersterTag);
    }
}
