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
import java.time.YearMonth;
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

    private static Konfiguration mitRegel(String art, Map<String, Object> parameter, String geschaeftsjahrEnde) {
        Regel regel = new Regel("erwartet", art, "ERWARTET", parameter, "WARNUNG", "Beleg fehlt", true);
        Konfiguration.Art eintrag = new Konfiguration.Art(art, art, null, List.of(), List.of(),
                null, "NORMAL", new Konfiguration.Aufbewahrung(null, "BELEGDATUM"), false, true);
        return new Konfiguration(1,
                new Konfiguration.Einstellungen(geschaeftsjahrEnde, new Konfiguration.Kennungen(null, List.of())),
                List.of(eintrag), List.of(), List.of(regel));
    }

    private static WerkzeugDaten daten(List<WerkzeugDaten.Monatszeile> monate, List<WerkzeugDaten.Jahreszeile> jahre,
                                       Object[] aufruf) {
        return (WerkzeugDaten) java.lang.reflect.Proxy.newProxyInstance(
                RegelbasierteLueckenTest.class.getClassLoader(), new Class<?>[]{WerkzeugDaten.class},
                (proxy, methode, args) -> switch (methode.getName()) {
                    case "monate" -> {
                        aufruf[0] = args[0];
                        yield monate;
                    }
                    case "jahre" -> {
                        aufruf[0] = args[0];
                        aufruf[1] = args[1];
                        yield jahre;
                    }
                    case "belege" -> 0L;
                    default -> throw new UnsupportedOperationException(methode.getName());
                });
    }

    private static WerkzeugDaten.Monatszeile zeile(String schluessel, String gruppe, String monat) {
        return new WerkzeugDaten.Monatszeile(schluessel, gruppe, YearMonth.parse(monat), 1);
    }

    @Test
    void erwarteteMonateMitGruppeErgebenEineReiheJeGruppe() {
        Konfiguration config = mitRegel("LOHN",
                Map.of("je", "MONAT", "datum", "BELEGDATUM", "gruppe", "zusatz.person"), "12-31");
        Object[] aufruf = new Object[2];
        WerkzeugDaten daten = daten(List.of(zeile("anna", "Anna", "2026-01"), zeile("anna", "Anna", "2026-02"),
                zeile("beat", "Beat", "2026-01"), zeile("beat", "Beat", "2026-03"),
                zeile(null, null, "2026-02")), List.of(), aufruf);
        Clock uhr = Clock.fixed(Instant.parse("2026-04-10T10:00:00Z"), ZoneId.of("Europe/Zurich"));

        Lueckenbericht.Kategorie kategorie = new KonfigurierteLuecken(daten, () -> config, uhr)
                .erstellen(new MandantCode("urinet"), 2026, SICHT).kategorien().getFirst();

        assertThat(aufruf[0]).isEqualTo(new WerkzeugDaten.Monatsquelle("LOHN", WerkzeugDaten.BELEGDATUM,
                "zusatz.person", null));
        assertThat(kategorie.ohneZuordnung()).isEqualTo(1L);
        assertThat(kategorie.reihen()).extracting(Lueckenbericht.Reihe::gruppe).containsExactly("Anna", "Beat");
        assertThat(kategorie.reihen()).extracting(Lueckenbericht.Reihe::fehlend)
                .containsExactly(List.of("2026-03"), List.of("2026-02"));
    }

    @Test
    void abVorDemErstenBelegZaehltDieMonateDazwischenAlsLuecke() {
        Konfiguration config = mitRegel("LOHN", Map.of("je", "MONAT", "datum", "BELEGDATUM", "ab", "2026-01-01"),
                "12-31");
        WerkzeugDaten daten = daten(List.of(zeile("", "", "2026-03"), zeile("", "", "2026-04")), List.of(),
                new Object[2]);
        Clock uhr = Clock.fixed(Instant.parse("2026-06-10T10:00:00Z"), ZoneId.of("Europe/Zurich"));

        Lueckenbericht.Reihe reihe = new KonfigurierteLuecken(daten, () -> config, uhr)
                .erstellen(new MandantCode("urinet"), 2026, SICHT).kategorien().getFirst().reihen().getFirst();

        assertThat(reihe.ab()).isEqualTo("2026-01");
        assertThat(reihe.fehlend()).containsExactly("2026-01", "2026-02", "2026-05");
    }

    @Test
    void erwarteteJahreOhneDokumentUndOhneAbErgebenKeineReihe() {
        Konfiguration config = mitRegel("JAHRESBERICHT", Map.of("je", "JAHR", "datum", "BELEGDATUM"), "12-31");
        Clock uhr = Clock.fixed(Instant.parse("2027-03-01T10:00:00Z"), ZoneId.of("Europe/Zurich"));

        Lueckenbericht.Kategorie kategorie = new KonfigurierteLuecken(daten(List.of(), List.of(), new Object[2]),
                () -> config, uhr).erstellen(new MandantCode("urinet"), 2026, SICHT).kategorien().getFirst();

        assertThat(kategorie.reihen()).isEmpty();
    }

    @Test
    void erwarteteJahrePruefenNurAbgeschlosseneGeschaeftsjahre() {
        Konfiguration config = mitRegel("JAHRESBERICHT", Map.of("je", "JAHR", "datum", "BELEGDATUM"), "12-31");
        WerkzeugDaten daten = daten(List.of(), List.of(new WerkzeugDaten.Jahreszeile(2023, 1)), new Object[2]);
        Clock uhr = Clock.fixed(Instant.parse("2026-11-18T10:00:00Z"), ZoneId.of("Europe/Zurich"));

        Lueckenbericht.Reihe reihe = new KonfigurierteLuecken(daten, () -> config, uhr)
                .erstellen(new MandantCode("urinet"), 2026, SICHT).kategorien().getFirst().reihen().getFirst();

        // 2026 läuft noch: geprüft bis 2025
        assertThat(reihe.hoechste()).isEqualTo("2025");
        assertThat(reihe.fehlend()).containsExactly("2024", "2025");
    }

    @Test
    void erwarteteJahreFolgenDemGeschaeftsjahresende() {
        Konfiguration config = mitRegel("JAHRESBERICHT",
                Map.of("je", "JAHR", "datum", "BELEGDATUM", "ab", "2024-08-15"), "06-30");
        Object[] aufruf = new Object[2];
        WerkzeugDaten daten = daten(List.of(), List.of(new WerkzeugDaten.Jahreszeile(2025, 1)), aufruf);
        Clock uhr = Clock.fixed(Instant.parse("2026-07-10T10:00:00Z"), ZoneId.of("Europe/Zurich"));

        Lueckenbericht.Reihe reihe = new KonfigurierteLuecken(daten, () -> config, uhr)
                .erstellen(new MandantCode("urinet"), 2026, SICHT).kategorien().getFirst().reihen().getFirst();

        assertThat(aufruf[1]).isEqualTo(java.time.MonthDay.of(6, 30));
        // 15.08.2024 gehört zum Geschäftsjahr 2025 (01.07.2024–30.06.2025); 2026 ist am 10.07.2026 abgeschlossen
        assertThat(reihe.ab()).isEqualTo("2025");
        assertThat(reihe.fehlend()).containsExactly("2026");
        assertThat(KonfigurierteLuecken.geschaeftsjahrDes(java.time.LocalDate.of(2024, 6, 30),
                java.time.MonthDay.of(6, 30))).isEqualTo(2024);
    }

    @Test
    void reiheMitDokumentNurImFolgejahrWirdNichtGemeldet() {
        Konfiguration config = mitRegel("LOHN",
                Map.of("je", "MONAT", "datum", "BELEGDATUM", "gruppe", "zusatz.person"), "12-31");
        WerkzeugDaten daten = daten(List.of(zeile("anna", "Anna", "2026-01"), zeile("neu", "Neu", "2027-01")),
                List.of(), new Object[2]);
        Clock uhr = Clock.fixed(Instant.parse("2027-02-10T10:00:00Z"), ZoneId.of("Europe/Zurich"));

        Lueckenbericht.Kategorie kategorie = new KonfigurierteLuecken(daten, () -> config, uhr)
                .erstellen(new MandantCode("urinet"), 2026, SICHT).kategorien().getFirst();

        assertThat(kategorie.reihen()).extracting(Lueckenbericht.Reihe::gruppe).containsExactly("Anna");
    }

    @Test
    void geschaeftsjahrOhneAbgeschlossenenVollenMonatNenntDenHinweis() {
        Konfiguration config = mitRegel("LOHN", Map.of("je", "MONAT", "datum", "BELEGDATUM", "ab", "2026-01-01"),
                "12-31");
        WerkzeugDaten daten = daten(List.of(zeile("", "", "2026-03")), List.of(), new Object[2]);
        Clock uhr = Clock.fixed(Instant.parse("2026-11-18T10:00:00Z"), ZoneId.of("Europe/Zurich"));

        Lueckenbericht.Kategorie kategorie = new KonfigurierteLuecken(daten, () -> config, uhr)
                .erstellen(new MandantCode("urinet"), 2027, SICHT).kategorien().getFirst();

        assertThat(kategorie.hinweis()).startsWith("Für dieses Geschäftsjahr liegen keine abgeschlossenen vollen Monate vor");
        assertThat(kategorie.reihen()).isEmpty();
    }

    @Test
    void erwarteteMonateFolgenDemGeschaeftsjahresende() {
        Konfiguration config = mitRegel("LOHN",
                Map.of("je", "MONAT", "datum", "BELEGDATUM", "gruppe", "zusatz.person"), "06-30");
        WerkzeugDaten daten = daten(List.of(zeile("anna", "Anna", "2024-07"), zeile("anna", "Anna", "2024-09")),
                List.of(), new Object[2]);
        Clock uhr = Clock.fixed(Instant.parse("2026-11-18T10:00:00Z"), ZoneId.of("Europe/Zurich"));

        Lueckenbericht.Reihe reihe = new KonfigurierteLuecken(daten, () -> config, uhr)
                .erstellen(new MandantCode("urinet"), 2025, SICHT).kategorien().getFirst().reihen().getFirst();

        // Geschäftsjahr 2025 = 01.07.2024–30.06.2025
        assertThat(reihe.fehlend()).containsExactly("2024-08", "2024-10", "2024-11", "2024-12", "2025-01",
                "2025-02", "2025-03", "2025-04", "2025-05", "2025-06");
    }

    @Test
    void berichtVerweigertRollenOhneVolleSichtAufA3UndA4() {
        Konfiguration config = mitRegel("LOHN", Map.of("je", "MONAT", "datum", "BELEGDATUM"), "12-31");
        Sichtregel mitarbeiter = Sichtregel.fuer(new Benutzer("joel", Rolle.MITARBEITER));

        assertThatThrownBy(() -> new KonfigurierteLuecken(daten(List.of(), List.of(), new Object[2]), () -> config)
                .erstellen(new MandantCode("urinet"), 2026, mitarbeiter))
                .isInstanceOf(ch.urinet.archiv.ki.application.chat.WerkzeugFehler.class);
    }

    private static Object throwUnsupported(String methode) {
        throw new UnsupportedOperationException(methode);
    }
}
