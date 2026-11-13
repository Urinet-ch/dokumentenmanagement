package ch.urinet.archiv.ki.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Nachbearbeitung des KI-Vorschlags (design.md «KI»; Req 7.2, 7.5, 7.6, 7.11, 7.12) mit Auflage A1 aus Gate 0.
 * Nur synthetische Werte (Testmandant mit Test-UID und Test-IBAN).
 */
class VorschlagNachbearbeitungTest {

    static final String UID = "CHE-000.000.001";
    static final String IBAN = "CH93 0076 2011 6238 5295 7";
    static final String FREMDE_IBAN = "CH44 3199 9123 0008 8901 2";
    static final ErfassungMandant MANDANT = new ErfassungMandant("Testfirma AG", UID, List.of(IBAN),
            Set.of("AUSGANGSRECHNUNG", "OFFERTE", "LOHNABRECHNUNG"));
    static final List<ArtEintrag> ARTEN = List.of(
            new ArtEintrag("AUSGANGSRECHNUNG", "Ausgangsrechnung", "INTERN"),
            new ArtEintrag("OFFERTE", "Offerte", "NORMAL"),
            new ArtEintrag("LIEFERANTENBELEG", "Lieferantenbeleg", "INTERN"),
            new ArtEintrag("QUITTUNG", "Quittung / Spesen", "INTERN"),
            new ArtEintrag("VERTRAG", "Vertrag", "PERSONAL"),
            new ArtEintrag("PROJEKTDOKUMENT", "Projektdokument", "NORMAL"));
    static final LocalDate EINGANG = LocalDate.of(2026, 11, 12);
    static final String EIGENER_KOPF = "Testfirma AG\nMWST " + UID + "\nRechnung";
    static final String FREMDER_KOPF = "Lieferant GmbH\nRechnung an Testfirma AG";

    static Rohvorschlag roh(String art, String belegnummer, BigDecimal sicherheitBelegnummer, String vertraulichkeit) {
        Map<Feld, BigDecimal> s = new EnumMap<>(Feld.class);
        s.put(Feld.ART, new BigDecimal("0.9"));
        s.put(Feld.BELEGNUMMER, sicherheitBelegnummer);
        s.put(Feld.BETRAG, new BigDecimal("0.9"));
        s.put(Feld.PARTNER, new BigDecimal("0.9"));
        return new Rohvorschlag(art, "Titel", "2026-11-01", "Kunde AG", null, null, null, belegnummer, "EINNAHME",
                new BigDecimal("100.00"), "CHF", "2026-12-01", null, vertraulichkeit, List.of("test"), s);
    }

    static Vorschlag bearbeite(Rohvorschlag v, Optional<QrDaten> qr, String text) {
        return VorschlagNachbearbeitung.bearbeite(v, qr, text, MANDANT, ARTEN, EINGANG);
    }

    @Test
    void a1EigenerBelegImMusterBleibtUnbegrenzt() {
        Vorschlag v = bearbeite(roh("PROJEKTDOKUMENT", "2026-003", new BigDecimal("0.95"), "NORMAL"), Optional.empty(),
                EIGENER_KOPF);

        assertThat(v.mandantIstAussteller()).isTrue();
        assertThat(v.art()).isEqualTo("AUSGANGSRECHNUNG");
        assertThat(v.sicherheit().get(Feld.ART)).isEqualByComparingTo("1");
        assertThat(v.sicherheit().get(Feld.BELEGNUMMER)).isEqualByComparingTo("0.95");
    }

    @Test
    void a1FremdbelegOhneMusterWirdBegrenzt() {
        Vorschlag v = bearbeite(roh("LIEFERANTENBELEG", "LF-4711", new BigDecimal("0.95"), "INTERN"),
                Optional.empty(), FREMDER_KOPF);

        assertThat(v.mandantIstAussteller()).isFalse();
        assertThat(v.belegnummer()).isEqualTo("LF-4711");
        assertThat(v.sicherheit().get(Feld.BELEGNUMMER)).isEqualByComparingTo(VorschlagNachbearbeitung.BELEGNUMMER_HOECHSTENS);
    }

