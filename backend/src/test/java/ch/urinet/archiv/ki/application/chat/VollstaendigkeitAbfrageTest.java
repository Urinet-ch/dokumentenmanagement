package ch.urinet.archiv.ki.application.chat;

import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.sicherheit.domain.Benutzer;
import ch.urinet.archiv.sicherheit.domain.Rolle;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Spec 02, Req 3.6, 4.2, 5.2 (Aufgabe 7.1c): Prüfung der Rolle in der Methode, Grenzen, ausdrückliche Lücke. */
class VollstaendigkeitAbfrageTest {

    private static final MandantCode URINET = new MandantCode("urinet");
    private static final Clock HEUTE = Clock.fixed(Instant.parse("2026-11-14T10:00:00Z"), ZoneId.of("Europe/Zurich"));

    private final List<String> aufrufe = new ArrayList<>();
    private List<Lueckenbericht.Kategorie> kategorien = List.of();

    private VollstaendigkeitAbfrage abfrage() {
        Lueckenbericht bericht = (mandant, jahr, sicht) -> {
            aufrufe.add(mandant.wert() + ":" + jahr);
            return new Lueckenbericht.Bericht(jahr, kategorien, 12);
        };
        WerkzeugDaten daten = (WerkzeugDaten) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{WerkzeugDaten.class}, (proxy, methode, args) -> null);
        return new VollstaendigkeitAbfrage(bericht, daten, new WerkzeugTransaktion(), HEUTE);
    }

    private static WerkzeugKontext kontext(Rolle rolle) {
        return WerkzeugKontext.fuer(URINET, new Benutzer("b", rolle), null);
    }

    @Test
    void jedeMethodePrueftDieRolleNochmals() {
        for (Rolle rolle : Rolle.values()) {
            boolean erlaubt = new WerkzeugAuswahl().erlaubt(kontext(rolle), WerkzeugAuswahl.VOLLSTAENDIGKEIT);
            if (erlaubt) {
                assertThat(abfrage().vollstaendigkeit(kontext(rolle), 2026)).as(rolle.name()).isNotNull();
            } else {
                assertThatThrownBy(() -> abfrage().vollstaendigkeit(kontext(rolle), 2026)).as(rolle.name())
                        .isInstanceOf(WerkzeugFehler.class).hasMessage(WerkzeugFehler.NICHT_VERFUEGBAR);
            }
        }
        assertThat(aufrufe).as("für eine nicht berechtigte Rolle kein Zugriff").hasSize(3);
    }

    @Test
    void geschaeftsjahrInSinnvollenGrenzen() {
        WerkzeugKontext inhaber = kontext(Rolle.INHABER);
        for (Integer jahr : new Integer[]{null, 1999, 0, -2026, 2028, 20260}) {
            assertThatThrownBy(() -> abfrage().vollstaendigkeit(inhaber, jahr)).as(String.valueOf(jahr))
                    .hasMessage("ungültiger Parameter: geschaeftsjahr");
        }
        assertThat(abfrage().vollstaendigkeit(inhaber, 2000).geschaeftsjahr()).isEqualTo(2000);
        assertThat(abfrage().vollstaendigkeit(inhaber, 2027).geschaeftsjahr()).isEqualTo(2027);
        assertThat(aufrufe).containsExactly("urinet:2000", "urinet:2027");
    }

    @Test
    void antwortNenntDatenstandUndDieLueckeNurMitAbaNinja() {
        VollstaendigkeitAbfrage.Antwort a = abfrage().vollstaendigkeit(kontext(Rolle.TREUHAENDER), 2026);
        assertThat(a.nichtGeprueft()).containsExactly("Lieferantenbelege ohne Archivdokument: nur mit AbaNinja");
        assertThat(a.datenstand().anzahl()).isEqualTo(12);
        assertThat(a.datenstand().zeitpunkt()).isEqualTo("14.11.2026 11:00");
        assertThat(a.hinweis()).as("ohne Kategorien nie «vollständig» behaupten").isEqualTo(
                VollstaendigkeitAbfrage.KEINE_REGELN);
        kategorien = List.of(new Lueckenbericht.Kategorie(Lueckenbericht.Regel.FOLGE, "X", null, 0, null, 0, List.of()));
        assertThat(abfrage().vollstaendigkeit(kontext(Rolle.INHABER), 2026).hinweis()).isNull();
    }
}
