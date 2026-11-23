package ch.urinet.archiv.konfiguration.application;

import ch.urinet.archiv.gemeinsam.domain.BestaetigungNoetig;
import ch.urinet.archiv.gemeinsam.domain.FachFehler;
import ch.urinet.archiv.gemeinsam.domain.Fehlercode;
import ch.urinet.archiv.gemeinsam.domain.KonfigurationUngueltig;
import ch.urinet.archiv.konfiguration.domain.EinstellungsRisiko;
import ch.urinet.archiv.konfiguration.domain.Konfiguration;
import ch.urinet.archiv.konfiguration.domain.Konfiguration.Einstellungen;
import ch.urinet.archiv.konfiguration.domain.KonfigurationQuelle;
import ch.urinet.archiv.mandant.application.MandantAufloesung;
import ch.urinet.archiv.mandant.application.MandantKontext;
import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.wissen.application.WissenEinstellungen;
import ch.urinet.archiv.wissen.application.WissenEinstellungenRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EinstellungenVerwaltungTest {
    private final KonfigurationProbe konfiguration = new KonfigurationProbe();
    private final MandantKontext kontext = new MandantKontext(null, null, null) {
        @Override public Optional<MandantCode> aktuell() {
            return Optional.of(new MandantCode("urinet"));
        }
    };
    private final MandantAufloesung aufloesung = new MandantAufloesung(null, null, null);
    private int schreibvorgaenge;
    private int stepUps;
    private int grenzen;
    private final EinstellungenVerwaltung verwaltung = new EinstellungenVerwaltung(konfiguration,
            einstellungen -> schreibvorgaenge++, new EinstellungsAenderungsPruefung(new StartwerteProbe(),
                    new EinstellungsAuswirkungBerechnen(new Daten()), () -> stepUps++, aufloesung, kontext, null),
            (akteur, art, ziel) -> grenzen++);

    @Test
    void versionWirdVorInhaltUndRisikoGeprueft() {
        assertThatThrownBy(() -> verwaltung.aendern(6, vorher -> eingabe(0), bestaetigt(null), "test"))
                .isInstanceOf(FachFehler.class)
                .satisfies(fehler -> assertThat(((FachFehler) fehler).code()).isEqualTo(Fehlercode.VERSION_KONFLIKT));
        assertThat(schreibvorgaenge).isZero();
    }

    @Test
    void fehlendeBestaetigungGehtVorUngueltigemWertUndAendertNichts() {
        assertThatThrownBy(() -> verwaltung.aendern(7, vorher -> eingabe(0), bestaetigt(null), "test"))
                .isInstanceOf(BestaetigungNoetig.class);
        assertThat(stepUps).isZero();
        assertThat(schreibvorgaenge).isZero();
        assertThatThrownBy(() -> verwaltung.aendern(7, vorher -> eingabe(0),
                bestaetigt("chat.aufbewahrungMonate"), "test")).isInstanceOf(KonfigurationUngueltig.class);
        assertThat(stepUps).isEqualTo(1);
        assertThat(schreibvorgaenge).isZero();
        assertThat(grenzen).isZero();
    }

    @Test
    void vorschauSchreibtNichtUndEineBestaetigteAenderungErzeugtEineVersion() {
        var vorschau = verwaltung.vorschau(7, vorher -> eingabe(6));
        assertThat(vorschau.auswirkungen()).hasSize(1);
        assertThat(schreibvorgaenge).isZero();
        assertThat(stepUps).isZero();
        var ergebnis = verwaltung.aendern(7, vorher -> eingabe(6), bestaetigt("chat.aufbewahrungMonate"), "test");
        assertThat(ergebnis.version()).isEqualTo(8);
        assertThat(ergebnis.geaendert()).isTrue();
        assertThat(schreibvorgaenge).isEqualTo(1);
        assertThat(stepUps).isEqualTo(1);
        assertThat(grenzen).isEqualTo(1);
    }

    @Test
    void unveraenderterWertErzeugtKeineVersion() {
        var ergebnis = verwaltung.aendern(7, vorher -> eingabe(12), bestaetigt(null), "test");
        assertThat(ergebnis.version()).isEqualTo(7);
        assertThat(ergebnis.geaendert()).isFalse();
        assertThat(schreibvorgaenge).isZero();
        assertThat(grenzen).isZero();
    }

    @Test
    void vollstaendigerStandMitUnwirksamenWissenswertenWirdNichtBestaetigt() {
        var wissen = new WissenEinstellungenRepository() {
            @Override public WissenEinstellungen.Werte lesen() {
                return new WissenEinstellungen.Werte(null, null, null, null);
            }
            @Override public void speichern(WissenEinstellungen.Werte werte) {
                throw new AssertionError("Darf nicht schreiben");
            }
        };
        var pruefung = new EinstellungsAenderungsPruefung(null, null, null, null, null, wissen);
        Einstellungen mitUnwirksamemWert = new Einstellungen("12-31", null, null, null, null,
                new Konfiguration.WissenEinstellungen(123456, null, null, null));
        assertThatThrownBy(() -> pruefung.wissenPruefen(mitUnwirksamemWert, mitUnwirksamemWert, false, true))
                .isInstanceOf(KonfigurationUngueltig.class);
        pruefung.wissenPruefen(einstellungen(12), einstellungen(12), false, true);
    }

    private static EinstellungenVerwaltung.Eingabe eingabe(int monate) {
        return new EinstellungenVerwaltung.Eingabe(einstellungen(monate), List.of());
    }

    private static BestaetigteEinstellungen bestaetigt(String wert) {
        return BestaetigteEinstellungen.lesen(wert);
    }

    private static Einstellungen einstellungen(int monate) {
        return new Einstellungen("12-31", null, null, new Konfiguration.ChatEinstellungen(monate), null, null);
    }

    private static final class KonfigurationProbe extends KonfigurationVerwaltung {
        private Konfiguration aktuell = new Konfiguration(7, einstellungen(12), List.of());

        private KonfigurationProbe() {
            super(null, null, null, null, null, null, null, null, null, null, null);
        }

        @Override public void aenderungStarten(int version) {
            if (version != aktuell.version()) {
                throw new FachFehler(Fehlercode.VERSION_KONFLIKT);
            }
        }

        @Override public Konfiguration aktuell() {
            return aktuell;
        }

        @Override int aenderungSpeichern(String wer, String grund, KonfigurationQuelle quelle, Konfiguration vorher,
                                        Einstellungen ziel) {
            aktuell = new Konfiguration(vorher.version() + 1, ziel, List.of());
            return aktuell.version();
        }
    }

    private static final class StartwerteProbe extends EinstellungsStartwerte {
        private StartwerteProbe() {
            super(null, null, null, null, "P12M", 1_000_000);
        }

        @Override public EinstellungsRisiko.Startwerte lesen() {
            return new EinstellungsRisiko.Startwerte(null, null, null);
        }
    }

    private static final class Daten implements EinstellungsAuswirkungen {
        @Override public long chatNachrichtenVor(Instant grenze) {
            return 0;
        }

        @Override public long oeffentlicheFragenVor(Instant grenze) {
            return 0;
        }
    }
}
