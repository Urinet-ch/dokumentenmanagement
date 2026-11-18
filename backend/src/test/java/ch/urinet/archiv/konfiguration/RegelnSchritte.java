package ch.urinet.archiv.konfiguration;

import ch.urinet.archiv.gemeinsam.Testanmeldung;
import ch.urinet.archiv.gemeinsam.domain.Fehlercode;
import ch.urinet.archiv.gemeinsam.domain.RegelVerletzt;
import ch.urinet.archiv.konfiguration.application.KonfigurationVerwaltung;
import ch.urinet.archiv.konfiguration.application.RegelDatenPort;
import ch.urinet.archiv.konfiguration.application.RegelPruefung;
import ch.urinet.archiv.konfiguration.domain.Konfiguration;
import ch.urinet.archiv.konfiguration.domain.Regel;
import ch.urinet.archiv.mandant.domain.MandantCode;
import io.agroal.api.AgroalDataSource;
import io.cucumber.java.After;
import io.cucumber.java.de.Angenommen;
import io.cucumber.java.de.Dann;
import io.cucumber.java.de.Wenn;
import io.restassured.response.Response;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

public class RegelnSchritte {

    /** Neue Konfigurationsversion mit den Regeln aus der Tabelle {@code regel}, sonst wie die letzte (wie 046). */
    private static final String VERSION_MIT_REGELN = """
            insert into konfiguration_version (version, wer, grund, quelle, inhalt, sha256)
            select n.version, 'test:regeln', 'Testregeln', 'APP', i.inhalt,
                encode(sha256(convert_to(i.inhalt::text, 'UTF8')), 'hex')
            from (select coalesce(max(version), 0) + 1 as version from konfiguration_version) n
            cross join lateral (
                select a.inhalt || jsonb_build_object('version', n.version, 'regeln', coalesce((
                    select jsonb_agg(jsonb_build_object('code', r.code, 'art', r.art, 'typ', r.typ,
                        'parameter', r.parameter, 'schwere', r.schwere, 'meldung', r.meldung, 'aktiv', r.aktiv)
                        order by r.code collate "C") from regel r), '[]'::jsonb)) as inhalt
                from (select inhalt from konfiguration_version order by version desc limit 1) a) i
            """;

    private record Eingerichtet(String mandant, String code) {
    }

    @Inject
    Instance<AgroalDataSource> datenbank;

    private final List<Eingerichtet> eingerichtet = new ArrayList<>();

    private List<Konfiguration.Befund> befunde;
    private List<Regel.Parameter> typisierteRegeln = List.of();
    private List<RegelPruefung.Warnung> warnungen = List.of();
    private RegelVerletzt fehler;

    @Wenn("die vier Regeltypen der Konfiguration aufgebaut werden")
    public void regeltypenAufbauen() {
        List<Regel> regeln = List.of(
                new Regel("datum_pflicht", "RECHNUNG", "PFLICHT_WENN",
                        Map.of("wenn", Map.of("feld", "belegnummer", "gleich", "R1"), "dann", List.of("datum")),
                        "FEHLER", "Belegdatum fehlt.", true),
                new Regel("nummer_eindeutig", "RECHNUNG", "EINDEUTIG", Map.of("felder", List.of("belegnummer")),
                        "FEHLER", "Nummer bereits erfasst.", true),
                new Regel("nummer_folge", "RECHNUNG", "FOLGE",
                        Map.of("feld", "zusatz.nummer", "zeitraum", "GESCHAEFTSJAHR"),
                        "WARNUNG", "Nummernfolge prüfen.", true),
                new Regel("beleg_erwartet", "RECHNUNG", "ERWARTET",
                        Map.of("je", "MONAT", "datum", "BELEGDATUM"),
                        "WARNUNG", "Monatsbeleg fehlt.", true));
        typisierteRegeln = regeln.stream().map(Regel::typisierteParameter).toList();
    }

    @Wenn("eine aktive Pflichtregel mit Schwere «{word}» und fehlendem Datum geprüft wird")
    public void pflichtregelPruefen(String schwere) {
        pruefenPflichtregel(schwere, null);
    }