    /** Req 7.12, Testfall Lieferantenrechnung {@code 2026-123}: Muster ändert die Art nicht, A1 begrenzt. */
    @Test
    void lieferantenrechnungImMusterDesMandantenBleibtLieferantenbeleg() {
        Vorschlag v = bearbeite(roh("LIEFERANTENBELEG", "2026-123", new BigDecimal("0.95"), "INTERN"),
                Optional.empty(), FREMDER_KOPF);

        assertThat(v.art()).isEqualTo("LIEFERANTENBELEG");
        assertThat(v.umstufung()).isNull();
        assertThat(v.sicherheit().get(Feld.ART)).isEqualByComparingTo("0.9");
        assertThat(v.sicherheit().get(Feld.BELEGNUMMER)).isEqualByComparingTo(VorschlagNachbearbeitung.BELEGNUMMER_HOECHSTENS);
    }

    @Test
    void a1SenktKeineNiedrigereSicherheit() {
        Vorschlag v = bearbeite(roh("LIEFERANTENBELEG", "LF-4711", new BigDecimal("0.4"), "INTERN"), Optional.empty(),
                FREMDER_KOPF);

        assertThat(v.sicherheit().get(Feld.BELEGNUMMER)).isEqualByComparingTo("0.4");
    }

    /** Req 7.12: Das Modell hält den Fremdbeleg im Muster für eine Ausgangsrechnung; der fremde QR stuft um. */
    @Test
    void eigeneArtMitFremdemQrWirdLieferantenbeleg() {
        QrDaten qr = new QrDaten(FREMDE_IBAN, new BigDecimal("250.5"), "CHF", "NON", null, "Lieferant GmbH");

        Vorschlag v = bearbeite(roh("AUSGANGSRECHNUNG", "2026-123", new BigDecimal("0.95"), "INTERN"), Optional.of(qr),
                FREMDER_KOPF + "\n" + UID);

        assertThat(v.mandantIstAussteller()).isFalse();
        assertThat(v.art()).isEqualTo("LIEFERANTENBELEG");
        assertThat(v.umstufung()).isEqualTo(VorschlagNachbearbeitung.GRUND_QR);
        assertThat(v.sicherheit().get(Feld.ART)).isEqualByComparingTo("0.5");
        assertThat(v.sicherheit().get(Feld.BELEGNUMMER)).isEqualByComparingTo(VorschlagNachbearbeitung.BELEGNUMMER_HOECHSTENS);
    }

    /** Req 7.11: Werte des Swiss QR Code vor den Werten der KI. */
    @Test
    void qrWerteHabenVorrang() {
        QrDaten qr = new QrDaten(FREMDE_IBAN, new BigDecimal("250.5"), "EUR", "QRR",
                "210000000003139471430009017", "Lieferant GmbH");

        Vorschlag v = bearbeite(roh("LIEFERANTENBELEG", null, BigDecimal.ZERO, "INTERN"), Optional.of(qr),
                FREMDER_KOPF);

        assertThat(v.betrag()).isEqualByComparingTo("250.50");
        assertThat(v.waehrung()).isEqualTo("EUR");
        assertThat(v.partnerName()).isEqualTo("Lieferant GmbH");
        assertThat(v.partnerIban()).isEqualTo(FREMDE_IBAN);
        assertThat(v.qrReferenz()).isEqualTo("210000000003139471430009017");
        assertThat(v.sicherheit()).containsEntry(Feld.BETRAG, BigDecimal.ONE).containsEntry(Feld.WAEHRUNG, BigDecimal.ONE)
                .containsEntry(Feld.PARTNER, BigDecimal.ONE).containsEntry(Feld.QR_REFERENZ, BigDecimal.ONE);
    }

    /** Ein QR mit der IBAN des Mandanten macht ihn zum Aussteller; der Partner bleibt der des Modells. */
    @Test
    void eigenerQrBehaeltPartner() {
        QrDaten qr = new QrDaten(IBAN.replace(" ", ""), new BigDecimal("80"), "CHF", "NON", null, "Testfirma AG");

        Vorschlag v = bearbeite(roh("AUSGANGSRECHNUNG", "2026-004", new BigDecimal("0.9"), "INTERN"), Optional.of(qr),
                "ohne Kennung");

        assertThat(v.mandantIstAussteller()).isTrue();
        assertThat(v.partnerName()).isEqualTo("Kunde AG");
        assertThat(v.betrag()).isEqualByComparingTo("80.00");
    }

    /** Req 7.2: Vertraulichkeit mit dem Standard der Art, nicht mit dem Wert des Modells. */
    @Test
    void vertraulichkeitAusDerArt() {
        Vorschlag v = bearbeite(roh("VERTRAG", null, BigDecimal.ZERO, "NORMAL"), Optional.empty(), FREMDER_KOPF);

        assertThat(v.vertraulichkeit()).isEqualTo("PERSONAL");
        assertThat(v.sicherheit().get(Feld.VERTRAULICHKEIT)).isEqualByComparingTo(v.sicherheit().get(Feld.ART));
    }

