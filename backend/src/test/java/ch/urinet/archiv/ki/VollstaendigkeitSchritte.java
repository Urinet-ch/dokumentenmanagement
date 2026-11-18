package ch.urinet.archiv.ki;

import ch.urinet.archiv.dokument.Testdokumente;
import ch.urinet.archiv.gemeinsam.Testanmeldung;
import ch.urinet.archiv.gemeinsam.domain.Normalisierer;
import ch.urinet.archiv.ki.adapter.in.werkzeug.ArchivWerkzeuge;
import ch.urinet.archiv.ki.application.chat.WerkzeugAuswahl;
import ch.urinet.archiv.ki.application.chat.WerkzeugKontext;
import ch.urinet.archiv.ki.application.chat.WerkzeugKontextRegister;
import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.sicherheit.domain.Benutzer;
import ch.urinet.archiv.sicherheit.domain.Rolle;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.agroal.api.AgroalDataSource;
import io.cucumber.datatable.DataTable;
import io.cucumber.java.After;
import io.cucumber.java.de.Angenommen;
import io.cucumber.java.de.Dann;
import io.cucumber.java.de.Wenn;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Schritte zu Spec 02, Aufgabe 7.1c (Req 3.6, 4.2, 5.2): synthetische Dokumente per JDBC, das Werkzeug
 * {@code vollstaendigkeit} direkt über CDI, jeder Aufruf in einem eigenen Thread ohne Anmeldung (wie bei den anderen
 * Werkzeug-Szenarien). Rolle und Mandant folgen aus dem Namen des Testbenutzers ({@code <rolle>-<mandant>}). Die Daten
 * liegen im Jahr 2003, damit sie nicht mit anderen Szenarien zusammenfallen und die Monate sicher abgeschlossen sind.
 */
public class VollstaendigkeitSchritte {

    private record Ort(String mandant, UUID id) {
    }

    private record Kontakt(String mandant, String name, UUID id) {
    }

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String ABANINJA = "Lieferantenbelege ohne Archivdokument: nur mit AbaNinja";

    @Inject
    Instance<AgroalDataSource> datenbank;

    @Inject
    ArchivWerkzeuge werkzeuge;

    @Inject
    WerkzeugAuswahl auswahl;

    @Inject
    WerkzeugKontextRegister register;

    private final String lauf = "luecken-" + UUID.randomUUID();
    private final List<Ort> dokumente = new ArrayList<>();
    private final Map<String, Kontakt> kontakte = new HashMap<>();
    private final Set<String> laeufe = new HashSet<>();
    /** {@code mandant:art} → von diesem Szenario angelegt. */
    private final Map<String, Boolean> angelegteArten = new HashMap<>();
    private final Map<String, String> antworten = new LinkedHashMap<>();
    private JsonNode antwort;
    private String antwortText;

    @After
    public void aufraeumen() throws SQLException {
        laeufe.forEach(register::entfernen);
        laeufe.clear();
        Map<String, List<UUID>> jeMandant = new HashMap<>();
        dokumente.forEach(o -> jeMandant.computeIfAbsent(o.mandant(), m -> new ArrayList<>()).add(o.id()));
        for (Map.Entry<String, List<UUID>> eintrag : jeMandant.entrySet()) {
            Testdokumente.loeschen(datenbank.get(), eintrag.getKey(), eintrag.getValue());
        }
        try (Connection verbindung = datenbank.get().getConnection()) {
            for (Kontakt kontakt : kontakte.values()) {
                try (PreparedStatement loeschen = verbindung.prepareStatement(
                        "delete from " + new MandantCode(kontakt.mandant()).schema() + ".kontakt where id = ?")) {
                    loeschen.setObject(1, kontakt.id());
                    loeschen.executeUpdate();
                }
            }
        }
        try (Connection verbindung = datenbank.get().getConnection()) {
            for (Map.Entry<String, Boolean> art : angelegteArten.entrySet()) {
                if (art.getValue()) {
                    String[] teile = art.getKey().split(":", 2);
                    try (PreparedStatement loeschen = verbindung.prepareStatement("delete from "
                            + new MandantCode(teile[0]).schema() + ".dokumentart where code = ?")) {
                        loeschen.setString(1, teile[1]);
                        loeschen.executeUpdate();
                    }
                }
            }
        }
        angelegteArten.clear();
        dokumente.clear();
        kontakte.clear();
    }

