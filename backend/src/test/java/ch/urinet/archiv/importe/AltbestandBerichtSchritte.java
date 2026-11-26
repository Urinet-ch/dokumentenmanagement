package ch.urinet.archiv.importe;

import ch.urinet.archiv.ki.adapter.out.chat.KonfigurierteLuecken;
import ch.urinet.archiv.ki.application.chat.Lueckenbericht;
import ch.urinet.archiv.ki.application.chat.WerkzeugDaten;
import ch.urinet.archiv.konfiguration.domain.Konfiguration;
import ch.urinet.archiv.konfiguration.domain.Regel;
import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.sicherheit.domain.Benutzer;
import ch.urinet.archiv.sicherheit.domain.Rolle;
import ch.urinet.archiv.sicherheit.domain.Sichtregel;
import io.cucumber.datatable.DataTable;
import io.cucumber.java.de.Dann;
import io.cucumber.java.de.Wenn;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

public class AltbestandBerichtSchritte {

    private Map<Integer, Lueckenbericht.Bericht> berichte;

    @Test
    void lueckenberichtBerechnetAusgabenJeReiheUndJahr() {
        lueckenberichteBerechnen(2025, 2026);
        pruefen(2025, "BULLETIN", List.of("3"));
        pruefen(2026, "BULLETIN", List.of("2"));
        pruefen(2025, "MERKBLATT", List.of("2"));
        pruefen(2026, "MERKBLATT", List.of("1", "3"));
    }

    @Wenn("der serverseitige Lückenbericht für die Geschäftsjahre «{int}» und «{int}» berechnet wird")
    public void lueckenberichteBerechnen(int erstesJahr, int zweitesJahr) {
        List<Konfiguration.Art> arten = List.of(art("BULLETIN"), art("MERKBLATT"));
        List<Regel> regeln = List.of(folgenregel("BULLETIN"), folgenregel("MERKBLATT"));
        Konfiguration konfiguration = new Konfiguration(1,
                new Konfiguration.Einstellungen("12-31", new Konfiguration.Kennungen(null, List.of())),
                arten, List.of(), regeln);
        WerkzeugDaten daten = synthetischeAusgaben();
        KonfigurierteLuecken luecken = new KonfigurierteLuecken(daten, () -> konfiguration, Duration.ofMillis(50));
        Sichtregel sicht = Sichtregel.fuer(new Benutzer("test-inhaber", Rolle.INHABER));
        MandantCode mandant = new MandantCode("vhf-gsk");

        berichte = Map.of(
                erstesJahr, luecken.erstellen(mandant, erstesJahr, sicht),
                zweitesJahr, luecken.erstellen(mandant, zweitesJahr, sicht));
    }

    @Dann("zeigt der Lückenbericht diese fehlenden Ausgaben:")
    public void fehlendeAusgaben(DataTable tabelle) {
        for (Map<String, String> zeile : tabelle.asMaps()) {
            int jahr = Integer.parseInt(zeile.get("jahr"));
            List<String> erwartet = List.of(zeile.get("fehlend").split(", "));
            pruefen(jahr, zeile.get("reihe"), erwartet);
        }
    }

    private void pruefen(int jahr, String art, List<String> erwartet) {
        Lueckenbericht.Bericht bericht = berichte.get(jahr);
        assertThat(bericht).as("Bericht für %s", jahr).isNotNull();
        assertThat(bericht.geschaeftsjahr()).isEqualTo(jahr);
        assertThat(bericht.kategorien()).filteredOn(kategorie -> kategorie.art().equals(art))
                .singleElement().satisfies(kategorie -> assertThat(kategorie.reihen()).singleElement()
                        .extracting(Lueckenbericht.Reihe::fehlend).isEqualTo(erwartet));
    }

    private static Konfiguration.Art art(String code) {
        return new Konfiguration.Art(code, code, null, List.of(), List.of(), null, "NORMAL",
                new Konfiguration.Aufbewahrung(null, "BELEGDATUM"), false, true);
    }

    private static Regel folgenregel(String art) {
        return new Regel("ausgabe_folge_" + art.toLowerCase(), art, "FOLGE",
                Map.of("feld", "zusatz.ausgabe", "zeitraum", Map.of("feld", "zusatz.jahr")),
                "WARNUNG", "Ausgabe fehlt.", true);
    }

    private static WerkzeugDaten synthetischeAusgaben() {
        return (WerkzeugDaten) Proxy.newProxyInstance(AltbestandBerichtSchritte.class.getClassLoader(),
                new Class<?>[]{WerkzeugDaten.class}, (proxy, methode, args) -> switch (methode.getName()) {
                    case "folgewerte" -> {
                        WerkzeugDaten.Folgequelle quelle = (WerkzeugDaten.Folgequelle) args[0];
                        yield switch (quelle.art() + ":" + quelle.periode()) {
                            case "BULLETIN:2025" -> List.of("1", "2", "4");
                            case "BULLETIN:2026" -> List.of("1", "3");
                            case "MERKBLATT:2025" -> List.of("1", "3");
                            case "MERKBLATT:2026" -> List.of("2", "4");
                            default -> throw new IllegalArgumentException("Unbekannte synthetische Folgenquelle");
                        };
                    }
                    case "belege" -> 0L;
                    default -> throw new UnsupportedOperationException(methode.getName());
                });
    }
}
