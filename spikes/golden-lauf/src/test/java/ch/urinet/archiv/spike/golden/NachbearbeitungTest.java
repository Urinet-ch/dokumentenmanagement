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
    static final LocalDate HEUTE = LocalDate.of(2026, 11, 8);
    static final List<String> EIGENE = List.of("AUSGANGSRECHNUNG", "OFFERTE", "LOHNABRECHNUNG");
    static final Sicherheit S = new Sicherheit(0.7, 0.9, 0.9, 0.6, 0.9, 0.9, 0.8, 0.9, 0.5, 0.5);

    static MetadatenVorschlag vorschlag(String art, String nummer, String datum) {
        return vorschlag(art, nummer, datum, null);
    }

    static MetadatenVorschlag vorschlag(String art, String nummer, String datum, Vertraulichkeit vertraulichkeit) {
        return new MetadatenVorschlag(art == null ? null : MetadatenVorschlag.ArtCode.valueOf(art), "t", datum, "Ki Partner", null, null, null, nummer, Richtung.AUSGABE,
                new BigDecimal("99.999"), "CHF", null, null, vertraulichkeit, List.of(), S);
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
                "Rechnung 2026-123 an Muster Mandant", MANDANT, arten(), EIGENE, HEUTE);
        assertThat(e.art()).isEqualTo("LIEFERANTENBELEG");
        assertThat(e.mandantIstAussteller()).isFalse();
        assertThat(e.partner()).isEqualTo("Muster Hosting AG");
        assertThat(e.betrag()).isEqualByComparingTo("193.15");
        assertThat(e.sicherheit()).containsEntry(Feld.BETRAG, 1.0).containsEntry(Feld.PARTNER, 1.0);
    }

    @Test
    void lieferantenrechnung2026_123WirdNieUeberDasMusterZurAusgangsrechnung() throws IOException {
        var e = Nachbearbeitung.bearbeite(vorschlag("AUSGANGSRECHNUNG", "2026-123", "2026-03-01"), Optional.of(FREMDER_QR),
                "Rechnung 2026-123", MANDANT, arten(), EIGENE, HEUTE);
        assertThat(e.mandantIstAussteller()).isFalse();
        assertThat(e.art()).isEqualTo("LIEFERANTENBELEG");
        assertThat(e.sicherheit().get(Feld.ART)).isLessThan(0.8);
    }

    @Test
    void vorrangFremderQrVorMandantenUidImKopf() throws IOException {
        Mandant mitUid = new Mandant("Muster Mandant", "CHE-123.456.789", MANDANT.ibans());
        var e = Nachbearbeitung.bearbeite(vorschlag("LIEFERANTENBELEG", "2026-123", "2026-03-01"), Optional.of(FREMDER_QR),
                "Kunde: Muster Mandant, UID CHE-123.456.789 MWST\nRechnung 2026-123", mitUid, arten(), EIGENE, HEUTE);
        assertThat(e.mandantIstAussteller()).isFalse();
        assertThat(e.art()).isEqualTo("LIEFERANTENBELEG");
        assertThat(e.sicherheit()).containsEntry(Feld.ART, 0.7);
    }

    @Test
    void fremdesSchreibenMitMandantenIbanNurImFliesstextBehaeltArt() throws IOException {
        var e = Nachbearbeitung.bearbeite(vorschlag("BEHOERDE", "2026-123", "2026-03-01"), Optional.empty(),
                seite(15, "Gutschrift auf Ihr Konto CH93 0076 2011 6238 5295 7"), MANDANT, arten(), EIGENE, HEUTE);
        assertThat(e.mandantIstAussteller()).isFalse();
        assertThat(e.art()).isEqualTo("BEHOERDE");
        assertThat(e.sicherheit()).containsEntry(Feld.ART, 0.7);
    }

    @Test
    void regel3EigeneArtOhneKennungOhneQrSicherheitNull() throws IOException {
        var e = Nachbearbeitung.bearbeite(vorschlag("AUSGANGSRECHNUNG", "4711", "2026-03-01"), Optional.empty(),
                "Kassenbon Beck AG", MANDANT, arten(), EIGENE, HEUTE);
        assertThat(e.art()).isEqualTo("AUSGANGSRECHNUNG");
        assertThat(e.umgestuft()).isFalse();
        assertThat(e.sicherheit()).containsEntry(Feld.ART, 0.0);
    }

    @Test
    void lohnabrechnungMitFremdemQrWirdNieUmgestuft() throws IOException {
        var e = Nachbearbeitung.bearbeite(vorschlag("LOHNABRECHNUNG", null, "2026-03-01", Vertraulichkeit.PERSONAL),
                Optional.of(FREMDER_QR), "Lohnabrechnung", MANDANT, arten(), EIGENE, HEUTE);
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
    void umstufungAusgangsrechnungUndOfferteBeiFremdemQr() throws IOException {
        for (String art : List.of("AUSGANGSRECHNUNG", "OFFERTE")) {
            var e = Nachbearbeitung.bearbeite(vorschlag(art, null, "2026-03-01", Vertraulichkeit.PERSONAL),
                    Optional.of(FREMDER_QR), "Rechnung", MANDANT, arten(), EIGENE, HEUTE);
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
                "Offerte", MANDANT, arten(), EIGENE, HEUTE);
        assertThat(e.mandantIstAussteller()).isTrue();
        assertThat(e.art()).isEqualTo("OFFERTE");
        assertThat(e.umgestuft()).isFalse();
        assertThat(e.sicherheit()).containsEntry(Feld.ART, 0.7);
    }

    @Test
    void eigeneRechnungSetztArtUndBehaeltPartner() throws IOException {
        QrDaten qr = new QrDaten("CH9300762011623852957", new BigDecimal("244.05"), "CHF", "NON", null, "Muster Mandant");
        var e = Nachbearbeitung.bearbeite(vorschlag("LIEFERANTENBELEG", "2026-033", "2026-03-01"), Optional.of(qr),
                "Text", MANDANT, arten(), EIGENE, HEUTE);
        assertThat(e.art()).isEqualTo("AUSGANGSRECHNUNG");
        assertThat(e.partner()).isEqualTo("Ki Partner");
        assertThat(e.umgestuft()).isFalse();
        assertThat(e.sicherheit()).containsEntry(Feld.ART, 1.0);
    }

    @Test
    void offerteOhneQrUeberIbanImText() throws IOException {
        var e = Nachbearbeitung.bearbeite(vorschlag("AUSGANGSRECHNUNG", "O-2026-009", "2026-03-01"), Optional.empty(),
                "Muster Mandant · IBAN CH93 0076 2011 6238 5295 7", MANDANT, arten(), EIGENE, HEUTE);
        assertThat(e.art()).isEqualTo("OFFERTE");
        assertThat(e.betrag()).isEqualByComparingTo("100.00");
        // Standard der endgültigen Art OFFERTE (NORMAL), nicht der KI-Art AUSGANGSRECHNUNG (INTERN)
        assertThat(e.vertraulichkeit()).isEqualTo("NORMAL");
    }

    @Test
    void datumWeitInDerZukunftOderUngueltigSicherheitNull() throws IOException {
        var zukunft = Nachbearbeitung.bearbeite(vorschlag("QUITTUNG", null, "2028-01-01"), Optional.empty(), "",
                MANDANT, arten(), EIGENE, HEUTE);
        assertThat(zukunft.datum()).isEqualTo(LocalDate.of(2028, 1, 1));
        assertThat(zukunft.sicherheit()).containsEntry(Feld.DATUM, 0.0);
        var ungueltig = Nachbearbeitung.bearbeite(vorschlag("QUITTUNG", null, "3102-13-40"), Optional.empty(), "",
                MANDANT, arten(), EIGENE, HEUTE);
        assertThat(ungueltig.datum()).isNull();
        assertThat(ungueltig.sicherheit()).containsEntry(Feld.DATUM, 0.0);
    }

    @Test
    void vertraulichkeitAusStandardDerArt() throws IOException {
        var e = Nachbearbeitung.bearbeite(vorschlag("OFFERTE", null, "2026-03-01"), Optional.empty(), "", MANDANT,
                arten(), EIGENE, HEUTE);
        assertThat(e.vertraulichkeit()).isEqualTo("NORMAL");
    }

    @Test
    void dokumentartenAusDesignMd() throws IOException {
        assertThat(arten()).hasSize(11).extracting(Dokumentarten.Art::code).contains("AUSGANGSRECHNUNG", "KORRESPONDENZ");
        assertThat(Prompt.gekuerzt("x".repeat(20_000))).hasSizeLessThan(12_010).contains("[…]");
    }
}