    // --- Daten ------------------------------------------------------------------------------------------------------

    @Angenommen("im Mandanten «{word}» gibt es für die Lückenprüfung die Kontakte «{}» und «{}»")
    public void kontakteAnlegen(String mandant, String erster, String zweiter) throws SQLException {
        try (Connection verbindung = datenbank.get().getConnection()) {
            for (String name : List.of(erster, zweiter)) {
                UUID id = UUID.randomUUID();
                try (PreparedStatement befehl = verbindung.prepareStatement("insert into "
                        + new MandantCode(mandant).schema() + ".kontakt (id, typ, name, name_normalisiert, quelle) "
                        + "values (?, 'ORGANISATION', ?, ?, 'TEST')")) {
                    befehl.setObject(1, id);
                    befehl.setString(2, name);
                    befehl.setString(3, Normalisierer.name(name));
                    befehl.executeUpdate();
                }
                kontakte.put(mandant + ":" + name, new Kontakt(mandant, name, id));
            }
        }
    }

    @Angenommen("im Mandanten «{word}» sind die Seed-Regeln für den Lückenbericht eingerichtet")
    public void lueckenRegeln(String mandant) throws SQLException {
        String schema = new MandantCode(mandant).schema();
        try (Connection verbindung = datenbank.get().getConnection(); Statement befehl = verbindung.createStatement()) {
            befehl.execute("""
                    insert into %s.regel (code, art, typ, parameter, schwere, meldung, aktiv) values
                        ('ausgangsrechnung_folge', 'AUSGANGSRECHNUNG', 'FOLGE',
                            '{"muster":true,"zeitraum":"GESCHAEFTSJAHR","beginn":1}',
                            'WARNUNG', 'Die Ausgangsrechnungsnummernfolge weist eine Lücke auf.', true),
                        ('lohnabrechnung_erwartet', 'LOHNABRECHNUNG', 'ERWARTET',
                            '{"je":"MONAT","datum":"BELEGDATUM","nurMonat":"zusatz.periode"}',
                            'WARNUNG', 'Für einen Monat fehlt eine bestätigte Lohnabrechnung.', true),
                        ('bankbeleg_erwartet', 'BANK', 'ERWARTET',
                            '{"je":"MONAT","datum":"zusatz.periode_bis"}',
                            'WARNUNG', 'Für einen Monat fehlt ein bestätigter Bankbeleg.', true)
                    on conflict (code) do nothing
                    """.formatted(schema));
            befehl.execute("""
                    with regeln as (
                        select coalesce(jsonb_agg(jsonb_build_object(
                            'code', code, 'art', art, 'typ', typ, 'parameter', parameter,
                            'schwere', schwere, 'meldung', meldung, 'aktiv', aktiv)
                            order by code collate "C"), '[]'::jsonb) as inhalt
                        from %1$s.regel
                    ),
                    aktuell as (
                        select version, inhalt from %1$s.konfiguration_version
                        order by version desc limit 1
                    ),
                    naechste as (
                        select coalesce(max(version), 0) + 1 as version from %1$s.konfiguration_version
                    ),
                    abbild as (
                        select n.version, a.inhalt || jsonb_build_object(
                            'version', n.version, 'regeln', r.inhalt) as inhalt
                        from aktuell a cross join naechste n cross join regeln r
                    )
                    insert into %1$s.konfiguration_version (version, wer, quelle, inhalt, sha256)
                    select version, 'test:seed', 'SEED', inhalt,
                        encode(sha256(convert_to(inhalt::text, 'UTF8')), 'hex')
                    from abbild
                    where (inhalt - 'version') is distinct from (
                        select inhalt - 'version' from aktuell)
                    """.formatted(schema));
        }
    }

    @Angenommen("im Mandanten «{word}» liegen bestätigte Ausgangsrechnungen mit den Belegnummern «{}»")
    public void rechnungen(String mandant, String nummern) throws SQLException {
        try (Connection verbindung = datenbank.get().getConnection()) {
            for (String nummer : liste(nummern)) {
                einfuegen(verbindung, mandant, "AUSGANGSRECHNUNG", "NORMAL", LocalDate.of(2003, 1, 15), nummer, null,
                        Map.of(), "ARCHIVIERT", true);
            }
        }
    }

