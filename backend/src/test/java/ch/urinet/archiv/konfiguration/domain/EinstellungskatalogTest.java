package ch.urinet.archiv.konfiguration.domain;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EinstellungskatalogTest {

    @Test
    void zahlengrenzenGeltenEinschliesslichDerRaender() {
        for (var einstellung : Einstellungskatalog.Zahl.values()) {
            assertThat(einstellung.gueltig(null)).isTrue();
            assertThat(einstellung.gueltig(einstellung.minimum())).isTrue();
            assertThat(einstellung.gueltig(einstellung.maximum())).isTrue();
            assertThat(einstellung.gueltig(einstellung.standard())).isTrue();
            assertThat(einstellung.gueltig(einstellung.minimum() - 1)).isFalse();
            assertThat(einstellung.gueltig(einstellung.maximum() + 1)).isFalse();
        }
    }

    @Test
    void betriebsUndGeheimnisschluesselSindKeineKatalogwerte() {
        assertThat(Einstellungskatalog.alle()).extracting(Einstellungskatalog.Einstellung::schluessel)
                .containsExactlyInAnyOrder("geschaeftsjahrEnde", "kennungen.uid", "kennungen.ibans",
                        "branding.name", "branding.primaerfarbe", "chat.aufbewahrungMonate",
                        "webseitenChat.aufbewahrungTage", "wissen.budgetTokens", "wissen.lintBudgetTokens",
                        "wissen.monatsbudgetTokens", "wissen.lintZeitplan.wochentag", "wissen.lintZeitplan.stunde");
    }

    @Test
    void webseitenChatHatMandantenbezogenenStandard() {
        var einstellung = Einstellungskatalog.alle().stream()
                .filter(e -> e.zahl() == Einstellungskatalog.Zahl.WEBSEITEN_CHAT_AUFBEWAHRUNG)
                .findFirst().orElseThrow();
        assertThat(einstellung.standardFuer("urinet")).isEqualTo(90);
        assertThat(einstellung.standardFuer("vhf-gsk")).isEqualTo(30);
    }

    @Test
    void eigenerWertVorStartwertVorKatalogstandard() {
        var einstellung = Einstellungskatalog.Zahl.WEBSEITEN_CHAT_AUFBEWAHRUNG;
        assertThat(einstellung.wirksamerWert(17, 25, "vhf-gsk")).isEqualTo(17);
        assertThat(einstellung.wirksamerWert(null, 25, "vhf-gsk")).isEqualTo(25);
        assertThat(einstellung.wirksamerWert(null, null, "vhf-gsk")).isEqualTo(30);
        assertThat(einstellung.wirksamerWert(null, null, "urinet")).isEqualTo(90);
        assertThatThrownBy(() -> einstellung.wirksamerWert(null, 366, "urinet"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void risikoBeruecksichtigtWirksameStandardsUndKuerzereAufbewahrung() {
        var vorher = einstellungen("12-31", null, null, null);
        var nachher = einstellungen("06-30", 11, 29, null);
        var startwerte = new EinstellungsRisiko.Startwerte(null, null, null);

        assertThat(EinstellungsRisiko.geaendert(vorher, nachher, startwerte, "vhf-gsk"))
                .containsExactlyInAnyOrder("geschaeftsjahrEnde", "chat.aufbewahrungMonate",
                        "webseitenChat.aufbewahrungTage");
        assertThat(EinstellungsRisiko.geaendert(nachher, vorher, startwerte, "vhf-gsk"))
                .containsExactly("geschaeftsjahrEnde");
    }

    @Test
    void risikoBeimZuruecksetzenVergleichtMitBisherigemStartwert() {
        var startwerte = new EinstellungsRisiko.Startwerte(java.time.Period.ofMonths(6), 40, 1_000_000L);
        var vorher = einstellungen("12-31", 12, 30, null);
        var nachher = einstellungen("12-31", null, null, null);

        assertThat(EinstellungsRisiko.geaendert(vorher, nachher, startwerte, "urinet"))
                .containsExactly("chat.aufbewahrungMonate");
        assertThat(EinstellungsRisiko.geaendert(nachher, nachher, startwerte, "urinet")).isEmpty();
        assertThat(EinstellungsRisiko.geaendert(einstellungen("12-31", null, null, null),
                einstellungen(null, null, null, null), startwerte, "urinet")).isEmpty();
    }

    @Test
    void risikoBeimZuruecksetzenBeachtetStartperiodeMitTagen() {
        var startwerte = new EinstellungsRisiko.Startwerte(java.time.Period.ofDays(30), null, null);
        var vorher = einstellungen("12-31", 1, null, null);
        var nachher = einstellungen("12-31", null, null, null);
        var jetzt = java.time.Instant.parse("2026-11-22T12:00:00Z");
        assertThat(EinstellungsRisiko.geaendert(vorher, nachher, startwerte, "urinet", jetzt))
                .containsExactly("chat.aufbewahrungMonate");
        assertThat(EinstellungsRisiko.geaendert(nachher, vorher, startwerte, "urinet", jetzt)).isEmpty();
    }

    @Test
    void monatsbudgetIstErstOberhalbDesDoppeltenStandardsRiskant() {
        var startwerte = new EinstellungsRisiko.Startwerte(null, null, null);
        var vorher = einstellungen("12-31", null, null, 1_000_000);

        assertThat(EinstellungsRisiko.geaendert(vorher,
                einstellungen("12-31", null, null, 2_000_000), startwerte, "urinet")).isEmpty();
        assertThat(EinstellungsRisiko.geaendert(vorher,
                einstellungen("12-31", null, null, 2_000_001), startwerte, "urinet"))
                .containsExactly("wissen.monatsbudgetTokens");
    }

    @Test
    void risikoBeachtetAuchBisherigeStartwerteUeberDerKataloggrenze() {
        var startwerte = new EinstellungsRisiko.Startwerte(null, null, 30_000_000L);
        assertThat(EinstellungsRisiko.geaendert(einstellungen("12-31", null, null, null),
                einstellungen("12-31", null, null, 3_000_000), startwerte, "urinet"))
                .containsExactly("wissen.monatsbudgetTokens");
    }

    @Test
    void risikoWirdAuchVorDerValidierungUngueltigerZahlenErkannt() {
        var startwerte = new EinstellungsRisiko.Startwerte(null, null, null);
        assertThat(EinstellungsRisiko.geaendert(einstellungen("12-31", null, null, null),
                einstellungen("12-31", 0, null, 20_000_001), startwerte, "urinet"))
                .containsExactlyInAnyOrder("chat.aufbewahrungMonate", "wissen.monatsbudgetTokens");
    }

    private static Konfiguration.Einstellungen einstellungen(String geschaeftsjahrEnde, Integer chat,
                                                              Integer webseitenChat, Integer monatsbudget) {
        return new Konfiguration.Einstellungen(geschaeftsjahrEnde, null, null,
                new Konfiguration.ChatEinstellungen(chat),
                new Konfiguration.WebseitenChatEinstellungen(webseitenChat),
                new Konfiguration.WissenEinstellungen(null, null, monatsbudget, null));
    }

    @Test
    void grenzenUndStandardsEntsprechenDemInventar() {
        Map<Einstellungskatalog.Zahl, int[]> erwartet = Map.of(
                Einstellungskatalog.Zahl.CHAT_AUFBEWAHRUNG, new int[] {1, 60, 12},
                Einstellungskatalog.Zahl.WEBSEITEN_CHAT_AUFBEWAHRUNG, new int[] {1, 365, 90},
                Einstellungskatalog.Zahl.WISSEN_BUDGET, new int[] {10_000, 5_000_000, 600_000},
                Einstellungskatalog.Zahl.WISSEN_LINT_BUDGET, new int[] {10_000, 1_000_000, 100_000},
                Einstellungskatalog.Zahl.WISSEN_MONATSBUDGET, new int[] {100_000, 20_000_000, 1_000_000},
                Einstellungskatalog.Zahl.WISSEN_LINT_STUNDE, new int[] {0, 23, 4});
        assertThat(erwartet).hasSize(Einstellungskatalog.Zahl.values().length);
        erwartet.forEach((e, werte) ->
                assertThat(new int[] {e.minimum(), e.maximum(), e.standard()}).containsExactly(werte));
    }

    @Test
    void brandingVerlangtSicherenNamenUndKontrastZumDunklenHintergrund() {
        assertThat(Einstellungskatalog.brandingNameGueltig(null)).isTrue();
        assertThat(Einstellungskatalog.brandingNameGueltig("a".repeat(60))).isTrue();
        assertThat(Einstellungskatalog.brandingNameGueltig("a".repeat(61))).isFalse();
        assertThat(Einstellungskatalog.brandingNameGueltig(" ")).isFalse();
        assertThat(Einstellungskatalog.brandingNameGueltig("<b>Name</b>")).isFalse();
        assertThat(Einstellungskatalog.brandingNameGueltig("**Name**")).isFalse();
        assertThat(Einstellungskatalog.brandingNameGueltig("Name\nNeu")).isFalse();
        assertThat(Einstellungskatalog.primaerfarbeGueltig(null)).isTrue();
        assertThat(Einstellungskatalog.primaerfarbeGueltig("#eeb111")).isTrue();
        assertThat(Einstellungskatalog.primaerfarbeGueltig("#05070c")).isFalse();
        assertThat(Einstellungskatalog.primaerfarbeGueltig("#zzzzzz")).isFalse();
    }

    @Test
    void kennungenHabenDasVorgegebeneFormatUndHoechstensZwanzigEintraege() {
        assertThat(Einstellungskatalog.uidGueltig(null)).isTrue();
        assertThat(Einstellungskatalog.uidGueltig("CHE-000.000.000 MWST")).isTrue();
        assertThat(Einstellungskatalog.uidGueltig("ungueltig")).isFalse();
        assertThat(Einstellungskatalog.ibansGueltig(java.util.Collections.nCopies(20, "synthetisch"))).isTrue();
        assertThat(Einstellungskatalog.ibansGueltig(java.util.Collections.nCopies(21, "synthetisch"))).isFalse();
        assertThat(Einstellungskatalog.ibansGueltig(List.of("a\nb"))).isFalse();
        assertThat(Einstellungskatalog.ibansGueltig(List.of("x".repeat(34)))).isTrue();
        assertThat(Einstellungskatalog.ibansGueltig(List.of("x".repeat(35)))).isFalse();
        assertThat(Einstellungskatalog.uidGueltig("CHE-000.000.000 TVA")).isTrue();
        assertThat(Einstellungskatalog.uidGueltig("CHE-000.000.000 IVA")).isTrue();
    }

    @Test
    void konfigurationMeldetUngueltigeEinstellungenMitPfad() {
        var einstellungen = new Konfiguration.Einstellungen("12-31", null,
                new Konfiguration.EinstellungenBranding("<b>Archiv</b>", "#05070c"),
                new Konfiguration.ChatEinstellungen(0), new Konfiguration.WebseitenChatEinstellungen(366),
                new Konfiguration.WissenEinstellungen(9_999, 1_000_001, 99_999,
                        new Konfiguration.LintZeitplan(null, 24)));
        var konfiguration = new Konfiguration(1, einstellungen, List.of());

        assertThat(konfiguration.pruefen()).extracting(Konfiguration.Befund::pfad).contains(
                "einstellungen.branding.name", "einstellungen.branding.primaerfarbe",
                "einstellungen.chat.aufbewahrungMonate", "einstellungen.webseitenChat.aufbewahrungTage",
                "einstellungen.wissen.budgetTokens", "einstellungen.wissen.lintBudgetTokens",
                "einstellungen.wissen.monatsbudgetTokens", "einstellungen.wissen.lintZeitplan.stunde");
    }

    @Test
    void aenderungenDerNeuenEinstellungenSindImDiffSichtbar() {
        var alt = new Konfiguration(1, new Konfiguration.Einstellungen("12-31", null), List.of());
        var neu = new Konfiguration(2, new Konfiguration.Einstellungen("12-31", null,
                new Konfiguration.EinstellungenBranding("Test", "#eeb111"),
                new Konfiguration.ChatEinstellungen(6), new Konfiguration.WebseitenChatEinstellungen(30),
                new Konfiguration.WissenEinstellungen(10_000, null, null,
                        new Konfiguration.LintZeitplan(java.time.DayOfWeek.MONDAY, 4))), List.of());
        assertThat(alt.aenderungen(neu)).extracting(Konfiguration.Aenderung::pfad).containsExactlyInAnyOrder(
                "einstellungen.branding.name", "einstellungen.branding.primaerfarbe",
                "einstellungen.chat.aufbewahrungMonate", "einstellungen.webseitenChat.aufbewahrungTage",
                "einstellungen.wissen.budgetTokens", "einstellungen.wissen.lintZeitplan.wochentag",
                "einstellungen.wissen.lintZeitplan.stunde");
    }
}
