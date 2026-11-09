package ch.urinet.archiv.spike.golden;

import ch.urinet.archiv.spike.golden.Erwartung.Mandant;
import ch.urinet.archiv.spike.golden.MetadatenVorschlag.Richtung;
import ch.urinet.archiv.spike.golden.MetadatenVorschlag.Sicherheit;
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
        return new MetadatenVorschlag(art == null ? null : MetadatenVorschlag.ArtCode.valueOf(art), "t", datum, "Ki Partner", null, null, null, nummer, Richtung.AUSGABE,
                new BigDecimal("99.999"), "CHF", null, null, null, List.of(), S);
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
    void eigeneArtBeiFremdemQrBleibtAberSicherheitNull() throws IOException {
        QrDaten qr = new QrDaten("CH4431999123000889012", new BigDecimal("193.15"), "CHF", "QRR", "1", "Muster Hosting AG");
        var e = Nachbearbeitung.bearbeite(vorschlag("AUSGANGSRECHNUNG", "2026-123", "2026-03-01"), Optional.of(qr),
                "Rechnung 2026-123", MANDANT, arten(), EIGENE, HEUTE);
        assertThat(e.art()).isEqualTo("AUSGANGSRECHNUNG");
        assertThat(e.sicherheit()).containsEntry(Feld.ART, 0.0);
    }

    @Test
    void eigeneRechnungSetztArtUndBehaeltPartner() throws IOException {
        QrDaten qr = new QrDaten("CH9300762011623852957", new BigDecimal("244.05"), "CHF", "NON", null, "Muster Mandant");
        var e = Nachbearbeitung.bearbeite(vorschlag("LIEFERANTENBELEG", "2026-033", "2026-03-01"), Optional.of(qr),
                "Text", MANDANT, arten(), EIGENE, HEUTE);
        assertThat(e.art()).isEqualTo("AUSGANGSRECHNUNG");
        assertThat(e.partner()).isEqualTo("Ki Partner");
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