    @Wenn("eine bereits verletzte Pflichtregel mit unveränderten Metadaten geprüft wird")
    public void bestehendePflichtverletzungPruefen() {
        pruefenPflichtregel("FEHLER", Map.of("belegnummer", "R1"));
    }

    private void pruefenPflichtregel(String schwere, Map<String, Object> vorher) {
        Regel regel = new Regel("datum_pflicht", "RECHNUNG", "PFLICHT_WENN",
                Map.of("wenn", Map.of("feld", "belegnummer", "gleich", "R1"), "dann", List.of("datum")),
                schwere, "Belegdatum fehlt.", true);
        fehler = null;
        warnungen = List.of();
        try {
            warnungen = pruefung(regel, (art, alle, felder, werte, id) -> false)
                    .pruefen("RECHNUNG", Map.of("belegnummer", "R1"), vorher, UUID.randomUUID());
        } catch (RegelVerletzt e) {
            fehler = e;
        }
    }

    @Dann("wird der Vorgang mit «{word}» abgelehnt")
    public void abgelehnt(String code) {
        assertThat(fehler).isNotNull();
        assertThat(fehler.code()).isEqualTo(Fehlercode.valueOf(code));
        assertThat(warnungen).isEmpty();
    }

    @Dann("enthält die Antwort die Warnung «{word}»")
    public void warnung(String code) {
        assertThat(fehler).isNull();
        assertThat(warnungen).extracting(RegelPruefung.Warnung::code).contains(code);
    }

    @Dann("wird der Vorgang nicht abgelehnt")
    public void nichtAbgelehnt() {
        assertThat(fehler).isNull();
    }

    @Dann("sind alle vier Regeltypen typisiert")
    public void alleRegeltypenTypisiert() {
        assertThat(typisierteRegeln).hasSize(4);
        assertThat(typisierteRegeln.get(0)).isInstanceOf(Regel.PflichtWenn.class);
        assertThat(typisierteRegeln.get(1)).isInstanceOf(Regel.Eindeutig.class);
        assertThat(typisierteRegeln.get(2)).isInstanceOf(Regel.Folge.class);
        assertThat(typisierteRegeln.get(3)).isInstanceOf(Regel.Erwartet.class);
    }

    @Wenn("eine Regel ein nicht vorhandenes Zusatzfeld referenziert")
    public void unbekanntesZusatzfeld() {
        Regel regel = new Regel("feld_unbekannt", "RECHNUNG", "PFLICHT_WENN",
                Map.of("wenn", Map.of("feld", "zusatz.unbekannt", "gleich", "x"), "dann", List.of("datum")),
                "FEHLER", "Unbekanntes Feld.", true);
        Konfiguration.Art art = new Konfiguration.Art("RECHNUNG", "Rechnung", null, List.of(), List.of(), null,
                "NORMAL", new Konfiguration.Aufbewahrung(null, "BELEGDATUM"), false, true);
        befunde = new Konfiguration(1, new Konfiguration.Einstellungen("12-31", null), List.of(art), List.of(),
                List.of(regel)).pruefen();
    }

    @Dann("wird die Test-Konfiguration mit Befund «{word}» abgelehnt")
    public void konfigurationAbgelehnt(String code) {
        assertThat(befunde).extracting(Konfiguration.Befund::code).contains(code);
    }

    private static RegelPruefung pruefung(Regel regel, RegelDatenPort daten) {
        Konfiguration abbild = new Konfiguration(1, new Konfiguration.Einstellungen("12-31", null), List.of(),
                List.of(), List.of(regel));
        KonfigurationVerwaltung verwaltung = new KonfigurationVerwaltung(null, null, null, null, null, null,
                null, null, null, null) {
            @Override
            public Konfiguration aktuell() {
                return abbild;
            }
        };
        return new RegelPruefung(verwaltung, daten, null);
    }