    @Angenommen("im Mandanten «{word}» liegen bestätigte Lohnabrechnungen der Person «{}» für die Monate «{}»")
    public void lohnabrechnungen(String mandant, String person, String monate) throws SQLException {
        try (Connection verbindung = datenbank.get().getConnection()) {
            for (String monat : liste(monate)) {
                einfuegen(verbindung, mandant, "LOHNABRECHNUNG", "PERSONAL", YearMonth.parse(monat).atEndOfMonth(),
                        null, null, Map.of("periode", monat, "person", person), "ARCHIVIERT", true);
            }
        }
    }

    @Angenommen("im Mandanten «{word}» liegen bestätigte Kontoauszüge des Partners «{}» für die Monate «{}»")
    public void kontoauszuege(String mandant, String partner, String monate) throws SQLException {
        try (Connection verbindung = datenbank.get().getConnection()) {
            for (String monat : liste(monate)) {
                YearMonth ym = YearMonth.parse(monat);
                einfuegen(verbindung, mandant, "BANK", "INTERN", ym.atEndOfMonth().plusDays(3), null,
                        kontakte.get(mandant + ":" + partner), Map.of("periode_von", ym.atDay(1).toString(),
                                "periode_bis", ym.atEndOfMonth().toString()), "ARCHIVIERT", true);
            }
        }
    }

    /** Derselbe Partner (gleiche Kontakt-ID), im Beleg aber unter einer Namensvariante gespeichert. */
    @Angenommen("im Mandanten «{word}» liegen bestätigte Kontoauszüge unter dem Namen «{}» des Partners «{}» für die Monate «{}»")
    public void kontoauszuegeUnterVariante(String mandant, String variante, String partner, String monate)
            throws SQLException {
        Kontakt original = kontakte.get(mandant + ":" + partner);
        try (Connection verbindung = datenbank.get().getConnection()) {
            for (String monat : liste(monate)) {
                YearMonth ym = YearMonth.parse(monat);
                einfuegen(verbindung, mandant, "BANK", "INTERN", ym.atEndOfMonth().plusDays(3), null,
                        new Kontakt(mandant, variante, original.id()), Map.of("periode_von", ym.atDay(1).toString(),
                                "periode_bis", ym.atEndOfMonth().toString()), "ARCHIVIERT", true);
            }
        }
    }

    @Angenommen("im Mandanten «{word}» liegen diese weiteren Dokumente für die Lückenprüfung:")
    public void weitere(String mandant, DataTable tabelle) throws SQLException {
        try (Connection verbindung = datenbank.get().getConnection()) {
            for (Map<String, String> z : tabelle.asMaps()) {
                Map<String, String> zusatz = new LinkedHashMap<>();
                for (String feld : List.of("periode", "person", "periode_bis")) {
                    if (z.get(feld) != null) {
                        zusatz.put(feld, z.get(feld));
                    }
                }
                einfuegen(verbindung, mandant, z.get("art"), z.get("vertraulichkeit"), LocalDate.parse(z.get("datum")),
                        z.get("belegnummer"), z.get("partner") == null ? null : kontakte.get(mandant + ":" + z.get("partner")),
                        zusatz, z.get("status"), "ja".equals(z.get("bestaetigt")));
            }
        }
    }

