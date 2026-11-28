package ch.urinet.archiv.wissen.application;

import ch.urinet.archiv.gemeinsam.domain.FachFehler;
import ch.urinet.archiv.gemeinsam.domain.Fehlercode;
import ch.urinet.archiv.mandant.application.MandantAusAnmeldung;
import ch.urinet.archiv.mandant.application.MandantKontext;
import ch.urinet.archiv.mandant.domain.MandantCode;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Spec 04, Req 22.2 (Wirkung nach höchstens 60 s; das Archiv speichert höchstens 30 s zwischen, die App lädt höchstens alle
 * 30 s neu) und design.md «Eine Stelle»: Zwischenspeicher und fail closed.
 */
@Tag("req-22.2")
class WissensfreischaltungTest {

    private static final MandantCode URINET = new MandantCode("urinet");
    private static final MandantCode ANDERER = new MandantCode("anderer");

    private final Map<MandantCode, Boolean> register = new HashMap<>();
    private final AtomicInteger lesungen = new AtomicInteger();
    private final AtomicLong uhr = new AtomicLong(1_000);
    private boolean kaputt;

    private final WissensfreischaltungPort port = new WissensfreischaltungPort() {
        @Override
        public Optional<Boolean> lesen(MandantCode mandant) {
            lesungen.incrementAndGet();
            if (kaputt) {
                throw new IllegalStateException("Datenbank weg");
            }
            return Optional.ofNullable(register.get(mandant));
        }

        @Override
        public Optional<Boolean> sperrenUndLesen(MandantCode mandant) {
            return lesen(mandant);
        }

        @Override
        public void setzen(MandantCode mandant, boolean freigeschaltet) {
            register.put(mandant, freigeschaltet);
        }
    };

    private Wissensfreischaltung freischaltung(Duration zwischenspeicher) {
        return new Wissensfreischaltung(port, null, null, zwischenspeicher, uhr::get);
    }

    @Test
    void eineAenderungWirktSpaetestensNach30Sekunden() {
        register.put(URINET, true);
        Wissensfreischaltung freischaltung = freischaltung(Duration.ofSeconds(30));
        assertThat(freischaltung.freigeschaltet(URINET)).isTrue();
        register.put(URINET, false);
        uhr.addAndGet(TimeUnit.SECONDS.toNanos(29));
        assertThat(freischaltung.freigeschaltet(URINET)).as("noch zwischengespeichert").isTrue();
        uhr.addAndGet(TimeUnit.SECONDS.toNanos(1));
        assertThat(freischaltung.freigeschaltet(URINET)).as("nach 30 s neu gelesen").isFalse();
        assertThat(lesungen).hasValue(2);
    }

    @Test
    void vergessenLiestSofortNeu() {
        register.put(URINET, false);
        Wissensfreischaltung freischaltung = freischaltung(Duration.ofSeconds(30));
        assertThat(freischaltung.freigeschaltet(URINET)).isFalse();
        register.put(URINET, true);
        freischaltung.vergessen(URINET);
        assertThat(freischaltung.freigeschaltet(URINET)).isTrue();
    }

    @Test
    void unbekannterMandantUndLesefehlerGeltenAlsNichtFreigeschaltet() {
        Wissensfreischaltung freischaltung = freischaltung(Duration.ofSeconds(30));
        assertThat(freischaltung.freigeschaltet(ANDERER)).isFalse();
        register.put(ANDERER, true);
        assertThat(freischaltung.freigeschaltet(ANDERER)).as("unbekannt wird nicht zwischengespeichert").isTrue();
        register.put(URINET, true);
        kaputt = true;
        assertThat(freischaltung.freigeschaltet(URINET)).isFalse();
        kaputt = false;
        assertThat(freischaltung.freigeschaltet(URINET)).as("Fehler wird nicht zwischengespeichert").isTrue();
    }

    @Test
    void ohneZwischenspeicherJedesMalNeu() {
        register.put(URINET, true);
        Wissensfreischaltung freischaltung = freischaltung(Duration.ZERO);
        freischaltung.freigeschaltet(URINET);
        freischaltung.freigeschaltet(URINET);
        assertThat(lesungen).hasValue(2);
    }

    @Test
    void mehrAls30SekundenSindUnzulaessig() {
        assertThatThrownBy(() -> freischaltung(Duration.ofSeconds(31))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> freischaltung(Duration.ofSeconds(-1))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void ohneMandantNichtFreigeschaltetUndPruefenVerweigert() {
        MandantAusAnmeldung anonym = () -> new MandantAusAnmeldung.Anonym();
        Wissensfreischaltung freischaltung = new Wissensfreischaltung(port, new MandantKontext(null, null, null),
                anonym, Duration.ofSeconds(30), uhr::get);
        assertThat(freischaltung.aktuell()).isFalse();
        assertThatThrownBy(freischaltung::pruefen).isInstanceOfSatisfying(FachFehler.class,
                e -> assertThat(e.code()).isEqualTo(Fehlercode.WISSEN_NICHT_FREIGESCHALTET));
        assertThatThrownBy(freischaltung::modellaufrufPruefen).isInstanceOf(WissenAusgeschaltet.class)
                .hasMessage(Wissensfreischaltung.GRUND_AUSGESCHALTET);
    }

    @Test
    void mandantDerAnmeldungZaehlt() {
        register.put(URINET, true);
        MandantAusAnmeldung angemeldet = () -> new MandantAusAnmeldung.Gueltig(URINET);
        Wissensfreischaltung freischaltung = new Wissensfreischaltung(port, new MandantKontext(null, null, null),
                angemeldet, Duration.ofSeconds(30), uhr::get);
        assertThat(freischaltung.aktuell()).isTrue();
    }
}
