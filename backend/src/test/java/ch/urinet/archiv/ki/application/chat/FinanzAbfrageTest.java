package ch.urinet.archiv.ki.application.chat;

import ch.urinet.archiv.ki.application.chat.WerkzeugDaten.ArtRegel;
import ch.urinet.archiv.ki.application.chat.WerkzeugDaten.Richtung;
import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.sicherheit.domain.Benutzer;
import ch.urinet.archiv.sicherheit.domain.Rolle;
import io.smallrye.config.PropertiesConfigSource;
import io.smallrye.config.SmallRyeConfig;
import io.smallrye.config.SmallRyeConfigBuilder;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Spec 02, Req 3.2–3.4, 5.2: Parameter, Hinweise und Datenstand von {@code kennzahl} und {@code offene_posten}
 * ohne Datenbank (Port als Attrappe).
 */
class FinanzAbfrageTest {

    private static final MandantCode URINET = new MandantCode("urinet");
    private static final Clock UHR = Clock.fixed(Instant.parse("2071-06-30T22:30:00Z"), ZoneOffset.UTC);

    private final AtomicReference<WerkzeugDaten.KennzahlAnfrage> kennzahlAnfrage = new AtomicReference<>();
    private final AtomicReference<Object[]> offeneAnfrage = new AtomicReference<>();
    private boolean verbunden;

    private final WerkzeugDaten daten = new WerkzeugDaten() {
        @Override
        public void nurLesend() {
        }

        @Override
        public java.util.Set<UUID> bestaetigte(java.util.Collection<UUID> ids) {
            throw new UnsupportedOperationException();
        }

        @Override
        public java.util.Optional<String> inhalt(UUID id, ch.urinet.archiv.sicherheit.domain.Sichtregel regel, int z) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Fristseite fristen(LocalDate von, LocalDate bis, ch.urinet.archiv.sicherheit.domain.Sichtregel regel,
                                  List<Zusatzfrist> zusatz, int hoechstens) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<String> belegnummern(String art, int geschaeftsjahr, ch.urinet.archiv.sicherheit.domain.Sichtregel regel) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<Monatszeile> monate(Monatsquelle quelle, ch.urinet.archiv.sicherheit.domain.Sichtregel regel) {
            throw new UnsupportedOperationException();
        }

        @Override
        public long belege(java.util.Collection<String> arten, ch.urinet.archiv.sicherheit.domain.Sichtregel regel) {
            throw new UnsupportedOperationException();
        }

        @Override
        public KennzahlSeite kennzahl(KennzahlAnfrage anfrage, ch.urinet.archiv.sicherheit.domain.Sichtregel regel) {
            kennzahlAnfrage.set(anfrage);
            return new KennzahlSeite(List.of(new KennzahlZeile("2071", new BigDecimal("1234.50"), 2),
                    new KennzahlZeile(null, new BigDecimal("100.00"), 1)), new BigDecimal("1334.50"), 3,
                    List.of(UUID.fromString("00000000-0000-4000-8000-000000000001")), 2, false);
        }

        @Override
        public OffenePostenSeite offenePosten(Richtung richtung, List<ArtRegel> arten, LocalDate von, LocalDate bis,
                                              ch.urinet.archiv.sicherheit.domain.Sichtregel regel, int hoechstens) {
            offeneAnfrage.set(new Object[]{richtung, arten, von, bis, hoechstens});
            return new OffenePostenSeite(List.of(
                    new OffenerPosten(UUID.fromString("00000000-0000-4000-8000-000000000002"), "2071-001", "Quellwald",
                            new BigDecimal("1234.50"), "CHF", LocalDate.of(2071, 6, 1), null),
                    new OffenerPosten(UUID.fromString("00000000-0000-4000-8000-000000000003"), "2071-002", null,
                            new BigDecimal("80.00"), "EUR", LocalDate.of(2071, 7, 15), "BEZAHLT")), 7);
        }
    };

    private final Zahlungsquelle zahlung = () -> verbunden;
    private final WerkzeugTransaktion transaktion = new WerkzeugTransaktion() {
        @Override
        public <T> T neu(java.util.function.Supplier<T> arbeit) {
            return arbeit.get();
        }
    };
    private final FinanzArten arten = new FinanzArten(konfiguration(Map.of(
            "archiv.mandant.urinet.kennzahl.einnahmen-arten", "AUSGANGSRECHNUNG",
            "archiv.mandant.urinet.kennzahl.ausgaben-arten", "LIEFERANTENBELEG, LOHNABRECHNUNG:periode")));
    private final KennzahlAbfrage kennzahl = new KennzahlAbfrage(daten, transaktion, arten, zahlung, UHR);
    private final OffenePostenAbfrage offene = new OffenePostenAbfrage(daten, transaktion, arten, zahlung, UHR);

