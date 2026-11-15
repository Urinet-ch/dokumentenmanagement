package ch.urinet.archiv.ki.adapter.out.chat;

import ch.urinet.archiv.ki.application.chat.Lueckenbericht;
import ch.urinet.archiv.ki.application.chat.WerkzeugDaten;
import ch.urinet.archiv.mandant.application.MandantEinstellungen;
import ch.urinet.archiv.mandant.domain.Geschaeftsjahresende;
import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.sicherheit.domain.Benutzer;
import ch.urinet.archiv.sicherheit.domain.Rolle;
import ch.urinet.archiv.sicherheit.domain.Sichtregel;
import io.smallrye.config.PropertiesConfigSource;
import io.smallrye.config.SmallRyeConfigBuilder;
import org.eclipse.microprofile.config.Config;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Spec 02, Req 3.6 (Aufgabe 7.1c): Regeln aus der Konfiguration, Lücken je Kategorie, keine Art im Code. */
class KonfigurierteLueckenTest {

    private static final MandantCode URINET = new MandantCode("urinet");
    private static final Sichtregel SICHT = Sichtregel.fuer(new Benutzer("fabian", Rolle.INHABER));
    private static final Clock HEUTE = Clock.fixed(Instant.parse("2026-11-14T10:00:00Z"), ZoneId.of("Europe/Zurich"));

    private final Map<String, String> werte = new HashMap<>();
    private final List<String> abfragen = new ArrayList<>();
    private List<String> belegnummern = List.of();
    private List<WerkzeugDaten.Monatszeile> lohn = List.of();
    private List<WerkzeugDaten.Monatszeile> bank = List.of();
    private String geschaeftsjahresende = "12-31";