    @Test
    void artAusserhalbDerListeIstKeinVorschlag() {
        Vorschlag v = bearbeite(roh("BANK", null, BigDecimal.ZERO, "INTERN"), Optional.empty(), FREMDER_KOPF);

        assertThat(v.art()).isNull();
        assertThat(v.vertraulichkeit()).isNull();
        assertThat(v.sicherheit().get(Feld.ART)).isEqualByComparingTo("0");
    }

    /** Req 7.6: Sicherheiten bleiben zwischen 0 und 1; ein Datum nach dem Eingang ist unsicher (R3). */
    @Test
    void sicherheitenZwischenNullUndEins() {
        Map<Feld, BigDecimal> s = new EnumMap<>(Feld.class);
        s.put(Feld.TITEL, new BigDecimal("1.4"));
        s.put(Feld.RICHTUNG, new BigDecimal("-0.2"));
        s.put(Feld.DATUM, new BigDecimal("0.9"));
        Rohvorschlag r = new Rohvorschlag("PROJEKTDOKUMENT", "Titel", "2026-11-30", null, null, null, null, null,
                null, null, null, null, null, null, null, s);

        Vorschlag v = bearbeite(r, Optional.empty(), FREMDER_KOPF);

        assertThat(v.sicherheit().get(Feld.TITEL)).isEqualByComparingTo("1");
        assertThat(v.sicherheit().get(Feld.RICHTUNG)).isEqualByComparingTo("0");
        assertThat(v.sicherheit().get(Feld.DATUM)).isEqualByComparingTo("0");
        assertThat(v.sicherheit().values()).allSatisfy(w -> assertThat(w).isBetween(BigDecimal.ZERO, BigDecimal.ONE));
    }

    static Rohvorschlag mitBetrag(String art, String belegnummer, BigDecimal betrag, String iban, String faelligkeit) {
        Map<Feld, BigDecimal> s = new EnumMap<>(Feld.class);
        s.put(Feld.ART, new BigDecimal("0.9"));
        s.put(Feld.BETRAG, new BigDecimal("0.8"));
        s.put(Feld.DATUM, new BigDecimal("0.9"));
        return new Rohvorschlag(art, "Titel", "2026-11-01", "Lieferant GmbH", null, iban, null, belegnummer, "AUSGABE",
                betrag, "CHF", faelligkeit, null, null, List.of(), s);
    }

    /** E1: Betrag 0 des Modells heisst «kein Betrag»; ein QR-Betrag 0.00 setzt ihn wieder. */
    @Test
    void e1BetragNullDesModellsWirdLeer() {
        for (String nullBetrag : List.of("0", "0.0", "0.00")) {
            Vorschlag v = bearbeite(mitBetrag("LIEFERANTENBELEG", null, new BigDecimal(nullBetrag), null, "2026-12-01"),
                    Optional.empty(), FREMDER_KOPF);
            assertThat(v.betrag()).isNull();
            assertThat(v.sicherheit().get(Feld.BETRAG)).isEqualByComparingTo("0.8");
        }
        QrDaten nullBetrag = new QrDaten(FREMDE_IBAN, new BigDecimal("0.00"), "CHF", "NON", null, "Lieferant GmbH");
        Vorschlag v = bearbeite(mitBetrag("LIEFERANTENBELEG", null, BigDecimal.ZERO, null, null), Optional.of(nullBetrag),
                FREMDER_KOPF);
        assertThat(v.betrag()).isEqualByComparingTo("0.00");
    }

    /** E3: Eine Belegnummer nur aus Nullen ist keine Belegnummer. */
    @Test
    void e3BelegnummerNurAusNullenWirdLeer() {
        for (String nullen : List.of("0", "000", " 0 ")) {
            assertThat(bearbeite(roh("PROJEKTDOKUMENT", nullen, new BigDecimal("0.9"), null), Optional.empty(),
                    FREMDER_KOPF).belegnummer()).isNull();
        }
        for (String nummer : List.of("0815", "10", "M-2026-0")) {
            assertThat(bearbeite(roh("PROJEKTDOKUMENT", nummer, new BigDecimal("0.9"), null), Optional.empty(),
                    FREMDER_KOPF).belegnummer()).isEqualTo(nummer);
        }
    }

