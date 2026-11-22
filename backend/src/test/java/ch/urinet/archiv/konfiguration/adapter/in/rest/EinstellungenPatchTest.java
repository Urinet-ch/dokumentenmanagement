package ch.urinet.archiv.konfiguration.adapter.in.rest;

import ch.urinet.archiv.gemeinsam.domain.FachFehler;
import ch.urinet.archiv.gemeinsam.domain.Fehlercode;
import ch.urinet.archiv.gemeinsam.domain.KonfigurationUngueltig;
import ch.urinet.archiv.konfiguration.domain.EinstellungsRisiko;
import ch.urinet.archiv.konfiguration.domain.Einstellungskatalog.Gruppe;
import ch.urinet.archiv.konfiguration.domain.Konfiguration;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EinstellungenPatchTest {
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final Konfiguration.Einstellungen bisher = new Konfiguration.Einstellungen("06-30",
            new Konfiguration.Kennungen(null, List.of("CH000")),
            new Konfiguration.EinstellungenBranding("Archiv", "#eeb111"),
            new Konfiguration.ChatEinstellungen(12), null, null);

    @Test
    void patchAendertNurGenannteFelderUndSetztGruppenZurueck() throws Exception {
        var ergebnis = anwenden("""
                {"branding":{"name":null},"chat":null,"kennungen":{"ibans":["CH111","CH222"]}}
                """);

        assertThat(ergebnis.befunde()).isEmpty();
        assertThat(ergebnis.ziel().geschaeftsjahrEnde()).isEqualTo("06-30");
        assertThat(ergebnis.ziel().branding().name()).isNull();
        assertThat(ergebnis.ziel().branding().primaerfarbe()).isEqualTo("#eeb111");
        assertThat(ergebnis.ziel().chat().aufbewahrungMonate()).isNull();
        assertThat(ergebnis.ziel().kennungen().ibans()).containsExactly("CH111", "CH222");
    }

    @Test
    void nullSetztAuchPflichtwerteAufIhrenStandard() throws Exception {
        var ergebnis = anwenden("{\"geschaeftsjahrEnde\":null,\"kennungen\":null}");
        assertThat(ergebnis.befunde()).isEmpty();
        assertThat(ergebnis.ziel().geschaeftsjahrEnde()).isEqualTo("12-31");
        assertThat(ergebnis.ziel().kennungen().ibans()).isEmpty();
    }

    @Test
    void unbekannteOderFalschTypisierteFelderWerdenNichtUebernommen() throws Exception {
        var ergebnis = anwenden("""
                {"mandant":"fremd","branding":{"name":42},"chat":{"aufbewahrungMonate":6}}
                """);
        assertThat(ergebnis.befunde()).extracting(KonfigurationUngueltig.Befund::code)
                .containsExactlyInAnyOrder("FELD_UNBEKANNT", "TYP_UNGUELTIG");
        assertThat(ergebnis.ziel().branding().name()).isEqualTo("Archiv");
        assertThat(ergebnis.ziel().chat().aufbewahrungMonate()).isEqualTo(6);
    }

    @Test
    void auchBeiFalschemZahlentypIstEinErkennbaresRisikoVorDemBefundPruefbar() throws Exception {
        var ergebnis = anwenden("""
                {"chat":{"aufbewahrungMonate":11.9},"wissen":{"monatsbudgetTokens":1e100}}
                """);
        assertThat(ergebnis.befunde()).hasSize(2);
        assertThat(EinstellungsRisiko.geaendert(bisher, ergebnis.ziel(),
                new EinstellungsRisiko.Startwerte(null, null, null), "urinet"))
                .containsExactlyInAnyOrder("chat.aufbewahrungMonate", "wissen.monatsbudgetTokens");
    }

    @Test
    void sehrKleineZahlWirdOhneAusdehnungDesExponentenGeprueft() throws Exception {
        var ergebnis = anwenden("{\"chat\":{\"aufbewahrungMonate\":1e-1000000000}}");
        assertThat(ergebnis.befunde()).hasSize(1);
        assertThat(ergebnis.ziel().chat().aufbewahrungMonate()).isZero();
    }

    @Test
    void begrenzungGiltAuchOhneContentLength() {
        byte[] eingabe = new byte[16 * 1024 + 1];
        assertThatThrownBy(() -> EinstellungenPatch.lesen(new ByteArrayInputStream(eingabe)))
                .isInstanceOf(FachFehler.class)
                .satisfies(fehler -> assertThat(((FachFehler) fehler).code()).isEqualTo(Fehlercode.DATEI_ZU_GROSS));
    }

    @Test
    void doppelteSchluesselUndUngueltigesUtf8SindUngueltig() {
        assertThatThrownBy(() -> lesen("{\"chat\":{},\"chat\":{}}"))
                .isInstanceOf(KonfigurationUngueltig.class);
        assertThatThrownBy(() -> EinstellungenPatch.lesen(new ByteArrayInputStream(new byte[] {(byte) 0xff})))
                .isInstanceOf(KonfigurationUngueltig.class);
    }

    @Test
    void zuruecksetzenNachGruppeOderVersionIstEindeutig() throws Exception {
        var auftrag = EinstellungenZuruecksetzen.lesen(lesen("{\"gruppe\":\"CHAT\"}"));
        assertThat(auftrag.gruppe()).isEqualTo(Gruppe.CHAT);
        assertThat(EinstellungenPatch.anwenden(json, bisher,
                EinstellungenZuruecksetzen.gruppenPatch(json, auftrag.gruppe())).ziel().chat().aufbewahrungMonate())
                .isNull();
        assertThat(EinstellungenZuruecksetzen.lesen(lesen("{\"version\":3}")).version()).isEqualTo(3);
        for (String ungueltig : List.of("{}", "{\"gruppe\":\"NICHTS\"}", "{\"version\":0}",
                "{\"gruppe\":\"CHAT\",\"version\":3}", "{\"version\":3,\"mandant\":\"fremd\"}")) {
            assertThatThrownBy(() -> EinstellungenZuruecksetzen.lesen(lesen(ungueltig)))
                    .isInstanceOf(KonfigurationUngueltig.class);
        }
    }

    private EinstellungenPatch.Ergebnis anwenden(String patch) throws Exception {
        return EinstellungenPatch.anwenden(json, bisher, lesen(patch));
    }

    private static com.fasterxml.jackson.databind.JsonNode lesen(String patch) throws Exception {
        return EinstellungenPatch.lesen(new ByteArrayInputStream(patch.getBytes(StandardCharsets.UTF_8)));
    }
}