    private KonfigurierteLuecken luecken() {
        WerkzeugDaten daten = (WerkzeugDaten) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{WerkzeugDaten.class}, (proxy, methode, args) -> switch (methode.getName()) {
                    case "belegnummern" -> {
                        abfragen.add("belegnummern:" + args[0] + ":" + args[1]);
                        yield belegnummern;
                    }
                    case "monate" -> {
                        WerkzeugDaten.Monatsquelle q = (WerkzeugDaten.Monatsquelle) args[0];
                        abfragen.add("monate:" + q);
                        yield q.art().equals("LOHN") ? lohn : bank;
                    }
                    case "belege" -> 7L;
                    default -> throw new UnsupportedOperationException(methode.getName());
                });
        MandantEinstellungen einstellungen = m -> new MandantEinstellungen.Werte(
                new Geschaeftsjahresende(geschaeftsjahresende), Set.of());
        Config konfiguration = new SmallRyeConfigBuilder().withSources(new PropertiesConfigSource(werte, "test", 500))
                .build();
        return new KonfigurierteLuecken(daten, einstellungen, konfiguration, HEUTE);
    }

    private void regeln() {
        werte.put("archiv.mandant.urinet.luecken.folge", "RECHNUNG:^(?<jahr>\\d{4})-(?<nr>\\d{3})$");
        werte.put("archiv.mandant.urinet.luecken.erwartet-monatlich", "LOHN:BELEGDATUM,BANK:zusatz.periode_bis");
        werte.put("archiv.mandant.urinet.luecken.gruppe", "LOHN:zusatz.person,BANK:PARTNER");
        werte.put("archiv.mandant.urinet.luecken.nur-monat", "LOHN:zusatz.periode");
    }

    private static WerkzeugDaten.Monatszeile zeile(String gruppe, int jahr, int monat) {
        return zeile(gruppe, gruppe, jahr, monat);
    }

    private static WerkzeugDaten.Monatszeile zeile(String schluessel, String gruppe, int jahr, int monat) {
        return new WerkzeugDaten.Monatszeile(schluessel, gruppe, YearMonth.of(jahr, monat), 1);
    }

    @Test
    void ohneRegelnKeineKategorienUndKeinZugriff() {
        Lueckenbericht.Bericht b = luecken().erstellen(URINET, 2026, SICHT);
        assertThat(b.kategorien()).isEmpty();
        assertThat(abfragen).isEmpty();
    }

    @Test
    void nummernfolgeUndMonateJeGruppe() {
        regeln();
        belegnummern = List.of("2026-001", "2026-002", "2026-005", "R0007");
        lohn = List.of(zeile("Anna", 2026, 1), zeile("Anna", 2026, 2), zeile("Anna", 2026, 4), zeile("Beat", 2026, 3),
                zeile("Beat", 2026, 5), zeile(null, 2026, 6));
        bank = List.of(zeile("Kantonalbank", 2026, 8), zeile("Kantonalbank", 2026, 9));

        Lueckenbericht.Bericht b = luecken().erstellen(URINET, 2026, SICHT);

        assertThat(b.belege()).isEqualTo(7);
        assertThat(b.kategorien()).extracting(Lueckenbericht.Kategorie::art).containsExactly("RECHNUNG", "LOHN", "BANK");
        Lueckenbericht.Kategorie folge = b.kategorien().get(0);
        assertThat(folge.regel()).isEqualTo(Lueckenbericht.Regel.FOLGE);
        assertThat(folge.reihen()).singleElement().satisfies(r -> {
            assertThat(r.fehlend()).containsExactly("2026-003", "2026-004");
            assertThat(r.anzahlFehlend()).isEqualTo(2);
            assertThat(r.hoechste()).isEqualTo("2026-005");
        });
        Lueckenbericht.Kategorie lohnKategorie = b.kategorien().get(1);
        assertThat(lohnKategorie.ohneZuordnung()).isEqualTo(1);
        assertThat(lohnKategorie.hinweis()).isNull();
        assertThat(lohnKategorie.reihen()).extracting(Lueckenbericht.Reihe::gruppe).containsExactly("Anna", "Beat");
        assertThat(lohnKategorie.reihen().get(0).fehlend()).containsExactly("2026-03", "2026-05", "2026-06",
                "2026-07", "2026-08", "2026-09", "2026-10");
        assertThat(lohnKategorie.reihen().get(0).ab()).isEqualTo("2026-01");
        assertThat(lohnKategorie.reihen().get(1).fehlend()).containsExactly("2026-04", "2026-06", "2026-07", "2026-08",
                "2026-09", "2026-10");
        Lueckenbericht.Kategorie bankKategorie = b.kategorien().get(2);
        assertThat(bankKategorie.ohneZuordnung()).isNull();
        assertThat(bankKategorie.reihen()).singleElement().satisfies(r -> {
            assertThat(r.fehlend()).containsExactly("2026-10");
            assertThat(r.vorhanden()).isEqualTo(2);
        });
        assertThat(abfragen).contains("belegnummern:RECHNUNG:2026",
                "monate:Monatsquelle[art=LOHN, bezug=BELEGDATUM, gruppe=zusatz.person, nurMonat=zusatz.periode]",
                "monate:Monatsquelle[art=BANK, bezug=zusatz.periode_bis, gruppe=PARTNER, nurMonat=null]");
    }

    @Test
    void namensvariantenDesPartnersBildenEineDurchgehendeReihe() {
        regeln();
        bank = List.of(zeile("partner-1", "Zeta Bank", 2026, 8),
                zeile("partner-1", "Alpha Bank", 2026, 9));

        Lueckenbericht.Kategorie kategorie = luecken().erstellen(URINET, 2026, SICHT).kategorien().get(2);

        assertThat(kategorie.reihen()).singleElement().satisfies(reihe -> {
            assertThat(reihe.gruppe()).isEqualTo("Alpha Bank");
            assertThat(reihe.vorhanden()).isEqualTo(2);
            assertThat(reihe.fehlend()).containsExactly("2026-10");
        });
    }

    @Test
    void geschaeftsjahrOhneAbgeschlosseneVolleMonateNenntDenHinweis() {
        regeln();

        Lueckenbericht.Kategorie kategorie = luecken().erstellen(URINET, 2027, SICHT).kategorien().get(1);

        assertThat(kategorie.regel()).isEqualTo(Lueckenbericht.Regel.ERWARTET);
        assertThat(kategorie.hinweis()).isEqualTo(
                "Für dieses Geschäftsjahr liegen keine abgeschlossenen vollen Monate vor; Monatslücken wurden nicht geprüft.");
        assertThat(kategorie.reihen()).isEmpty();
    }

    @Test
    void reiheOhneDokumentImJahrWirdNichtGemeldet() {
        regeln();
        lohn = List.of(zeile("Anna", 2026, 1), zeile("Neu", 2027, 1));
        assertThat(luecken().erstellen(URINET, 2026, SICHT).kategorien().get(1).reihen())
                .extracting(Lueckenbericht.Reihe::gruppe).containsExactly("Anna");
    }

    @Test
    void geschaeftsjahrMitAnderemEndeUndVergangenesJahr() {
        regeln();
        geschaeftsjahresende = "06-30";
        lohn = List.of(zeile("Anna", 2024, 7), zeile("Anna", 2024, 9));
        Lueckenbericht.Kategorie k = luecken().erstellen(URINET, 2025, SICHT).kategorien().get(1);
        assertThat(k.reihen().get(0).fehlend()).containsExactly("2024-08", "2024-10", "2024-11", "2024-12", "2025-01",
                "2025-02", "2025-03", "2025-04", "2025-05", "2025-06");
    }

    @Test
    void ungueltigeKonfigurationBrichtAbOhneWertInDerMeldung() {
        werte.put("archiv.mandant.urinet.luecken.folge", "RECHNUNG:^(?<jahr>geheim\\d{4}$");
        assertThatThrownBy(() -> luecken().erstellen(URINET, 2026, SICHT)).isInstanceOf(IllegalStateException.class)
                .hasMessage("archiv.mandant.urinet.luecken.folge ist ungültig");
        werte.put("archiv.mandant.urinet.luecken.folge", "RECHNUNG:^(\\d{4})$");
        assertThatThrownBy(() -> luecken().erstellen(URINET, 2026, SICHT)).hasMessageContaining("folge");
        werte.put("archiv.mandant.urinet.luecken.folge", "rechnung:^(?<nr>\\d)$");
        assertThatThrownBy(() -> luecken().erstellen(URINET, 2026, SICHT)).hasMessageContaining("folge");
        werte.remove("archiv.mandant.urinet.luecken.folge");
        werte.put("archiv.mandant.urinet.luecken.erwartet-monatlich", "LOHN:datum");
        assertThatThrownBy(() -> luecken().erstellen(URINET, 2026, SICHT)).hasMessageContaining("erwartet-monatlich");
        werte.put("archiv.mandant.urinet.luecken.erwartet-monatlich", "LOHN:BELEGDATUM");
        werte.put("archiv.mandant.urinet.luecken.gruppe", "ANDERE:PARTNER");
        assertThatThrownBy(() -> luecken().erstellen(URINET, 2026, SICHT)).hasMessageContaining("gruppe");
        werte.put("archiv.mandant.urinet.luecken.gruppe", "LOHN:zusatz.Person; drop");
        assertThatThrownBy(() -> luecken().erstellen(URINET, 2026, SICHT)).hasMessageContaining("gruppe");
    }

    @Test
    void anderesMandantHatEigeneRegeln() {
        regeln();
        assertThat(luecken().erstellen(new MandantCode("vhf-gsk"), 2026, SICHT).kategorien()).isEmpty();
    }
}