    private void einfuegen(Connection verbindung, String mandant, String art, String vertraulichkeit, LocalDate datum,
                           String belegnummer, Kontakt partner, Map<String, String> zusatz, String status,
                           boolean bestaetigt) throws SQLException {
        UUID id = UUID.randomUUID();
        String schema = new MandantCode(mandant).schema();
        artSicherstellen(verbindung, mandant, art);
        ObjectNode json = JSON.createObjectNode();
        zusatz.forEach(json::put);
        try (PreparedStatement befehl = verbindung.prepareStatement("insert into " + schema + ".dokument (id, art, "
                + "titel, datum, quelle, partner_id, partner_name, tags, vertraulichkeit, erstellt_von, status, "
                + "bestaetigt, bestaetigt_von, geschaeftsjahr, zusatz) values (?, ?, ?, ?, 'UPLOAD', ?, ?, "
                + "cast('{}' as text[]), ?, ?, ?, ?, ?, ?, cast(? as jsonb))")) {
            befehl.setObject(1, id);
            befehl.setString(2, art);
            befehl.setString(3, "Lückenprüfung " + art);
            befehl.setObject(4, datum);
            befehl.setObject(5, partner == null ? null : partner.id());
            befehl.setString(6, partner == null ? null : partner.name());
            befehl.setString(7, vertraulichkeit);
            befehl.setString(8, Testanmeldung.sub("inhaber-" + mandant));
            befehl.setString(9, status);
            befehl.setObject(10, bestaetigt ? OffsetDateTime.now() : null);
            befehl.setString(11, bestaetigt ? "probe" : null);
            befehl.setInt(12, datum.getYear());
            befehl.setString(13, json.toString());
            befehl.executeUpdate();
        }
        if (belegnummer != null) {
            try (PreparedStatement befehl = verbindung.prepareStatement("insert into " + schema + ".dokument_finanz "
                    + "(dokument_id, belegnummer, richtung, betrag, waehrung) values (?, ?, 'EINNAHME', ?, 'CHF')")) {
                befehl.setObject(1, id);
                befehl.setString(2, belegnummer);
                befehl.setBigDecimal(3, new BigDecimal("100.00"));
                befehl.executeUpdate();
            }
        }
        dokumente.add(new Ort(mandant, id));
    }

    /** Mandanten ohne Seed (vhf-gsk) haben die Art nicht; sie wird für das Szenario angelegt und danach entfernt. */
    private void artSicherstellen(Connection verbindung, String mandant, String art) throws SQLException {
        String schluessel = mandant + ":" + art;
        if (angelegteArten.containsKey(schluessel)) {
            return;
        }
        try (PreparedStatement befehl = verbindung.prepareStatement("insert into " + new MandantCode(mandant).schema()
                + ".dokumentart (code, name, vertraulichkeit, gesetzliche_pflicht, aktiv) values (?, ?, 'NORMAL', false, "
                + "true) on conflict do nothing")) {
            befehl.setString(1, art);
            befehl.setString(2, "Lückenprüfung " + art);
            angelegteArten.put(schluessel, befehl.executeUpdate() > 0);
        }
    }

    // --- Aufrufe ----------------------------------------------------------------------------------------------------

    @Wenn("«{word}» im Chat die Vollständigkeit für das Geschäftsjahr «{int}» abfragt")
    public void abfragen(String benutzer, int jahr) {
        String id = kontext(benutzer);
        lesen(benutzer, imFremdenThread(() -> werkzeuge.vollstaendigkeit(id, jahr)));
    }

    private String kontext(String benutzer) {
        String id = lauf + ":" + benutzer;
        if (!laeufe.contains(id)) {
            int trenner = benutzer.indexOf('-');
            Rolle rolle = Rolle.valueOf(benutzer.substring(0, trenner).toUpperCase(Locale.ROOT));
            register.festhalten(id, WerkzeugKontext.fuer(new MandantCode(benutzer.substring(trenner + 1)),
                    new Benutzer("test-" + benutzer, rolle), null));
            laeufe.add(id);
        }
        return id;
    }

