package ch.urinet.archiv.ki.adapter.out.chat;

import ch.urinet.archiv.ki.application.chat.Lueckenbericht;
import ch.urinet.archiv.ki.application.chat.WerkzeugDaten;
import ch.urinet.archiv.ki.domain.chat.Nummernfolge;
import ch.urinet.archiv.konfiguration.application.KonfigurationLesen;
import ch.urinet.archiv.konfiguration.domain.Konfiguration;
import ch.urinet.archiv.konfiguration.domain.Regel;
import ch.urinet.archiv.mandant.domain.Geschaeftsjahresende;
import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.sicherheit.domain.Benutzer;
import ch.urinet.archiv.sicherheit.domain.Rolle;
import ch.urinet.archiv.sicherheit.domain.Sichtregel;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RegelbasierteLueckenTest {

    private static final Sichtregel SICHT = Sichtregel.fuer(new Benutzer("fabian", Rolle.INHABER));

    @Test
    void aktiveFolgenregelLiestFeldUndZeitraumAusDerMandantenkonfiguration() {
        Regel regel = new Regel("rechnung_nummer", "RECHNUNG", "FOLGE",
                Map.of("feld", "zusatz.nummer", "zeitraum", "GESCHAEFTSJAHR", "beginn", 10),
                "WARNUNG", "Nummernfolge prüfen", true);
        Konfiguration.Art art = new Konfiguration.Art("RECHNUNG", "Rechnung", null, List.of(), List.of(),
                null, "NORMAL", new Konfiguration.Aufbewahrung(null, "BELEGDATUM"), false, true,
                false, List.of(), null, null, false, List.of(), null, 0);
        Konfiguration config = new Konfiguration(1,
                new Konfiguration.Einstellungen("12-31", new Konfiguration.Kennungen(null, List.of())),
                List.of(art), List.of(), List.of(regel));

        WerkzeugDaten.Folgequelle[] aufruf = new WerkzeugDaten.Folgequelle[1];
        WerkzeugDaten daten = (WerkzeugDaten) java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{WerkzeugDaten.class}, (proxy, methode, args) -> switch (methode.getName()) {
                    case "folgewerte" -> {
                        aufruf[0] = (WerkzeugDaten.Folgequelle) args[0];
                        yield List.of("10", "12");
                    }
                    case "belege" -> 2L;
                    default -> throw new UnsupportedOperationException(methode.getName());
                });
        KonfigurationLesen lesen = () -> config;
        KonfigurierteLuecken luecken = new KonfigurierteLuecken(daten, lesen);

        Lueckenbericht.Bericht bericht = luecken.erstellen(new MandantCode("urinet"), 2026, SICHT);

        assertThat(aufruf[0]).isEqualTo(new WerkzeugDaten.Folgequelle("RECHNUNG", "zusatz.nummer", false,
                "GESCHAEFTSJAHR", null, 2026));
        assertThat(bericht.kategorien()).singleElement().satisfies(kategorie -> {
            assertThat(kategorie.regel()).isEqualTo(Lueckenbericht.Regel.FOLGE);
            assertThat(kategorie.reihen()).singleElement()
                    .extracting(Lueckenbericht.Reihe::fehlend).isEqualTo(List.of("11"));
        });
    }

    @Test
    void deaktivierteRegelnErzeugenKeineKategorie() {
        Regel regel = new Regel("aus", "RECHNUNG", "FOLGE",
                Map.of("feld", "geschaeftsjahr", "zeitraum", "GESAMT"), "WARNUNG", "Nicht aktiv", false);
        Konfiguration.Art art = new Konfiguration.Art("RECHNUNG", "Rechnung", null, List.of(), List.of(),
                null, "NORMAL", new Konfiguration.Aufbewahrung(null, "BELEGDATUM"), false, true);
        Konfiguration config = new Konfiguration(1,
                new Konfiguration.Einstellungen("12-31", new Konfiguration.Kennungen(null, List.of())),
                List.of(art), List.of(), List.of(regel));
        WerkzeugDaten daten = (WerkzeugDaten) java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{WerkzeugDaten.class},
                (proxy, methode, args) -> methode.getName().equals("belege") ? 0L
                        : throwUnsupported(methode.getName()));

        Lueckenbericht.Bericht bericht = new KonfigurierteLuecken(daten, () -> config)
                .erstellen(new MandantCode("urinet"), 2026, SICHT);

        assertThat(bericht.kategorien()).isEmpty();
    }

    @Test
    void erwarteteMonateBeginnenBeimKonfiguriertenDatumAuchOhneBisherigeBelege() {
        Regel regel = new Regel("monatlich", "LOHN", "ERWARTET",
                Map.of("je", "MONAT", "datum", "BELEGDATUM", "ab", "2026-02-14"),
                "WARNUNG", "Monatsbeleg fehlt", true);
        Konfiguration.Art art = new Konfiguration.Art("LOHN", "Lohn", null, List.of(), List.of(),
                null, "PERSONAL", new Konfiguration.Aufbewahrung(null, "BELEGDATUM"), false, true);
        Konfiguration config = new Konfiguration(1,
                new Konfiguration.Einstellungen("12-31", new Konfiguration.Kennungen(null, List.of())),
                List.of(art), List.of(), List.of(regel));
        WerkzeugDaten daten = (WerkzeugDaten) java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{WerkzeugDaten.class}, (proxy, methode, args) -> switch (methode.getName()) {
                    case "monate" -> List.of();
                    case "belege" -> 0L;
                    default -> throw new UnsupportedOperationException(methode.getName());
                });
        Clock uhr = Clock.fixed(Instant.parse("2026-11-18T10:00:00Z"), ZoneId.of("Europe/Zurich"));
        KonfigurierteLuecken luecken = new KonfigurierteLuecken(daten, () -> config, uhr);

        Lueckenbericht.Kategorie kategorie = luecken.erstellen(new MandantCode("urinet"), 2026, SICHT)
                .kategorien().getFirst();

        assertThat(kategorie.regel()).isEqualTo(Lueckenbericht.Regel.ERWARTET);
        assertThat(kategorie.reihen()).singleElement().satisfies(reihe -> {
            assertThat(reihe.ab()).isEqualTo("2026-02");
            assertThat(reihe.fehlend()).containsExactly("2026-02", "2026-03", "2026-04", "2026-05", "2026-06",
                    "2026-07", "2026-08", "2026-09", "2026-10");
        });
    }

    @Test
    void lueckenberichtBrichtBeiZuVielenBelegnummernAb() {
        Regel regel = new Regel("rechnung_nummer", "RECHNUNG", "FOLGE",
                Map.of("feld", "zusatz.nummer", "zeitraum", "GESAMT"), "WARNUNG", "Nummernfolge prüfen", true);
        Konfiguration.Art art = new Konfiguration.Art("RECHNUNG", "Rechnung", null, List.of(), List.of(),
                null, "NORMAL", new Konfiguration.Aufbewahrung(null, "BELEGDATUM"), false, true);
        Konfiguration config = new Konfiguration(1,
                new Konfiguration.Einstellungen("12-31", new Konfiguration.Kennungen(null, List.of())),
                List.of(art), List.of(), List.of(regel));
        WerkzeugDaten daten = (WerkzeugDaten) java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{WerkzeugDaten.class}, (proxy, methode, args) -> switch (methode.getName()) {
                    case "folgewerte" -> Collections.nCopies(Nummernfolge.BELEGNUMMERN_MAX + 1, "1");
                    case "belege" -> 0L;
                    default -> throw new UnsupportedOperationException(methode.getName());
                });

        assertThatThrownBy(() -> new KonfigurierteLuecken(daten, () -> config)
                .erstellen(new MandantCode("urinet"), 2026, SICHT))
                .isInstanceOf(IllegalStateException.class);
    }

    private static Object throwUnsupported(String methode) {
        throw new UnsupportedOperationException(methode);
    }
}
