package ch.urinet.archiv.spike.golden;

import ch.urinet.archiv.spike.golden.Erwartung.Mandant;
import ch.urinet.archiv.spike.golden.MetadatenVorschlag.Richtung;
import ch.urinet.archiv.spike.golden.MetadatenVorschlag.Sicherheit;
import ch.urinet.archiv.spike.golden.MetadatenVorschlag.Vertraulichkeit;
import ch.urinet.archiv.spike.golden.SwissQrLeser.QrDaten;
import ch.urinet.archiv.spike.golden.Vergleich.Feld;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** design.md «Nachbearbeitung», Req 7.5, 7.11, 7.12. */
class NachbearbeitungTest {

    static final Mandant MANDANT = new Mandant("Muster Mandant", null, List.of("CH93 0076 2011 6238 5295 7"));
    static final LocalDate EINGANG = LocalDate.of(2026, 11, 10);
    static final List<String> EIGENE = List.of("AUSGANGSRECHNUNG", "OFFERTE", "LOHNABRECHNUNG");
    static final Sicherheit S = new Sicherheit(0.7, 0.9, 0.9, 0.6, 0.9, 0.9, 0.8, 0.9, 0.5, 0.5);

    static MetadatenVorschlag vorschlag(String art, String nummer, String datum) {
        return vorschlag(art, nummer, datum, null);
    }

    static MetadatenVorschlag vorschlag(String art, String nummer, String datum, Vertraulichkeit vertraulichkeit) {
        return vorschlag(art, nummer, datum, vertraulichkeit, new BigDecimal("99.999"), null, null);
    }

    static MetadatenVorschlag vorschlag(String art, String nummer, String datum, Vertraulichkeit vertraulichkeit,
                                        BigDecimal betrag, String partnerIban, String faelligkeit) {
        return new MetadatenVorschlag(art == null ? null : MetadatenVorschlag.ArtCode.valueOf(art), "t", datum, "Ki Partner", null, partnerIban, null, nummer, Richtung.AUSGABE,
                betrag, "CHF", faelligkeit, null, vertraulichkeit, List.of(), S);
    }

    static final QrDaten FREMDER_QR = new QrDaten("CH4431999123000889012", new BigDecimal("193.15"), "CHF", "QRR", "1",
            "Muster Hosting AG");
    static final QrDaten EIGENER_QR = new QrDaten("CH9300762011623852957", new BigDecimal("244.05"), "CHF", "NON", null,
            "Muster Mandant");