    @Angenommen("im Mandanten «{word}» gilt die Regel «{word}» für die Art «{word}» vom Typ «{word}» mit Schwere «{word}» und den Parametern {string}")
    public void regelEinrichten(String mandant, String code, String art, String typ, String schwere, String parameter)
            throws SQLException {
        // legt bei Bedarf die erste Konfigurationsversion an (KonfigurationVerwaltung.basisSicherstellen)
        Response stand = given().auth().oauth2(Testanmeldung.token("inhaber-" + mandant))
                .get("/api/v1/dokumentarten");
        assertThat(stand.statusCode()).as(stand.asString()).isEqualTo(200);
        eingerichtet.add(new Eingerichtet(mandant, code));
        try (Connection verbindung = datenbank.get().getConnection()) {
            try (PreparedStatement einfuegen = verbindung.prepareStatement("insert into "
                    + new MandantCode(mandant).schema() + ".regel (code, art, typ, parameter, schwere, meldung, aktiv) "
                    + "values (?, ?, ?, cast(? as jsonb), ?, 'Testregel verletzt.', true)")) {
                einfuegen.setString(1, code);
                einfuegen.setString(2, art);
                einfuegen.setString(3, typ);
                einfuegen.setString(4, parameter);
                einfuegen.setString(5, schwere);
                einfuegen.executeUpdate();
            }
            neueVersion(verbindung, mandant);
        }
    }

    @Dann("enthält die aktuelle Konfiguration von «{word}» die Regeln «{}»")
    public void regelnInKonfiguration(String mandant, String codes) throws SQLException {
        assertThat(regelnDerAktuellenVersion(mandant)).containsAll(List.of(codes.split(",\\s*")));
    }

    @Dann("enthält die aktuelle Konfiguration von «{word}» keine Regeln")
    public void keineRegelnInKonfiguration(String mandant) throws SQLException {
        assertThat(regelnDerAktuellenVersion(mandant)).isEmpty();
    }

    private List<String> regelnDerAktuellenVersion(String mandant) throws SQLException {
        String schema = new MandantCode(mandant).schema();
        List<String> codes = new ArrayList<>();
        try (Connection verbindung = datenbank.get().getConnection();
             Statement abfrage = verbindung.createStatement();
             var ergebnis = abfrage.executeQuery("select r ->> 'code' from " + schema + ".konfiguration_version v, "
                     + "jsonb_array_elements(v.inhalt -> 'regeln') r where v.version = (select max(version) from "
                     + schema + ".konfiguration_version) union all select 'tabelle:' || code from " + schema
                     + ".regel")) {
            while (ergebnis.next()) {
                codes.add(ergebnis.getString(1));
            }
        }
        // Tabelle und aktuelle Version führen dieselben Regeln
        List<String> tabelle = codes.stream().filter(c -> c.startsWith("tabelle:"))
                .map(c -> c.substring("tabelle:".length())).sorted().toList();
        List<String> version = codes.stream().filter(c -> !c.startsWith("tabelle:")).sorted().toList();
        assertThat(version).isEqualTo(tabelle);
        return version;
    }

    // vor den Hooks, die Probe-Arten löschen (fk_regel_art ist RESTRICT)
    @After(order = 20000)
    public void regelnEntfernen() throws SQLException {
        if (eingerichtet.isEmpty()) {
            return;
        }
        try (Connection verbindung = datenbank.get().getConnection()) {
            for (String mandant : eingerichtet.stream().map(Eingerichtet::mandant).distinct().toList()) {
                try (PreparedStatement loeschen = verbindung.prepareStatement("delete from "
                        + new MandantCode(mandant).schema() + ".regel where code = any(?)")) {
                    loeschen.setArray(1, verbindung.createArrayOf("text", eingerichtet.stream()
                            .filter(e -> e.mandant().equals(mandant)).map(Eingerichtet::code).toArray()));
                    loeschen.executeUpdate();
                }
                neueVersion(verbindung, mandant);
            }
        }
        eingerichtet.clear();
    }

    private static void neueVersion(Connection verbindung, String mandant) throws SQLException {
        try (Statement befehl = verbindung.createStatement()) {
            befehl.execute("set search_path to " + new MandantCode(mandant).schema());
            try {
                assertThat(befehl.executeUpdate(VERSION_MIT_REGELN)).isEqualTo(1);
            } finally {
                befehl.execute("reset search_path");
            }
        }
    }
}
