package ch.urinet.archiv.ki.application;

import ch.urinet.archiv.ki.domain.ErfassungPrompt;
import ch.urinet.archiv.ki.domain.ArtEintrag;
import ch.urinet.archiv.ki.domain.Feld;
import ch.urinet.archiv.ki.domain.Rohvorschlag;
import ch.urinet.archiv.ki.domain.Vorschlag;
import ch.urinet.archiv.mandant.application.MandantEinstellungen;
import ch.urinet.archiv.mandant.domain.Geschaeftsjahresende;
import ch.urinet.archiv.mandant.domain.MandantCode;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class MetadatenExtraktionTest {

    @Test
    void vhfVorschlagEnthaeltKeineFinanzdaten() {
        Vorschlag original = new Vorschlag("BULLETIN", "Ausgabe", LocalDate.of(2026, 1, 2), "Verein",
                "uid-1", "CH9300762011623852957", "verein@example.ch", "R-123", "AUSGABE",
                new BigDecimal("123.45"), "CHF", LocalDate.of(2026, 2, 1), "QR-123", "NORMAL",
                List.of("Mitteilung"), Map.of(
                        Feld.BELEGNUMMER, BigDecimal.ONE,
                        Feld.RICHTUNG, BigDecimal.ONE,
                        Feld.BETRAG, BigDecimal.ONE,
                        Feld.WAEHRUNG, BigDecimal.ONE,
                        Feld.FAELLIGKEIT, BigDecimal.ONE,
                        Feld.QR_REFERENZ, BigDecimal.ONE), false, null);

        Vorschlag bereinigt = MetadatenExtraktion.ohneFinanz(original);

        assertThat(bereinigt.partnerIban()).isNull();
        assertThat(bereinigt.belegnummer()).isNull();
        assertThat(bereinigt.richtung()).isNull();
        assertThat(bereinigt.betrag()).isNull();
        assertThat(bereinigt.waehrung()).isNull();
        assertThat(bereinigt.faelligkeit()).isNull();
        assertThat(bereinigt.qrReferenz()).isNull();
        assertThat(bereinigt.sicherheit()).doesNotContainKeys(
                Feld.BELEGNUMMER, Feld.RICHTUNG, Feld.BETRAG, Feld.WAEHRUNG, Feld.FAELLIGKEIT, Feld.QR_REFERENZ);
        assertThat(bereinigt).extracting(Vorschlag::partnerName, Vorschlag::titel, Vorschlag::datum)
                .containsExactly("Verein", "Ausgabe", LocalDate.of(2026, 1, 2));
    }

    /** Der Mandantenhinweis hängt am Mandanten; die Kennung des Gate-Prompts bleibt vom Anhang unberührt. */
    @Test
    void mandantenHinweiseGeltenNurFuerDenMandantenMitHinweisen() {
        assertThat(ErfassungPrompt.hatMandantenHinweise("vhf-gsk")).isTrue();
        assertThat(ErfassungPrompt.mandantenHinweise("vhf-gsk")).isNotBlank();
        assertThat(ErfassungPrompt.hatMandantenHinweise("urinet")).isFalse();
        assertThat(ErfassungPrompt.mandantenHinweise("urinet")).isEmpty();
        String grund = ErfassungPrompt.system("Mandant", "ART");
        assertThat(ErfassungPrompt.hash(grund + ErfassungPrompt.mandantenHinweise("vhf-gsk"), "{}", List.of("ART"),
                ErfassungPrompt.BILDWEG))
                .isNotEqualTo(ErfassungPrompt.hash(grund, "{}", List.of("ART"), ErfassungPrompt.BILDWEG));
    }

    /** Verdrahtung durch {@code vorschlagen}: gestubbter KI-Port, der gesendete Prompt und der Vorschlag. */
    private static Rohvorschlag roh() {
        return new Rohvorschlag("QUITTUNG", "Titel", "2026-11-01", "Kunde AG", null, null, null, "R-1", "AUSGABE",
                new BigDecimal("100.00"), "CHF", "2026-12-01", null, "NORMAL", List.of(),
                Map.of(Feld.BETRAG, BigDecimal.ONE, Feld.BELEGNUMMER, BigDecimal.ONE));
    }

    private static Optional<Vorschlag> vorschlagen(String mandant, List<String> gesendet) {
        KiPort ki = new KiPort() {
            @Override
            public Antwort metadaten(Anfrage anfrage) {
                gesendet.add(anfrage.systemPrompt());
                return new Gueltig(roh(), new Verbrauch("test", 1, 1));
            }

            @Override
            public String antwortschema() {
                return "{}";
            }

            @Override
            public List<String> artCodes() {
                return List.of("QUITTUNG");
            }
        };
        BeispielAuswahl beispiele = new BeispielAuswahl(null, null, null, null) {
            @Override
            public String fuer(UUID dokumentId, MandantCode code, String text, Optional<String> qrIban,
                               ch.urinet.archiv.ki.domain.ErfassungMandant m) {
                return "";
            }
        };
        MetadatenExtraktion extraktion = new MetadatenExtraktion(ki, null, null, (zweck, verbrauch) -> { },
                null, code -> new MandantEinstellungen.Werte(Geschaeftsjahresende.STANDARD, Set.of(), "Mandant", "", List.of()),
                beispiele) {
            @Override
            List<ArtEintrag> arten() {
                return List.of(new ArtEintrag("QUITTUNG", "Quittung", "NORMAL"));
            }
        };
        return extraktion.vorschlagen(new MetadatenExtraktion.Eingabe(UUID.randomUUID(), new MandantCode(mandant),
                "Text", Optional.empty(), Optional.empty(), LocalDate.of(2026, 11, 17)));
    }

    @Test
    void vorschlagenFuerVhfGskHaengtHinweisAnUndVerwirftFinanzwerte() {
        List<String> gesendet = new java.util.ArrayList<>();
        Vorschlag v = vorschlagen("vhf-gsk", gesendet).orElseThrow();
        assertThat(gesendet.get(0)).endsWith(ErfassungPrompt.mandantenHinweise("vhf-gsk"));
        assertThat(v.betrag()).isNull();
        assertThat(v.belegnummer()).isNull();
        assertThat(v.sicherheit()).doesNotContainKeys(Feld.BETRAG, Feld.BELEGNUMMER);
    }

    @Test
    void vorschlagenFuerAndereMandantenBleibtUnveraendert() {
        List<String> gesendet = new java.util.ArrayList<>();
        Vorschlag v = vorschlagen("urinet", gesendet).orElseThrow();
        assertThat(gesendet.get(0)).doesNotContain("Zugriffsstufe wird manuell gewählt");
        assertThat(v.betrag()).isEqualByComparingTo("100.00");
        assertThat(v.belegnummer()).isEqualTo("R-1");
    }
}