    private static String imFremdenThread(Callable<String> aufruf) {
        ExecutorService thread = Executors.newSingleThreadExecutor();
        try {
            return thread.submit(aufruf).get(60, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException("Werkzeugaufruf gescheitert", e);
        } finally {
            thread.shutdownNow();
        }
    }

    private void lesen(String benutzer, String text) {
        antwortText = text;
        try {
            antwort = JSON.readTree(text);
        } catch (Exception e) {
            throw new IllegalStateException("Antwort ist kein JSON", e);
        }
        antworten.put(benutzer, text);
    }

    // --- Ergebnis ---------------------------------------------------------------------------------------------------

    private JsonNode reihe(String art, String gruppe) {
        for (JsonNode kategorie : antwort.path("kategorien")) {
            if (art.equals(kategorie.path("art").asText())) {
                for (JsonNode reihe : kategorie.path("reihen")) {
                    if (gruppe == null ? !reihe.has("gruppe") : gruppe.equals(reihe.path("gruppe").asText(null))) {
                        return reihe;
                    }
                }
            }
        }
        throw new AssertionError("keine Reihe " + art + "/" + gruppe + " in " + antwortText);
    }

    private static List<String> fehlend(JsonNode reihe) {
        List<String> werte = new ArrayList<>();
        reihe.path("fehlend").forEach(n -> werte.add(n.asText()));
        return werte;
    }

    @Dann("fehlen bei den Ausgangsrechnungen die Nummern «{}»")
    public void fehlendeNummern(String erwartet) {
        JsonNode reihe = reihe("AUSGANGSRECHNUNG", null);
        assertThat(fehlend(reihe)).as(antwortText).containsExactlyElementsOf(liste(erwartet));
        assertThat(reihe.path("anzahlFehlend").asInt()).isEqualTo(liste(erwartet).size());
    }

    @Dann("fehlen bei den Ausgangsrechnungen keine Nummern, die höchste ist «{}»")
    public void keineFehlendenNummern(String hoechste) {
        JsonNode reihe = reihe("AUSGANGSRECHNUNG", null);
        assertThat(fehlend(reihe)).as(antwortText).isEmpty();
        assertThat(reihe.path("hoechste").asText()).isEqualTo(hoechste);
    }

    @Dann("fehlen bei der Art «{word}» für «{}» die Monate «{}»")
    public void fehlendeMonate(String art, String gruppe, String erwartet) {
        JsonNode reihe = reihe(art, gruppe);
        assertThat(fehlend(reihe)).as(antwortText).containsExactlyElementsOf(liste(erwartet));
        assertThat(reihe.path("anzahlFehlend").asInt()).isEqualTo(liste(erwartet).size());
    }

    @Dann("hat die Art «{word}» für «{}» keine fehlenden Monate")
    public void keineFehlendenMonate(String art, String gruppe) {
        assertThat(fehlend(reihe(art, gruppe))).as(antwortText).isEmpty();
    }

    @Dann("fehlen bei der Art «{word}» die Monate «{}»")
    public void fehlendeMonateOhneGruppe(String art, String erwartet) {
        JsonNode reihe = reihe(art, null);
        assertThat(fehlend(reihe)).as(antwortText).containsExactlyElementsOf(liste(erwartet));
        assertThat(reihe.path("anzahlFehlend").asInt()).isEqualTo(liste(erwartet).size());
    }

    @Dann("hat die Art «{word}» keine fehlenden Monate")
    public void keineFehlendenMonateOhneGruppe(String art) {
        for (JsonNode kategorie : antwort.path("kategorien")) {
            if (art.equals(kategorie.path("art").asText())) {
                assertThat(kategorie.path("reihen")).as(antwortText).isEmpty();
                return;
            }
        }
        throw new AssertionError("keine Kategorie " + art + " in " + antwortText);
    }

    @Dann("enthält der Bericht keine Personennamen")
    public void keinePersonennamen() {
        assertThat(antwortText).doesNotContain("Anna Weidmatt", "Clara Moosbrugger", "Beat Rinderli");
    }

    @Dann("hat die Art «{word}» keine Reihe für «{}»")
    public void keineReihe(String art, String gruppe) {
        for (JsonNode kategorie : antwort.path("kategorien")) {
            if (art.equals(kategorie.path("art").asText())) {
                for (JsonNode reihe : kategorie.path("reihen")) {
                    assertThat(reihe.path("gruppe").asText()).as(antwortText).isNotEqualTo(gruppe);
                }
            }
        }
    }

    @Dann("beginnt die Reihe der Art «{word}» für «{}» mit dem Monat «{}»")
    public void beginntMit(String art, String gruppe, String monat) {
        assertThat(reihe(art, gruppe).path("ab").asText()).as(antwortText).isEqualTo(monat);
    }

    @Dann("beginnt die Reihe der Art «{word}» mit dem Monat «{}»")
    public void beginntMitOhneGruppe(String art, String monat) {
        assertThat(reihe(art, null).path("ab").asText()).as(antwortText).isEqualTo(monat);
    }

    @Dann("nennt die Antwort die ausdrückliche Lücke «Lieferantenbelege ohne Archivdokument: nur mit AbaNinja»")
    public void abaNinja() {
        assertThat(antwort.path("nichtGeprueft")).as(antwortText).hasSize(1);
        assertThat(antwort.path("nichtGeprueft").get(0).asText()).isEqualTo(ABANINJA);
        assertThat(antwort.path("datenstand").path("zeitpunkt").asText()).matches("\\d{2}\\.\\d{2}\\.\\d{4} \\d{2}:\\d{2}");
        assertThat(antwort.path("geschaeftsjahr").asInt()).isEqualTo(2003);
    }

    @Dann("liefern «{word}», «{word}» und «{word}» dieselben Lücken")
    public void dieselben(String erster, String zweiter, String dritter) {
        for (String benutzer : List.of(erster, zweiter, dritter)) {
            String id = kontext(benutzer);
            lesen(benutzer, imFremdenThread(() -> werkzeuge.vollstaendigkeit(id, 2003)));
        }
        assertThat(ohneZeitpunkt(antworten.get(zweiter))).as(zweiter).isEqualTo(ohneZeitpunkt(antworten.get(erster)));
        assertThat(ohneZeitpunkt(antworten.get(dritter))).as(dritter).isEqualTo(ohneZeitpunkt(antworten.get(erster)));
        assertThat(antworten.get(erster)).doesNotContain("\"fehler\"");
    }

    private static String ohneZeitpunkt(String text) {
        return text.replaceAll("\"zeitpunkt\":\"[^\"]*\"", "");
    }

    @Dann("nennt der Datenstand die Zahl der bestätigten Belege der Arten «{}» im Mandanten «{word}»")
    public void datenstand(String arten, String mandant) throws SQLException {
        try (Connection verbindung = datenbank.get().getConnection();
             PreparedStatement abfrage = verbindung.prepareStatement("select count(*) from "
                     + new MandantCode(mandant).schema() + ".dokument where status = 'ARCHIVIERT' "
                     + "and bestaetigt is not null and art = any (?)")) {
            abfrage.setArray(1, verbindung.createArrayOf("text", liste(arten).toArray()));
            try (ResultSet z = abfrage.executeQuery()) {
                z.next();
                assertThat(antwort.path("datenstand").path("anzahl").asLong()).as(antwortText).isEqualTo(z.getLong(1));
            }
        }
    }

    @Dann("hat die Antwort keine Kategorien und den Hinweis «{}»")
    public void ohneKategorien(String hinweis) {
        assertThat(antwort.path("kategorien")).as(antwortText).isEmpty();
        assertThat(antwort.path("hinweis").asText()).isEqualTo(hinweis);
    }

    @Dann("ist die Antwort der Fehler «{}»")
    public void fehler(String meldung) {
        assertThat(antwortText).isEqualTo("{\"fehler\":\"" + meldung + "\"}");
    }

    @Dann("wird «{word}» das Werkzeug vollstaendigkeit angeboten")
    public void angeboten(String benutzer) {
        assertThat(angebot(benutzer)).as(benutzer).contains("vollstaendigkeit");
    }

    @Dann("wird «{word}» das Werkzeug vollstaendigkeit nicht angeboten")
    public void nichtAngeboten(String benutzer) {
        assertThat(angebot(benutzer)).as(benutzer).doesNotContain("vollstaendigkeit");
    }

    private List<String> angebot(String benutzer) {
        int trenner = benutzer.indexOf('-');
        WerkzeugKontext kontext = WerkzeugKontext.fuer(new MandantCode(benutzer.substring(trenner + 1)),
                new Benutzer("test-" + benutzer, Rolle.valueOf(benutzer.substring(0, trenner)
                        .toUpperCase(Locale.ROOT))), null);
        List<String> namen = auswahl.angeboten(kontext);
        assertThat(namen).as(benutzer).contains("dokumente_suchen", "dokument_zeigen", "fristen");
        return namen;
    }

    private static List<String> liste(String text) {
        return Arrays.stream(text.split(",")).map(String::strip).filter(t -> !t.isEmpty()).toList();
    }
}