    /** Eine Seite mit 30 nicht leeren Zeilen; die Zeile an Position {@code pos} (0-basiert) trägt {@code inhalt}. */
    static String seite(int pos, String inhalt) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 30; i++) {
            sb.append(i == pos ? inhalt : "Zeile " + i).append("\n\n");
        }
        return sb.toString();
    }

    static List<Dokumentarten.Art> arten() throws IOException {
        return Dokumentarten.aus(Dokumentarten.designMd(Path.of("")));
    }

    @Test
    void fremdeRechnung2026_123BleibtLieferantenbeleg() throws IOException {
        QrDaten qr = new QrDaten("CH4431999123000889012", new BigDecimal("193.15"), "CHF", "QRR", "1", "Muster Hosting AG");
        var e = Nachbearbeitung.bearbeite(vorschlag("LIEFERANTENBELEG", "2026-123", "2026-03-01"), Optional.of(qr),
                "Rechnung 2026-123 an Muster Mandant", MANDANT, arten(), EIGENE, EINGANG);
        assertThat(e.art()).isEqualTo("LIEFERANTENBELEG");
        assertThat(e.mandantIstAussteller()).isFalse();
        assertThat(e.partner()).isEqualTo("Muster Hosting AG");
        assertThat(e.betrag()).isEqualByComparingTo("193.15");
        assertThat(e.sicherheit()).containsEntry(Feld.BETRAG, 1.0).containsEntry(Feld.PARTNER, 1.0);
    }

    @Test
    void lieferantenrechnung2026_123WirdNieUeberDasMusterZurAusgangsrechnung() throws IOException {
        var e = Nachbearbeitung.bearbeite(vorschlag("AUSGANGSRECHNUNG", "2026-123", "2026-03-01"), Optional.of(FREMDER_QR),
                "Rechnung 2026-123", MANDANT, arten(), EIGENE, EINGANG);
        assertThat(e.mandantIstAussteller()).isFalse();
        assertThat(e.art()).isEqualTo("LIEFERANTENBELEG");
        assertThat(e.sicherheit().get(Feld.ART)).isLessThan(0.8);
    }

    @Test
    void vorrangFremderQrVorMandantenUidImKopf() throws IOException {
        Mandant mitUid = new Mandant("Muster Mandant", "CHE-123.456.789", MANDANT.ibans());
        var e = Nachbearbeitung.bearbeite(vorschlag("LIEFERANTENBELEG", "2026-123", "2026-03-01"), Optional.of(FREMDER_QR),
                "Kunde: Muster Mandant, UID CHE-123.456.789 MWST\nRechnung 2026-123", mitUid, arten(), EIGENE, EINGANG);
        assertThat(e.mandantIstAussteller()).isFalse();
        assertThat(e.art()).isEqualTo("LIEFERANTENBELEG");
        assertThat(e.sicherheit()).containsEntry(Feld.ART, 0.7);
    }

    @Test
    void fremdesSchreibenMitMandantenIbanNurImFliesstextBehaeltArt() throws IOException {
        var e = Nachbearbeitung.bearbeite(vorschlag("BEHOERDE", "2026-123", "2026-03-01"), Optional.empty(),
                seite(15, "Gutschrift auf Ihr Konto CH93 0076 2011 6238 5295 7"), MANDANT, arten(), EIGENE, EINGANG);
        assertThat(e.mandantIstAussteller()).isFalse();
        assertThat(e.art()).isEqualTo("BEHOERDE");
        assertThat(e.sicherheit()).containsEntry(Feld.ART, 0.7);
    }

    @Test
    void regel3EigeneArtOhneKennungOhneQrSicherheitNull() throws IOException {
        var e = Nachbearbeitung.bearbeite(vorschlag("AUSGANGSRECHNUNG", "4711", "2026-03-01"), Optional.empty(),
                "Kassenbon Beck AG", MANDANT, arten(), EIGENE, EINGANG);
        assertThat(e.art()).isEqualTo("AUSGANGSRECHNUNG");
        assertThat(e.umgestuft()).isFalse();
        assertThat(e.sicherheit()).containsEntry(Feld.ART, 0.0);
    }

    @Test
    void lohnabrechnungMitFremdemQrWirdNieUmgestuft() throws IOException {
        var e = Nachbearbeitung.bearbeite(vorschlag("LOHNABRECHNUNG", null, "2026-03-01", Vertraulichkeit.PERSONAL),
                Optional.of(FREMDER_QR), "Lohnabrechnung", MANDANT, arten(), EIGENE, EINGANG);
        assertThat(e.art()).isEqualTo("LOHNABRECHNUNG");
        assertThat(e.vertraulichkeit()).isEqualTo("PERSONAL");
        assertThat(e.umgestuft()).isFalse();
        assertThat(e.sicherheit()).containsEntry(Feld.ART, 0.0);
    }

    @Test
    void kennungImKopfOderFussbereichJeSeite() {
        String iban = "IBAN CH93 0076 2011 6238 5295 7";
        assertThat(Nachbearbeitung.kopfOderFussEnthaeltKennung(seite(0, iban), MANDANT)).isTrue();
        assertThat(Nachbearbeitung.kopfOderFussEnthaeltKennung(seite(9, iban), MANDANT)).isTrue();
        assertThat(Nachbearbeitung.kopfOderFussEnthaeltKennung(seite(29, iban), MANDANT)).isTrue();
        assertThat(Nachbearbeitung.kopfOderFussEnthaeltKennung(seite(10, iban), MANDANT)).isFalse();
        assertThat(Nachbearbeitung.kopfOderFussEnthaeltKennung(seite(19, iban), MANDANT)).isFalse();
        // Fuss der ersten Seite bleibt Fuss, auch wenn eine zweite Seite folgt
        assertThat(Nachbearbeitung.kopfOderFussEnthaeltKennung(seite(29, iban) + "\f" + seite(15, "x"), MANDANT)).isTrue();
        Mandant mitUid = new Mandant("Muster Mandant", "CHE-123.456.789", List.of());
        assertThat(Nachbearbeitung.kopfOderFussEnthaeltKennung(seite(2, "CHE-123.456.789 MWST"), mitUid)).isTrue();
        assertThat(Nachbearbeitung.kopfOderFussEnthaeltKennung(seite(14, "CHE-123.456.789 MWST"), mitUid)).isFalse();
    }

    @Test
    void kurzeSeiteZaehltGanzAuchGutschriftskontoInDerDrittletztenZeile() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 17; i++) {
            sb.append(i == 14 ? "Gutschrift auf Ihr Konto CH93 0076 2011 6238 5295 7" : "Zeile " + i).append('\n');
        }
        // heutiges Verhalten: bei bis zu 20 nicht leeren Zeilen gilt die ganze Seite als Kopf- oder Fussbereich
        assertThat(Nachbearbeitung.kopfOderFussEnthaeltKennung(sb.toString(), MANDANT)).isTrue();
    }

    @Test
    void umstufungAusgangsrechnungUndOfferteBeiFremdemQr() throws IOException {
        for (String art : List.of("AUSGANGSRECHNUNG", "OFFERTE")) {
            var e = Nachbearbeitung.bearbeite(vorschlag(art, null, "2026-03-01", Vertraulichkeit.PERSONAL),
                    Optional.of(FREMDER_QR), "Rechnung", MANDANT, arten(), EIGENE, EINGANG);
            assertThat(e.art()).isEqualTo("LIEFERANTENBELEG");
            assertThat(e.artRoh()).isEqualTo(art);
            assertThat(e.umgestuft()).isTrue();
            assertThat(e.sicherheit()).containsEntry(Feld.ART, 0.5);
            // Standard von LIEFERANTENBELEG, nicht der Modellwert der verworfenen Art
            assertThat(e.vertraulichkeit()).isEqualTo("INTERN");
        }
    }

    @Test
    void eigenerQrKeineUmstufung() throws IOException {
        var e = Nachbearbeitung.bearbeite(vorschlag("OFFERTE", null, "2026-03-01"), Optional.of(EIGENER_QR),
                "Offerte", MANDANT, arten(), EIGENE, EINGANG);
        assertThat(e.mandantIstAussteller()).isTrue();
        assertThat(e.art()).isEqualTo("OFFERTE");
        assertThat(e.umgestuft()).isFalse();
        assertThat(e.sicherheit()).containsEntry(Feld.ART, 0.7);
    }

    @Test
    void eigeneRechnungSetztArtUndBehaeltPartner() throws IOException {
        QrDaten qr = new QrDaten("CH9300762011623852957", new BigDecimal("244.05"), "CHF", "NON", null, "Muster Mandant");
        var e = Nachbearbeitung.bearbeite(vorschlag("LIEFERANTENBELEG", "2026-033", "2026-03-01"), Optional.of(qr),
                "Text", MANDANT, arten(), EIGENE, EINGANG);
        assertThat(e.art()).isEqualTo("AUSGANGSRECHNUNG");
        assertThat(e.partner()).isEqualTo("Ki Partner");
        assertThat(e.umgestuft()).isFalse();
        assertThat(e.sicherheit()).containsEntry(Feld.ART, 1.0);
    }

    @Test
    void offerteOhneQrUeberIbanImText() throws IOException {
        var e = Nachbearbeitung.bearbeite(vorschlag("AUSGANGSRECHNUNG", "O-2026-009", "2026-03-01"), Optional.empty(),
                "Muster Mandant · IBAN CH93 0076 2011 6238 5295 7", MANDANT, arten(), EIGENE, EINGANG);
        assertThat(e.art()).isEqualTo("OFFERTE");
        assertThat(e.betrag()).isEqualByComparingTo("100.00");
        // Standard der endgültigen Art OFFERTE (NORMAL), nicht der KI-Art AUSGANGSRECHNUNG (INTERN)
        assertThat(e.vertraulichkeit()).isEqualTo("NORMAL");
    }

    @Test
    void r3DatumStriktNachEingangSicherheitNullGleicherTagNicht() throws IOException {
        var danach = Nachbearbeitung.bearbeite(vorschlag("BEHOERDE", null, "2026-11-11"), Optional.empty(), "",
                MANDANT, arten(), EIGENE, EINGANG);
        assertThat(danach.datum()).isEqualTo(LocalDate.of(2026, 11, 11));
        assertThat(danach.datumNachEingang()).isTrue();
        assertThat(danach.sicherheit()).containsEntry(Feld.DATUM, 0.0);
        var gleicherTag = Nachbearbeitung.bearbeite(vorschlag("BEHOERDE", null, "2026-11-10"), Optional.empty(), "",
                MANDANT, arten(), EIGENE, EINGANG);
        assertThat(gleicherTag.datumNachEingang()).isFalse();
        assertThat(gleicherTag.sicherheit()).containsEntry(Feld.DATUM, 0.9);
        // Fälligkeit nach dem Eingang ist normal und lässt die Datum-Sicherheit stehen
        var faellig = Nachbearbeitung.bearbeite(vorschlag("BEHOERDE", null, "2026-11-01", null, BigDecimal.TEN, null,
                "2026-12-01"), Optional.empty(), "", MANDANT, arten(), EIGENE, EINGANG);
        assertThat(faellig.datumNachEingang()).isFalse();
        assertThat(faellig.sicherheit()).containsEntry(Feld.DATUM, 0.9);
    }

    @Test
    void leeresOderUngueltigesDatumSicherheitNull() throws IOException {
        for (String d : java.util.Arrays.asList(null, "3102-13-40")) {
            var e = Nachbearbeitung.bearbeite(vorschlag("BEHOERDE", null, d), Optional.empty(), "",
                    MANDANT, arten(), EIGENE, EINGANG);
            assertThat(e.datum()).isNull();
            assertThat(e.datumNachEingang()).isFalse();
            assertThat(e.sicherheit()).containsEntry(Feld.DATUM, 0.0);
        }
    }

    @Test
    void e1BetragNullDesModellsWirdLeer() throws IOException {
        for (String null0 : List.of("0", "0.0", "0.00")) {
            var ohneQr = Nachbearbeitung.bearbeite(vorschlag("BEHOERDE", null, "2026-03-01", null, new BigDecimal(null0),
                    null, null), Optional.empty(), "", MANDANT, arten(), EIGENE, EINGANG);
            assertThat(ohneQr.betrag()).isNull();
            assertThat(ohneQr.sicherheit()).containsEntry(Feld.BETRAG, 0.8);
        }
        QrDaten ohneBetrag = new QrDaten("CH4431999123000889012", null, "CHF", "QRR", "1", "Muster Hosting AG");
        var qrOhneBetrag = Nachbearbeitung.bearbeite(vorschlag("LIEFERANTENBELEG", null, "2026-03-01", null,
                BigDecimal.ZERO, null, null), Optional.of(ohneBetrag), "", MANDANT, arten(), EIGENE, EINGANG);
        assertThat(qrOhneBetrag.betrag()).isNull();
        QrDaten nullBetrag = new QrDaten("CH4431999123000889012", new BigDecimal("0.00"), "CHF", "QRR", "1", "Muster Hosting AG");
        var qrNull = Nachbearbeitung.bearbeite(vorschlag("LIEFERANTENBELEG", null, "2026-03-01", null,
                BigDecimal.ZERO, null, null), Optional.of(nullBetrag), "", MANDANT, arten(), EIGENE, EINGANG);
        assertThat(qrNull.betrag()).isEqualByComparingTo("0.00");
        var andererBetrag = Nachbearbeitung.bearbeite(vorschlag("BEHOERDE", null, "2026-03-01", null,
                new BigDecimal("12.50"), null, null), Optional.empty(), "", MANDANT, arten(), EIGENE, EINGANG);
        assertThat(andererBetrag.betrag()).isEqualByComparingTo("12.50");
    }

    @Test
    void e3BelegnummerNurAusNullenWirdLeer() throws IOException {
        for (String nullen : List.of("0", "000", " 0 ")) {
            var e = Nachbearbeitung.bearbeite(vorschlag("BEHOERDE", nullen, "2026-02-08"), Optional.empty(), "", MANDANT,
                    arten(), EIGENE, EINGANG);
            assertThat(e.belegnummer()).isNull();
        }
        for (String nummer : List.of("0815", "10", "M-2026-0")) {
            var e = Nachbearbeitung.bearbeite(vorschlag("BEHOERDE", nummer, "2026-02-08"), Optional.empty(), "", MANDANT,
                    arten(), EIGENE, EINGANG);
            assertThat(e.belegnummer()).isEqualTo(nummer);
        }
    }

    static Nachbearbeitung.Ergebnis e2(String art, Optional<QrDaten> qr, String iban, String faelligkeit, String nummer,
                                       String text) throws IOException {
        return Nachbearbeitung.bearbeite(vorschlag(art, nummer, "2026-03-01", Vertraulichkeit.NORMAL, BigDecimal.TEN,
                iban, faelligkeit), qr, text, MANDANT, arten(), EIGENE, EINGANG);
    }

    @Test
    void e2LieferantenbelegOhneQrIbanFaelligkeitWirdQuittung() throws IOException {
        var e = e2("LIEFERANTENBELEG", Optional.empty(), null, null, null, "Bon");
        assertThat(e.art()).isEqualTo("QUITTUNG");
        assertThat(e.artRoh()).isEqualTo("LIEFERANTENBELEG");
        assertThat(e.umgestuft()).isTrue();
        assertThat(e.umstufungGrund()).isEqualTo("E2");
        assertThat(e.sicherheit()).containsEntry(Feld.ART, 0.5);
        assertThat(e.vertraulichkeit()).isEqualTo("INTERN");
        // «null» als Text und ungültige Fälligkeit zählen als leer
        assertThat(e2("LIEFERANTENBELEG", Optional.empty(), "null", "2026-13-40", null, "Bon").art()).isEqualTo("QUITTUNG");
    }

    @Test
    void e2GreiftNichtJeBedingung() throws IOException {
        QrDaten ohneBetrag = new QrDaten("CH4431999123000889012", null, "CHF", "QRR", "1", "Muster Hosting AG");
        var mitQr = e2("LIEFERANTENBELEG", Optional.of(ohneBetrag), null, null, null, "Bon");
        var mitIban = e2("LIEFERANTENBELEG", Optional.empty(), "CH4431999123000889012", null, null, "Bon");
        var mitFaelligkeit = e2("LIEFERANTENBELEG", Optional.empty(), null, "2026-03-31", null, "Bon");
        var andereRohArt = e2("BEHOERDE", Optional.empty(), null, null, null, "Bon");
        for (var e : List.of(mitQr, mitIban, mitFaelligkeit)) {
            assertThat(e.art()).isEqualTo("LIEFERANTENBELEG");
            assertThat(e.umgestuft()).isFalse();
            assertThat(e.umstufungGrund()).isNull();
        }
        assertThat(andereRohArt.art()).isEqualTo("BEHOERDE");
        assertThat(andereRohArt.umgestuft()).isFalse();
    }

    @Test
    void e2GreiftNichtWennMusterDieArtGesetztHat() throws IOException {
        var e = e2("LIEFERANTENBELEG", Optional.empty(), null, null, "F2026", "IBAN CH93 0076 2011 6238 5295 7");
        assertThat(e.mandantIstAussteller()).isTrue();
        assertThat(e.art()).isEqualTo("LIEFERANTENBELEG");
        assertThat(e.sicherheit()).containsEntry(Feld.ART, 1.0);
        assertThat(e.umgestuft()).isFalse();
    }

    @Test
    void e2SchliesstSichMitQrUmstufungAus() throws IOException {
        var e = e2("AUSGANGSRECHNUNG", Optional.of(FREMDER_QR), null, null, null, "Rechnung");
        assertThat(e.art()).isEqualTo("LIEFERANTENBELEG");
        assertThat(e.umstufungGrund()).isEqualTo("QR");
    }

    @Test
    void vertraulichkeitAusStandardDerArt() throws IOException {
        var e = Nachbearbeitung.bearbeite(vorschlag("OFFERTE", null, "2026-03-01"), Optional.empty(), "", MANDANT,
                arten(), EIGENE, EINGANG);
        assertThat(e.vertraulichkeit()).isEqualTo("NORMAL");
    }

    @Test
    void dokumentartenAusDesignMd() throws IOException {
        assertThat(arten()).hasSize(11).extracting(Dokumentarten.Art::code).contains("AUSGANGSRECHNUNG", "KORRESPONDENZ");
        assertThat(Prompt.gekuerzt("x".repeat(20_000))).hasSizeLessThan(12_010).contains("[…]");
    }
}
