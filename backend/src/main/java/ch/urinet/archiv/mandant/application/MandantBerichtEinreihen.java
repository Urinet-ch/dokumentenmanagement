package ch.urinet.archiv.mandant.application;

import ch.urinet.archiv.mandant.domain.MandantBerichtFenster;
import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.verarbeitung.application.AuftragService;
import ch.urinet.archiv.verarbeitung.domain.AuftragTyp;
import ch.urinet.archiv.verarbeitung.domain.Nutzlast;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;

/** Reiht höchstens einen offenen Monatsbericht je aktivem Mandanten und Monat ein. */
@ApplicationScoped
public class MandantBerichtEinreihen {

    public static final AuftragTyp TYP = new AuftragTyp("MANDANT_BERICHT");
    public static final String MONAT = "monat";

    private final AuftragService auftraege;
    private final MandantVerzeichnis verzeichnis;
    private final MandantBerichtSpeicher speicher;

    public MandantBerichtEinreihen(AuftragService auftraege, MandantVerzeichnis verzeichnis,
                                   MandantBerichtSpeicher speicher) {
        this.auftraege = auftraege;
        this.verzeichnis = verzeichnis;
        this.speicher = speicher;
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public OptionalLong einreihen(MandantCode mandant, MandantBerichtFenster fenster) {
        if (verzeichnis.aktivesSchema(mandant).isEmpty()
                || speicher.lesen(fenster.monat()).filter(MandantBerichtSpeicher.Eintrag::mailVersandt).isPresent()) {
            return OptionalLong.empty();
        }
        if (auftraege.offen(TYP, Optional.of(MONAT), true).isPresent()) {
            return OptionalLong.empty();
        }
        long id = auftraege.einreihen(TYP, new Nutzlast(Map.of(MONAT, fenster.ersterTag().toEpochDay())));
        return OptionalLong.of(id);
    }
}