    @Test
    void kennzahlLiefertZeilenSummeUndDatenstand() {
        KennzahlAbfrage.Antwort a = kennzahl.kennzahl(kontext(Rolle.INHABER), "einnahmen", "2071-01-01", "31.12.2071",
                "jahr", null);
        assertThat(a.messgroesse()).isEqualTo("EINNAHMEN");
        assertThat(a.von()).isEqualTo("01.01.2071");
        assertThat(a.zeilen()).containsExactly(new KennzahlAbfrage.Zeile("2071", "CHF 1'234.50", 2),
                new KennzahlAbfrage.Zeile("(ohne Partner)", "CHF 100.00", 1));
        assertThat(a.summe()).isEqualTo("CHF 1'334.50");
        assertThat(a.datenstand()).isEqualTo(new KennzahlAbfrage.Datenstand("01.07.2071 00:30", 3,
                "nach Rechnungsdatum, 3 Rechnungen"));
        assertThat(a.belegIds()).containsExactly("00000000-0000-4000-8000-000000000001");
        assertThat(a.fremdwaehrungAusgeschlossen()).isEqualTo(2);
        assertThat(a.hinweise()).isNull();
        assertThat(kennzahlAnfrage.get().richtung()).isEqualTo(Richtung.EINNAHME);
        assertThat(kennzahlAnfrage.get().arten()).containsExactly(new ArtRegel("AUSGANGSRECHNUNG", null));
        assertThat(kennzahlAnfrage.get().hoechstensBelege()).isEqualTo(50);
    }

    @Test
    void ausgabenNutzenDieKonfigurierteListeMitMonatsregel() {
        kennzahl.kennzahl(kontext(Rolle.INHABER), "AUSGABEN", "2071-01-01", "2071-12-31", "ART", "BELEGDATUM");
        assertThat(kennzahlAnfrage.get().richtung()).isEqualTo(Richtung.AUSGABE);
        assertThat(kennzahlAnfrage.get().arten()).containsExactly(new ArtRegel("LIEFERANTENBELEG", null),
                new ArtRegel("LOHNABRECHNUNG", "periode"));
    }

    @Test
    void eingeschraenkteSichtHatDenHinweis() {
        KennzahlAbfrage.Antwort a = kennzahl.kennzahl(kontext(Rolle.MITARBEITER), "EINNAHMEN", "2071-01-01",
                "2071-12-31", null, null);
        assertThat(a.hinweise()).containsExactly("nur Belege, die du sehen darfst");
    }

    @Test
    void zahlungsdatumOhneZahlungsquelleGibtKeineZahl() {
        KennzahlAbfrage.Antwort a = kennzahl.kennzahl(kontext(Rolle.INHABER), "EINNAHMEN", "2071-01-01", "2071-12-31",
                "MONAT", "zahlungsdatum");
        assertThat(a.summe()).isNull();
        assertThat(a.zeilen()).isEmpty();
        assertThat(a.datenstand()).isNull();
        assertThat(a.belegIds()).isEmpty();
        assertThat(a.hinweise()).containsExactly("Zahlungsdatum unbekannt (keine AbaNinja-Verbindung)");
        assertThat(kennzahlAnfrage.get()).as("kein Datenbankzugriff").isNull();
        verbunden = true;
        assertThat(kennzahl.kennzahl(kontext(Rolle.INHABER), "EINNAHMEN", "2071-01-01", "2071-12-31", "MONAT",
                "ZAHLUNGSDATUM").summe()).isEqualTo("CHF 1'334.50");
        assertThat(kennzahlAnfrage.get().basis()).isEqualTo(WerkzeugDaten.Basis.ZAHLUNGSDATUM);
    }

    @Test
    void spanneHoechstensZehnJahre() {
        assertThat(kennzahl.kennzahl(kontext(Rolle.INHABER), "EINNAHMEN", "2061-01-01", "2071-01-01", null, null))
                .isNotNull();
        assertThatThrownBy(() -> kennzahl.kennzahl(kontext(Rolle.INHABER), "EINNAHMEN", "2061-01-01", "2071-01-02",
                null, null)).isInstanceOf(WerkzeugFehler.class).hasMessage("ungültiger Parameter: bis");
    }

    @Test
    void ohneKonfigurierteArtenKeineZahl() {
        WerkzeugKontext fremd = WerkzeugKontext.fuer(new MandantCode("vhf-gsk"), new Benutzer("b", Rolle.INHABER), null);
        assertThatThrownBy(() -> kennzahl.kennzahl(fremd, "EINNAHMEN", "2071-01-01", "2071-12-31", null, null))
                .isInstanceOf(WerkzeugFehler.class);
        assertThatThrownBy(() -> offene.offenePosten(fremd, null, null)).isInstanceOf(WerkzeugFehler.class);
    }