    /** E2: Lieferantenbeleg ohne QR, ohne IBAN und ohne Fälligkeit wird Quittung. */
    @Test
    void e2LieferantenbelegOhneQrIbanFaelligkeitWirdQuittung() {
        Vorschlag v = bearbeite(mitBetrag("LIEFERANTENBELEG", null, BigDecimal.TEN, null, null), Optional.empty(), "Bon");

        assertThat(v.art()).isEqualTo("QUITTUNG");
        assertThat(v.umstufung()).isEqualTo(VorschlagNachbearbeitung.GRUND_E2);
        assertThat(v.sicherheit().get(Feld.ART)).isEqualByComparingTo("0.5");
        assertThat(bearbeite(mitBetrag("LIEFERANTENBELEG", null, BigDecimal.TEN, "null", "2026-13-40"),
                Optional.empty(), "Bon").art()).isEqualTo("QUITTUNG");
    }

    @Test
    void e2GreiftNichtJeBedingung() {
        QrDaten qr = new QrDaten(FREMDE_IBAN, null, "CHF", "NON", null, "Lieferant GmbH");
        for (Vorschlag v : List.of(
                bearbeite(mitBetrag("LIEFERANTENBELEG", null, BigDecimal.TEN, null, null), Optional.of(qr), "Bon"),
                bearbeite(mitBetrag("LIEFERANTENBELEG", null, BigDecimal.TEN, FREMDE_IBAN, null), Optional.empty(), "Bon"),
                bearbeite(mitBetrag("LIEFERANTENBELEG", null, BigDecimal.TEN, null, "2026-12-31"), Optional.empty(),
                        "Bon"))) {
            assertThat(v.art()).isEqualTo("LIEFERANTENBELEG");
            assertThat(v.umstufung()).isNull();
        }
    }

    /** E2 greift nicht, wenn das Belegnummer-Muster des ausstellenden Mandanten die Art gesetzt hat. */
    @Test
    void e2GreiftNichtWennMusterDieArtGesetztHat() {
        Vorschlag v = bearbeite(mitBetrag("LIEFERANTENBELEG", "F2026", BigDecimal.TEN, null, null), Optional.empty(),
                "IBAN " + IBAN);

        assertThat(v.mandantIstAussteller()).isTrue();
        assertThat(v.art()).isEqualTo("LIEFERANTENBELEG");
        assertThat(v.sicherheit().get(Feld.ART)).isEqualByComparingTo("1");
        assertThat(v.umstufung()).isNull();
    }

    /** Belegnummer-Muster (Req 7.5): Offerte und Altbestand nur beim eigenen Beleg. */
    @Test
    void musterSetzenDieArtNurBeimEigenenBeleg() {
        assertThat(bearbeite(roh("PROJEKTDOKUMENT", "O-2026-005", new BigDecimal("0.9"), null), Optional.empty(),
                EIGENER_KOPF).art()).isEqualTo("OFFERTE");
        assertThat(bearbeite(roh("PROJEKTDOKUMENT", "R0001", new BigDecimal("0.9"), null), Optional.empty(),
                EIGENER_KOPF).art()).isEqualTo("AUSGANGSRECHNUNG");
        assertThat(bearbeite(roh("PROJEKTDOKUMENT", "O-2026-005", new BigDecimal("0.9"), null), Optional.empty(),
                FREMDER_KOPF).art()).isEqualTo("PROJEKTDOKUMENT");
    }

    /** Eine Regel setzt keine Art, die beim Mandanten nicht aktiv ist; die Art des Modells bleibt. */
    @Test
    void zielArtNurWennBeimMandantenAktiv() {
        List<ArtEintrag> ohneZiele = ARTEN.stream()
                .filter(a -> !Set.of("QUITTUNG", "OFFERTE").contains(a.code())).toList();

        Vorschlag e2 = VorschlagNachbearbeitung.bearbeite(mitBetrag("LIEFERANTENBELEG", null, BigDecimal.TEN, null,
                null), Optional.empty(), "Bon", MANDANT, ohneZiele, EINGANG);
        Vorschlag muster = VorschlagNachbearbeitung.bearbeite(roh("PROJEKTDOKUMENT", "O-2026-005",
                new BigDecimal("0.9"), null), Optional.empty(), EIGENER_KOPF, MANDANT, ohneZiele, EINGANG);

        assertThat(e2.art()).isEqualTo("LIEFERANTENBELEG");
        assertThat(e2.umstufung()).isNull();
        assertThat(muster.art()).isEqualTo("PROJEKTDOKUMENT");
    }

