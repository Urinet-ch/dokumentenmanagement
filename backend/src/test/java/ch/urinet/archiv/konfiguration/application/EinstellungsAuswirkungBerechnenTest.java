package ch.urinet.archiv.konfiguration.application;

import ch.urinet.archiv.gemeinsam.domain.BestaetigungNoetig;
import ch.urinet.archiv.konfiguration.domain.EinstellungsRisiko;
import ch.urinet.archiv.konfiguration.domain.Konfiguration;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EinstellungsAuswirkungBerechnenTest {
    private static final Instant JETZT = Instant.parse("2026-11-22T12:00:00Z");
    private static final EinstellungsRisiko.Startwerte STANDARD = new EinstellungsRisiko.Startwerte(null, null, null);
    private final TestDaten daten = new TestDaten();
    private final EinstellungsAuswirkungBerechnen berechnung = new EinstellungsAuswirkungBerechnen(daten);

    @Test
    void kuerzereAufbewahrungZaehltAnDerNeuenGrenze() {
        var auswirkungen = berechnung.berechnen(einstellungen(12, 90, null),
                einstellungen(6, 30, null), STANDARD, "urinet", JETZT);
        assertThat(daten.chatGrenze).isEqualTo(Instant.parse("2026-05-22T12:00:00Z"));
        assertThat(daten.webGrenze).isEqualTo(Instant.parse("2026-10-23T12:00:00Z"));
        assertThat(auswirkungen).extracting(BestaetigungNoetig.Auswirkung::betroffeneDatensaetze)
                .containsExactly(3L, 7L);
    }

    @Test
    void bestaetigungMussJedeRiskanteAenderungEinzelnNennen() {
        var auswirkungen = berechnung.berechnen(einstellungen(12, 90, null),
                einstellungen(6, 30, null), STANDARD, "urinet", JETZT);
        assertThatThrownBy(() -> berechnung.bestaetigungPruefen(auswirkungen, Set.of("chat.aufbewahrungMonate")))
                .isInstanceOf(BestaetigungNoetig.class)
                .satisfies(fehler -> assertThat(((BestaetigungNoetig) fehler).auswirkung())
                        .extracting(BestaetigungNoetig.Auswirkung::schluessel)
                        .containsExactly("webseitenChat.aufbewahrungTage"));
        berechnung.bestaetigungPruefen(auswirkungen,
                Set.of("chat.aufbewahrungMonate", "webseitenChat.aufbewahrungTage"));
    }

    @Test
    void ungueltigerWertLiefertKeineErfundeneAnzahl() {
        var auswirkungen = berechnung.berechnen(einstellungen(12, 90, null),
                einstellungen(0, 90, null), STANDARD, "urinet", JETZT);
        assertThat(auswirkungen).singleElement().satisfies(auswirkung -> {
            assertThat(auswirkung.betroffeneDatensaetze()).isNull();
            assertThat(auswirkung.text()).contains("ungültigen Werts");
        });
        assertThat(daten.chatGrenze).isNull();
    }

    @Test
    void budgetZeigtDenGekennzeichnetenKostenrichtwert() {
        var auswirkungen = berechnung.berechnen(einstellungen(12, 90, null),
                einstellungen(12, 90, 2_000_001), STANDARD, "urinet", JETZT);
        assertThat(auswirkungen).singleElement().satisfies(auswirkung ->
                assertThat(auswirkung.text()).contains("CHF 1.51", "Preisstand 08.11.2026", "ohne MWST"));
    }

    @Test
    void zuruecksetzenZaehltAnDerTatsaechlichenStartperiode() {
        var startwerte = new EinstellungsRisiko.Startwerte(java.time.Period.ofDays(30), null, null);
        var auswirkungen = berechnung.berechnen(einstellungen(1, 90, null),
                einstellungen(null, 90, null), startwerte, "urinet", JETZT);
        assertThat(auswirkungen).hasSize(1);
        assertThat(daten.chatGrenze).isEqualTo(Instant.parse("2026-10-23T12:00:00Z"));
    }

    @Test
    void kostenrichtwertNutztDenBisherigenMonatsbudgetStartwert() {
        var startwerte = new EinstellungsRisiko.Startwerte(null, null, 30_000_000L);
        var auswirkungen = berechnung.berechnen(einstellungen(12, 90, 3_000_000),
                einstellungen(12, 90, null), startwerte, "urinet", JETZT);
        assertThat(auswirkungen).singleElement().satisfies(auswirkung ->
                assertThat(auswirkung.text()).contains("CHF 22.50"));
    }

    private static Konfiguration.Einstellungen einstellungen(Integer chat, Integer web, Integer budget) {
        return new Konfiguration.Einstellungen("12-31", null, null, new Konfiguration.ChatEinstellungen(chat),
                new Konfiguration.WebseitenChatEinstellungen(web),
                new Konfiguration.WissenEinstellungen(null, null, budget, null));
    }

    private static final class TestDaten implements EinstellungsAuswirkungen {
        private Instant chatGrenze;
        private Instant webGrenze;

        @Override
        public long chatNachrichtenVor(Instant grenze) {
            chatGrenze = grenze;
            return 3;
        }

        @Override
        public long oeffentlicheFragenVor(Instant grenze) {
            webGrenze = grenze;
            return 7;
        }
    }
}