    @Test
    void offenePostenMitFensterUndUnbekanntemZahlstatus() {
        OffenePostenAbfrage.Antwort a = offene.offenePosten(kontext(Rolle.MITARBEITER), "2071-07-01", null);
        assertThat(offeneAnfrage.get()).containsExactly(Richtung.EINNAHME, List.of(new ArtRegel("AUSGANGSRECHNUNG", null)),
                LocalDate.of(2071, 4, 2), LocalDate.of(2071, 7, 31), 50);
        assertThat(a.fenster()).isEqualTo(new OffenePostenAbfrage.Fenster("02.04.2071", "31.07.2071"));
        assertThat(a.gesamt()).isEqualTo(7);
        assertThat(a.posten()).extracting(OffenePostenAbfrage.Posten::zahlstatus).containsExactly("UNBEKANNT", "UNBEKANNT");
        assertThat(a.posten()).extracting(OffenePostenAbfrage.Posten::ueberfaellig).containsExactly(true, false);
        assertThat(a.posten().getFirst().betrag()).isEqualTo("CHF 1'234.50");
        assertThat(a.posten().get(1).betrag()).isEqualTo("EUR 80.00");
        assertThat(a.datenstand().basis()).isEqualTo("nach Fälligkeit, 7 Rechnungen");
        assertThat(a.hinweise()).contains("Zahlstatus nicht bekannt, AbaNinja nicht verbunden",
                "nur Belege, die du sehen darfst");
    }

    @Test
    void stichtagStandardIstHeuteInZuerich() {
        // 2071-06-30 22:30 UTC ist in Zürich schon der 1. Juli
        OffenePostenAbfrage.Antwort a = offene.offenePosten(kontext(Rolle.INHABER), null, "ausgabe");
        assertThat(a.stichtag()).isEqualTo("01.07.2071");
        assertThat(a.richtung()).isEqualTo("AUSGABE");
        assertThat(offeneAnfrage.get()[0]).isEqualTo(Richtung.AUSGABE);
        assertThat(offeneAnfrage.get()[2]).isEqualTo(LocalDate.of(2071, 4, 2));
    }

    @Test
    void mitVerbindungZaehltDerGespeicherteZahlstatus() {
        verbunden = true;
        OffenePostenAbfrage.Antwort a = offene.offenePosten(kontext(Rolle.INHABER), "2071-07-01", null);
        assertThat(a.posten()).extracting(OffenePostenAbfrage.Posten::zahlstatus).containsExactly("UNBEKANNT", "BEZAHLT");
        assertThat(a.hinweise()).isNull();
    }

    @Test
    void artenAusDerKonfigurationUndUngueltigeEintraege() {
        assertThat(arten.arten(URINET, Richtung.AUSGABE)).containsExactly(new ArtRegel("LIEFERANTENBELEG", null),
                new ArtRegel("LOHNABRECHNUNG", "periode"));
        for (String eintrag : List.of("liefer", "A:b:c", "LOHN:Periode", "X")) {
            FinanzArten falsch = new FinanzArten(konfiguration(Map.of(
                    "archiv.mandant.urinet.kennzahl.einnahmen-arten", eintrag)));
            assertThatThrownBy(() -> falsch.arten(URINET, Richtung.EINNAHME)).isInstanceOf(IllegalStateException.class)
                    .hasMessageNotContaining(eintrag.length() > 1 ? eintrag : "~~");
        }
    }

    /** Die ausgelieferte Konfiguration (Entscheid Fabian 14.11.2026, E1) ist gültig. */
    @Test
    void ausgelieferteKonfigurationUrinet() throws IOException {
        SmallRyeConfig config = new SmallRyeConfigBuilder().withProfile("prod").addDefaultInterceptors()
                .withSources(new PropertiesConfigSource(getClass().getClassLoader().getResource("application.properties")))
                .build();
        FinanzArten ausgeliefert = new FinanzArten(config);
        assertThat(ausgeliefert.arten(URINET, Richtung.EINNAHME)).containsExactly(new ArtRegel("AUSGANGSRECHNUNG", null));
        assertThat(ausgeliefert.arten(URINET, Richtung.AUSGABE)).containsExactly(new ArtRegel("LIEFERANTENBELEG", null),
                new ArtRegel("QUITTUNG", null), new ArtRegel("LOHNABRECHNUNG", "periode"), new ArtRegel("BEHOERDE", null));
    }

    private static WerkzeugKontext kontext(Rolle rolle) {
        return WerkzeugKontext.fuer(URINET, new Benutzer("b", rolle), null);
    }

    private static SmallRyeConfig konfiguration(Map<String, String> werte) {
        return new SmallRyeConfigBuilder().withSources(new PropertiesConfigSource(werte, "test", 100)).build();
    }
}