    /** R3: ein Datum nach dem Eingang ist unsicher, der gleiche Tag nicht. */
    @Test
    void r3GleicherTagBleibtSicher() {
        Rohvorschlag r = mitBetrag("LIEFERANTENBELEG", null, BigDecimal.TEN, null, "2026-12-01");
        Rohvorschlag gleicherTag = new Rohvorschlag(r.art(), r.titel(), EINGANG.toString(), r.partnerName(),
                r.partnerUid(), r.partnerIban(), r.partnerEmail(), r.belegnummer(), r.richtung(), r.betrag(),
                r.waehrung(), r.faelligkeit(), r.qrReferenz(), r.vertraulichkeit(), r.tags(), r.sicherheit());

        assertThat(bearbeite(gleicherTag, Optional.empty(), FREMDER_KOPF).sicherheit().get(Feld.DATUM))
                .isEqualByComparingTo("0.9");
    }

    /** Betrag des Modells ausserhalb von NUMERIC(12,2) oder mit riesigem Exponenten: leer und unsicher, ohne Ausnahme. */
    @Test
    void betragDesModellsAusserhalbDesBereichsWirdLeer() {
        for (String ungueltig : List.of("1E300000000", "-1E300000000", "10000000000", "1E+10")) {
            Vorschlag v = bearbeite(mitBetrag("LIEFERANTENBELEG", null, new BigDecimal(ungueltig), null, "2026-12-01"),
                    Optional.empty(), FREMDER_KOPF);
            assertThat(v.betrag()).as(ungueltig).isNull();
            assertThat(v.sicherheit().get(Feld.BETRAG)).as(ungueltig).isEqualByComparingTo("0");
        }
        Vorschlag winzig = bearbeite(mitBetrag("LIEFERANTENBELEG", null, new BigDecimal("1E-300000000"), null,
                "2026-12-01"), Optional.empty(), FREMDER_KOPF);
        assertThat(winzig.betrag()).isNull();
        Vorschlag gutschrift = bearbeite(mitBetrag("LIEFERANTENBELEG", null, new BigDecimal("-9999999999.994"), null,
                "2026-12-01"), Optional.empty(), FREMDER_KOPF);
        assertThat(gutschrift.betrag()).isEqualByComparingTo("-9999999999.99");
        assertThat(gutschrift.sicherheit().get(Feld.BETRAG)).isEqualByComparingTo("0.8");
    }

    /** Sicherheiten mit riesigem Exponenten werden ohne Ausnahme auf 0 bis 1 gebracht. */
    @Test
    void sicherheitMitRiesigemExponenten() {
        Map<Feld, BigDecimal> s = new EnumMap<>(Feld.class);
        s.put(Feld.TITEL, new BigDecimal("1E-300000000"));
        s.put(Feld.RICHTUNG, new BigDecimal("1E300000000"));
        s.put(Feld.DATUM, new BigDecimal("0.899999"));
        Rohvorschlag r = new Rohvorschlag("PROJEKTDOKUMENT", "Titel", "2026-11-01", null, null, null, null, null,
                null, null, null, null, null, null, null, s);

        Vorschlag v = bearbeite(r, Optional.empty(), FREMDER_KOPF);

        assertThat(v.sicherheit().get(Feld.TITEL)).isEqualByComparingTo("0");
        assertThat(v.sicherheit().get(Feld.RICHTUNG)).isEqualByComparingTo("1");
        assertThat(v.sicherheit().get(Feld.DATUM)).isEqualByComparingTo("0.8999");
    }

    /** Ein QR-Betrag ausserhalb des Formats macht den Betrag leer und unsicher, die übrigen QR-Werte gelten. */
    @Test
    void ungueltigerQrBetragWirdLeerUndUnsicher() {
        QrDaten qr = QrDaten.parse(QrDatenTest.payload("1E300000000")).orElseThrow();

        Vorschlag v = bearbeite(mitBetrag("LIEFERANTENBELEG", null, new BigDecimal("12.50"), null, null),
                Optional.of(qr), FREMDER_KOPF);

        assertThat(v.betrag()).isNull();
        assertThat(v.sicherheit().get(Feld.BETRAG)).isEqualByComparingTo("0");
        assertThat(v.partnerIban()).isEqualTo(qr.iban());
        assertThat(v.sicherheit().get(Feld.PARTNER)).isEqualByComparingTo("1");
    }
}
