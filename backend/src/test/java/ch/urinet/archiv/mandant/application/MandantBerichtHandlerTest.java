package ch.urinet.archiv.mandant.application;

import ch.urinet.archiv.mandant.domain.MandantBericht;
import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.verarbeitung.application.Auftrag;
import ch.urinet.archiv.verarbeitung.domain.Nutzlast;
import org.junit.jupiter.api.Test;

import java.time.YearMonth;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class MandantBerichtHandlerTest {

    @Test
    void speichertVorDemMailversandUndVerwendetDenBerichtBeimWiederholen() {
        YearMonth monat = YearMonth.of(2071, 2);
        MandantCode mandant = new MandantCode("urinet");
        MandantBericht bericht = new MandantBericht(mandant, monat, 10, Map.of("NEU", 1L),
                Map.of("ERFASSUNG", new MandantBericht.Tokens(2, 3)), 4);
        AtomicInteger erhebungen = new AtomicInteger();
        AtomicInteger speicherungen = new AtomicInteger();
        AtomicInteger mails = new AtomicInteger();
        MandantBerichtSpeicher speicher = new MandantBerichtSpeicher() {
            private Eintrag eintrag;

            @Override
            public Optional<Eintrag> lesen(YearMonth angefragt) {
                return Optional.ofNullable(eintrag);
            }

            @Override
            public Eintrag speichernFallsFehlt(MandantBericht wert) {
                speicherungen.incrementAndGet();
                eintrag = new Eintrag(wert, false);
                return eintrag;
            }

            @Override
            public void mailVersandt(YearMonth angefragt) {
                eintrag = new Eintrag(eintrag.bericht(), true);
            }
        };
        MandantBerichtDaten daten = (code, fenster) -> {
            erhebungen.incrementAndGet();
            return bericht;
        };
        MandantBerichtMail mail = wert -> mails.incrementAndGet() > 1;
        MandantBerichtHandler handler = new MandantBerichtHandler(daten, speicher, mail);
        Auftrag auftrag = new Auftrag(1, mandant, MandantBerichtEinreihen.TYP,
                new Nutzlast(Map.of(MandantBerichtEinreihen.MONAT, monat.atDay(1).toEpochDay())), 0);

        handler.ausfuehren(auftrag);
        assertThat(speicher.lesen(monat).orElseThrow().mailVersandt()).isFalse();
        handler.ausfuehren(auftrag);

        assertThat(erhebungen).hasValue(1);
        assertThat(speicherungen).hasValue(1);
        assertThat(mails).hasValue(2);
        MandantBerichtSpeicher.Eintrag eintrag = speicher.lesen(monat).orElseThrow();
        assertThat(eintrag.bericht()).isSameAs(bericht);
        assertThat(eintrag.mailVersandt()).isTrue();
    }
}
