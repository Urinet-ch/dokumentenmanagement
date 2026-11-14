package ch.urinet.archiv.kontakt;

import ch.urinet.archiv.dokument.Testdokumente;
import ch.urinet.archiv.gemeinsam.LogMitschnitt;
import ch.urinet.archiv.gemeinsam.Testanmeldung;
import ch.urinet.archiv.gemeinsam.domain.Normalisierer;
import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.sicherheit.ProblemDetails;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agroal.api.AgroalDataSource;
import io.cucumber.datatable.DataTable;
import io.cucumber.java.After;
import io.cucumber.java.de.Angenommen;
import io.cucumber.java.de.Dann;
import io.cucumber.java.de.Wenn;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Schritte zu «Kontakte ansehen und pflegen» (Spec 01, Req 17; Aufgabe 4.11): Liste, Detail, Verweise, Zusammenführen,
 * inaktiv und wieder aktiv über {@code /api/v1/kontakte} gegen das echte Backend; Kontakte, Dokumente, Verkaufschancen
 * und Korrekturbeispiele legen die Schritte direkt in der Datenbank an. Kontakte und Dokumente heissen im Szenario
 * nach ihrer Kennung (z. B. {@code K1}, {@code D1}). Alle Namen und Werte sind synthetisch.
 */
public class KontaktePflegenSchritte {

    private static final String KONTAKTE = "/api/v1/kontakte";
    private static final String GRUND = "Doppelt erfasst (Probe)";
    private static final String AUSLOESER = "kontakt_pflegen_probe_fehler";

    @Inject
    Instance<AgroalDataSource> datenbank;

    private record Ort(String mandant, UUID id) {
    }

    private record Ergebnis(int status, String fehlercode) {
    }

    private final Map<String, Ort> kontakte = new LinkedHashMap<>();
    private final Map<String, Ort> dokumente = new LinkedHashMap<>();
    private final Map<String, Integer> gemerkteVersionen = new HashMap<>();
    private final List<String> werte = new ArrayList<>();
    private final List<String> ausloeser = new ArrayList<>();
    private final List<String> eingaben = new ArrayList<>();
    private LogMitschnitt mitschnitt;
    private Map<String, Object> stand;
    private Response antwort;
    private Duration dauer;
    private List<Ergebnis> gleichzeitig;
    private CompletableFuture<Void> sperre;
    private CountDownLatch freigeben;

    @Angenommen("im Mandanten «{word}» gibt es diese gepflegten Kontakte:")
    public void kontakteAnlegen(String mandant, DataTable tabelle) throws SQLException {
        String sql = "insert into " + schema(mandant) + ".kontakt (id, crm_id, typ, name, vorname, strasse, plz, ort, "
                + "email, telefon, name_normalisiert, quelle, nicht_im_export, aktiv, inaktiv_seit) "
                + "values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, case when ? then null else now() end)";
        try (Connection verbindung = datenbank.get().getConnection();
             PreparedStatement einfuegen = verbindung.prepareStatement(sql)) {
            for (Map<String, String> z : tabelle.asMaps()) {
                UUID id = UUID.randomUUID();
                String typ = oder(z.get("typ"), "ORGANISATION");
                boolean aktiv = !"false".equals(z.get("aktiv"));
                einfuegen.setObject(1, id);
                einfuegen.setString(2, z.get("crm_id"));
                einfuegen.setString(3, typ);
                einfuegen.setString(4, z.get("name"));
                einfuegen.setString(5, z.get("vorname"));
                einfuegen.setString(6, z.get("strasse"));
                einfuegen.setString(7, z.get("plz") == null ? null : Normalisierer.plz(z.get("plz")));
                einfuegen.setString(8, z.get("ort"));
                einfuegen.setString(9, z.get("email") == null ? null : Normalisierer.email(z.get("email")));
                einfuegen.setString(10, z.get("telefon"));
                einfuegen.setString(11, "PERSON".equals(typ) ? Normalisierer.personName(z.get("vorname"), z.get("name"))
                        : Normalisierer.name(z.get("name")));
                einfuegen.setString(12, oder(z.get("quelle"), "MANUELL"));
                einfuegen.setBoolean(13, Boolean.parseBoolean(z.get("nicht_im_export")));
                einfuegen.setBoolean(14, aktiv);
                einfuegen.setBoolean(15, aktiv);
                einfuegen.executeUpdate();
                kontakte.put(z.get("kontakt"), new Ort(mandant, id));
                for (String feld : List.of("name", "vorname", "strasse", "ort", "email", "telefon")) {
                    if (z.get(feld) != null) {
                        werte.add(z.get(feld));
                    }
                }
            }
        }
    }

    /**
     * Bestätigte Testdokumente mit Partner (Spalte {@code partner}, Kennung oder leer) und Partner des KI-Vorschlags
     * ({@code ki_partner}); {@code person} ist ein Testbenutzer ({@code person_benutzer}).
     */
    @Angenommen("im Mandanten «{word}» gibt es diese Dokumente zu Kontakten:")
    public void dokumenteAnlegen(String mandant, DataTable tabelle) throws SQLException {
        for (Map<String, String> z : tabelle.asMaps()) {
            String person = z.get("person") == null ? null : Testanmeldung.sub(z.get("person"));
            UUID id = Testdokumente.anlegen(datenbank.get(), new Testdokumente.Angaben(mandant, null,
                    oder(z.get("vertraulichkeit"), "NORMAL"), true, "probe", person,
                    oder(z.get("status"), "ARCHIVIERT")));
            dokumente.put(z.get("dokument"), new Ort(mandant, id));
            Ort partner = z.get("partner") == null ? null : kontakt(z.get("partner"));
            Ort kiPartner = z.get("ki_partner") == null ? null : kontakt(z.get("ki_partner"));
            ausfuehren("update " + schema(mandant) + ".dokument set partner_id = ?, partner_name = ?, ki_partner_id = ?, "
                            + "datum = ?, titel = ? where id = ?", partner == null ? null : partner.id(),
                    z.get("partner") == null ? null : "Partner " + z.get("partner"),
                    kiPartner == null ? null : kiPartner.id(),
                    z.get("datum") == null ? null : LocalDate.parse(z.get("datum")), "Probe " + z.get("dokument"), id);
        }
    }

    @Angenommen("am Kontakt {word} hängen {int} Verkaufschancen und {int} Korrekturbeispiele zum Dokument {word}")
    public void verweiseAnlegen(String kennung, int verkaufschancen, int beispiele, String dokument)
            throws SQLException {
        Ort k = kontakt(kennung);
        for (int i = 0; i < verkaufschancen; i++) {
            ausfuehren("insert into " + schema(k.mandant()) + ".verkaufschance (id, kontakt_id, titel) values (?, ?, ?)",
                    UUID.randomUUID(), k.id(), "Probe-Chance " + i);
        }
        for (int i = 0; i < beispiele; i++) {
            ausfuehren("insert into " + schema(k.mandant()) + ".ki_beispiel (partner_id, art, korrektur, dokument_id) "
                    + "values (?, 'KORRESPONDENZ', '{}'::jsonb, ?)", k.id(), dokument(dokument).id());
        }
    }

    @Angenommen("der Kontakt {word} wurde schon in den Kontakt {word} zusammengeführt")
    public void schonZusammengefuehrt(String quelle, String ziel) throws SQLException {
        ausfuehren("update " + schema(kontakt(quelle).mandant()) + ".kontakt set aktiv = false, inaktiv_seit = now(), "
                + "zusammengefuehrt_in = ? where id = ?", kontakt(ziel).id(), kontakt(quelle).id());
    }

    /** Für Kontakte anderer Schritte (nach Name): wie {@code POST /kontakte/{id}/inaktiv}, direkt in der Datenbank. */
    @Angenommen("der Kontakt «{}» im Mandanten «{word}» ist inaktiv")
    public void inaktivNachName(String name, String mandant) throws SQLException {
        try (Connection verbindung = datenbank.get().getConnection(); PreparedStatement aendern =
                verbindung.prepareStatement("update " + schema(mandant) + ".kontakt set aktiv = false, "
                        + "inaktiv_seit = now() where name = ? and aktiv")) {
            aendern.setString(1, name);
            assertThat(aendern.executeUpdate()).as("Kontakt %s", name).isPositive();
        }
    }

    @Angenommen("das Log der Kontaktpflege wird mitgeschnitten")
    public void mitschneiden() {
        mitschnitt = LogMitschnitt.starten();
    }

    @Dann("enthält das Log weder Kontaktdaten noch Eingabewerte")
    public void logPruefen() {
        String log = mitschnitt.volltext();
        for (String wert : werte) {
            assertThat(log).as("Log").doesNotContain(wert);
        }
        for (String wert : eingaben) {
            assertThat(log).as("Log").doesNotContain(wert);
        }
    }

    @Dann("nennt das Log die ID von {word}")
    public void logMitId(String kennung) {
        assertThat(mitschnitt.volltext()).contains(kontakt(kennung).id().toString());
    }

    /** Viele archivierte Dokumente für die Laufzeit des Zusammenführens (synthetisch, ein INSERT). */
    @Angenommen("am Kontakt {word} hängen {int} weitere archivierte Dokumente")
    public void vieleDokumente(String kennung, int anzahl) throws SQLException {
        Ort k = kontakt(kennung);
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < anzahl; i++) {
            ids.add(UUID.randomUUID());
        }
        try (Connection verbindung = datenbank.get().getConnection(); PreparedStatement einfuegen =
                verbindung.prepareStatement("insert into " + schema(k.mandant()) + ".dokument (id, titel, quelle, "
                        + "vertraulichkeit, erstellt_von, status, bestaetigt, bestaetigt_von, partner_id, partner_name) "
                        + "select i, 'Probe Masse', 'UPLOAD', 'NORMAL', 'probe', 'ARCHIVIERT', now(), 'probe', ?, "
                        + "'Partner Masse' from unnest(?::uuid[]) i")) {
            einfuegen.setObject(1, k.id());
            einfuegen.setArray(2, verbindung.createArrayOf("uuid", ids.toArray()));
            einfuegen.executeUpdate();
        }
        for (int i = 0; i < ids.size(); i++) {
            dokumente.put("M" + i, new Ort(k.mandant(), ids.get(i)));
        }
    }

    @Dann("dauerte das Zusammenführen höchstens {int} Sekunden")
    public void laufzeit(int sekunden) {
        status(200);
        // nur die Dauer, keine Daten (Nachweis für design.md, Aufgabe 4.11)
        System.out.printf("Zusammenführen: %d Dokumente in %d ms%n", antwort.<Integer>path("dokumente"),
                dauer.toMillis());
        assertThat(dauer).isLessThan(Duration.ofSeconds(sekunden));
    }

    @Angenommen("die Version des Dokuments {word} ist gemerkt")
    public void versionMerken(String dokument) throws SQLException {
        gemerkteVersionen.put(dokument, (Integer) dokumentWert(dokument, "version"));
    }

    @Angenommen("der Stand der Kontakte und Dokumente ist gemerkt")
    public void standMerken() throws SQLException {
        stand = stand();
    }

    /** Ein Trigger lässt den Eintrag ohne Dokument scheitern, also nach dem Umhängen und den Einträgen je Dokument. */
    @Angenommen("das Zugriffsprotokoll von «{word}» lehnt den Eintrag zum Zusammenführen ab")
    public void protokollScheitert(String mandant) throws SQLException {
        String schema = schema(mandant);
        ausfuehren("create or replace function " + schema + "." + AUSLOESER + "() returns trigger language plpgsql as "
                + "$$ begin raise exception 'Probe: Protokoll nicht schreibbar'; end $$");
        ausfuehren("create trigger " + AUSLOESER + " before insert on " + schema + ".zugriff_protokoll for each row "
                + "when (new.aktion = 'KONTAKT_ZUSAMMENFUEHREN' and new.dokument_id is null) "
                + "execute function " + schema + "." + AUSLOESER + "()");
        ausloeser.add(mandant);
    }

    @Angenommen("eine andere Transaktion den Kontakt {word} sperrt, wie es eine laufende Übernahme tut")
    public void sperren(String kennung) throws Exception {
        Ort k = kontakt(kennung);
        AgroalDataSource quelle = datenbank.get();
        CountDownLatch gesperrt = new CountDownLatch(1);
        freigeben = new CountDownLatch(1);
        sperre = CompletableFuture.runAsync(() -> {
            try (Connection verbindung = quelle.getConnection()) {
                verbindung.setAutoCommit(false);
                try (PreparedStatement abfrage = verbindung.prepareStatement(
                        "select 1 from " + schema(k.mandant()) + ".kontakt where id = ? for update")) {
                    abfrage.setObject(1, k.id());
                    abfrage.executeQuery().close();
                    gesperrt.countDown();
                    freigeben.await(15, TimeUnit.SECONDS);
                } finally {
                    verbindung.rollback();
                    verbindung.setAutoCommit(true);
                }
            } catch (SQLException e) {
                throw new IllegalStateException(e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        });
        assertThat(gesperrt.await(10, TimeUnit.SECONDS)).as("Sperre gesetzt").isTrue();
    }

    @Wenn("die andere Transaktion die Sperre des Kontakts freigibt")
    public void freigeben() throws Exception {
        freigeben.countDown();
        sperre.get(15, TimeUnit.SECONDS);
        sperre = null;
    }

    @Wenn("«{word}» die Kontaktliste abfragt")
    public void liste(String benutzer) {
        antwort = anfrage(benutzer).when().get(KONTAKTE + "/liste");
    }

    /** {@code parameter} wie eine Query, z. B. {@code q=probe&status=ALLE}; Werte werden kodiert gesendet. */
    @Wenn("«{word}» die Kontaktliste mit «{}» abfragt")
    public void listeMit(String benutzer, String parameter) {
        RequestSpecification anfrage = anfrage(benutzer);
        for (String paar : parameter.split("&")) {
            int gleich = paar.indexOf('=');
            String wert = paar.substring(gleich + 1).replace("{leer}", "").replace("{tab}", "\t")
                    .replace("{201}", "y".repeat(201)).replace("{200}", "y".repeat(200));
            if (wert.length() >= 3 && !wert.matches("-?\\d+")) {
                eingaben.add(wert);
            }
            anfrage.queryParam(paar.substring(0, gleich), wert);
        }
        antwort = anfrage.when().get(KONTAKTE + "/liste");
    }

    @Wenn("«{word}» den Kontakt {word} ansieht")
    public void ansehen(String benutzer, String kennung) {
        antwort = anfrage(benutzer).when().get(KONTAKTE + "/" + kontakt(kennung).id());
    }

    @Wenn("«{word}» die Verweise auf den Kontakt {word} abfragt")
    public void verweise(String benutzer, String kennung) {
        antwort = anfrage(benutzer).when().get(KONTAKTE + "/" + kontakt(kennung).id() + "/verweise");
    }

    @Wenn("«{word}» in der Partnerauswahl nach «{}» sucht")
    public void partnerauswahl(String benutzer, String text) {
        antwort = anfrage(benutzer).queryParam("q", text).when().get(KONTAKTE);
    }

    @Wenn("«{word}» den Kontakt {word} in den Kontakt {word} zusammenführt")
    public void zusammenfuehren(String benutzer, String quelle, String ziel) throws SQLException {
        zusammenfuehren(benutzer, quelle, "\"" + version(quelle) + "\"",
                Map.of("ziel", kontakt(ziel).id().toString(), "grund", GRUND));
    }

    @Wenn("«{word}» den Kontakt {word} in den Kontakt {word} mit dem Grund «{}» zusammenführt")
    public void zusammenfuehrenMitGrund(String benutzer, String quelle, String ziel, String grund)
            throws SQLException {
        zusammenfuehren(benutzer, quelle, "\"" + version(quelle) + "\"",
                Map.of("ziel", kontakt(ziel).id().toString(), "grund", grund));
    }

    @Wenn("«{word}» den Kontakt {word} in den Kontakt {word} mit If-Match «{}» zusammenführt")
    public void zusammenfuehrenMitIfMatch(String benutzer, String quelle, String ziel, String ifMatch) {
        zusammenfuehren(benutzer, quelle, "{ohne}".equals(ifMatch) ? null : ifMatch,
                Map.of("ziel", kontakt(ziel).id().toString(), "grund", GRUND));
    }

    /** Roher Körper; {@code <K2>} steht für die ID des Kontakts K2, {@code {1001}} für 1001 Zeichen. */
    @Wenn("«{word}» den Kontakt {word} mit diesem Körper zusammenführt:")
    public void zusammenfuehrenRoh(String benutzer, String quelle, String koerper) throws SQLException {
        String text = koerper.replace("{1001}", "x".repeat(1001)).replace("{1000}", "x".repeat(1000));
        for (Map.Entry<String, Ort> k : kontakte.entrySet()) {
            text = text.replace("<" + k.getKey() + ">", k.getValue().id().toString());
        }
        long start = System.nanoTime();
        antwort = anfrage(benutzer).header("If-Match", "\"" + version(quelle) + "\"").body(text).when()
                .post(KONTAKTE + "/" + kontakt(quelle).id() + "/zusammenfuehren");
        dauer = Duration.ofNanos(System.nanoTime() - start);
    }

    @Wenn("«{word}» gleichzeitig {word} in {word} und {word} in {word} zusammenführt")
    public void gegenlaeufig(String benutzer, String a, String b, String c, String d) throws Exception {
        Map<String, Object> eins = Map.of("ziel", kontakt(b).id().toString(), "grund", GRUND);
        Map<String, Object> zwei = Map.of("ziel", kontakt(d).id().toString(), "grund", GRUND);
        String ifMatchA = "\"" + version(a) + "\"";
        String ifMatchC = "\"" + version(c) + "\"";
        String token = Testanmeldung.token(benutzer);
        CountDownLatch start = new CountDownLatch(1);
        CompletableFuture<Ergebnis> erster = CompletableFuture.supplyAsync(() -> senden(start, token, a, ifMatchA, eins));
        CompletableFuture<Ergebnis> zweiter = CompletableFuture.supplyAsync(() -> senden(start, token, c, ifMatchC, zwei));
        start.countDown();
        gleichzeitig = List.of(erster.get(30, TimeUnit.SECONDS), zweiter.get(30, TimeUnit.SECONDS));
    }

    @Wenn("«{word}» den Kontakt {word} als inaktiv markiert")
    public void inaktiv(String benutzer, String kennung) throws SQLException {
        antwort = anfrage(benutzer).header("If-Match", "\"" + version(kennung) + "\"").when()
                .post(KONTAKTE + "/" + kontakt(kennung).id() + "/inaktiv");
    }

    @Wenn("«{word}» den Kontakt {word} mit dem Grund «{}» als inaktiv markiert")
    public void inaktivMitGrund(String benutzer, String kennung, String grund) throws SQLException {
        antwort = anfrage(benutzer).header("If-Match", "\"" + version(kennung) + "\"")
                .body(Map.of("grund", grund.replace("{1001}", "x".repeat(1001)))).when()
                .post(KONTAKTE + "/" + kontakt(kennung).id() + "/inaktiv");
    }

    @Wenn("«{word}» den Kontakt {word} mit If-Match «{}» als inaktiv markiert")
    public void inaktivMitIfMatch(String benutzer, String kennung, String ifMatch) {
        RequestSpecification anfrage = anfrage(benutzer);
        if (!"{ohne}".equals(ifMatch)) {
            anfrage.header("If-Match", ifMatch);
        }
        antwort = anfrage.when().post(KONTAKTE + "/" + kontakt(kennung).id() + "/inaktiv");
    }

    @Wenn("«{word}» den Kontakt {word} mit dem Körper {string} als inaktiv markiert")
    public void inaktivRoh(String benutzer, String kennung, String koerper) throws SQLException {
        antwort = anfrage(benutzer).header("If-Match", "\"" + version(kennung) + "\"").body(koerper).when()
                .post(KONTAKTE + "/" + kontakt(kennung).id() + "/inaktiv");
    }

    @Wenn("«{word}» den Kontakt {word} wieder aktiviert")
    public void aktiv(String benutzer, String kennung) throws SQLException {
        antwort = anfrage(benutzer).header("If-Match", "\"" + version(kennung) + "\"").when()
                .post(KONTAKTE + "/" + kontakt(kennung).id() + "/aktiv");
    }

    @Wenn("«{word}» den Kontakt {word} mit If-Match «{}» wieder aktiviert")
    public void aktivMitIfMatch(String benutzer, String kennung, String ifMatch) {
        RequestSpecification anfrage = anfrage(benutzer);
        if (!"{ohne}".equals(ifMatch)) {
            anfrage.header("If-Match", ifMatch);
        }
        antwort = anfrage.when().post(KONTAKTE + "/" + kontakt(kennung).id() + "/aktiv");
    }

    @Wenn("«{word}» den Kontakt {word} in «{}» umbenennt")
    public void umbenennen(String benutzer, String kennung, String name) throws SQLException {
        werte.add(name);
        antwort = anfrage(benutzer).header("If-Match", "\"" + version(kennung) + "\"").body(Map.of("name", name))
                .when().patch(KONTAKTE + "/" + kontakt(kennung).id());
    }

    @Wenn("«{word}» den Kontakt {word} mit If-Match «{}» in «{}» umbenennt")
    public void umbenennenMitIfMatch(String benutzer, String kennung, String ifMatch, String name) {
        RequestSpecification anfrage = anfrage(benutzer).body(Map.of("name", name));
        if (!"{ohne}".equals(ifMatch)) {
            anfrage.header("If-Match", ifMatch);
        }
        antwort = anfrage.when().patch(KONTAKTE + "/" + kontakt(kennung).id());
    }

    @Wenn("«{word}» die Dokumente des Kontakts {word} abfragt")
    public void dokumenteAbfragen(String benutzer, String kennung) {
        dokumenteSeite(benutzer, 0, 50, kennung);
    }

    @Wenn("«{word}» die Seite {int} mit {int} Dokumenten des Kontakts {word} abfragt")
    public void dokumenteSeite(String benutzer, int seite, int groesse, String kennung) {
        antwort = anfrage(benutzer).queryParam("partner", kontakt(kennung).id().toString())
                .queryParam("sortierung", "DATUM").queryParam("seite", seite).queryParam("groesse", groesse)
                .when().get("/api/v1/dokumente");
    }

    @Wenn("«{word}» das Dokument {word} mit der gemerkten Version ändert")
    public void mitAlterVersionAendern(String benutzer, String dokument) {
        antwort = anfrage(benutzer).header("If-Match", "\"" + gemerkteVersionen.get(dokument) + "\"")
                .body(Map.of("titel", "Neuer Titel", "grund", "Probe")).when()
                .patch("/api/v1/dokumente/" + dokument(dokument).id());
    }

    @Dann("antwortet die Kontaktpflege mit Status {int}")
    public void status(int status) {
        assertThat(antwort.statusCode()).as(antwort.asString()).isEqualTo(status);
    }

    @Dann("antwortet die Kontaktpflege mit Status {int} und Fehlercode «{word}»")
    public void fehler(int status, String code) {
        assertThat(ProblemDetails.pruefen(antwort, status)).isEqualTo(code);
        // Fehler nennen weder Kontaktdaten noch Eingabewerte (Req 16.7)
        werte.forEach(w -> assertThat(antwort.asString()).as("Antwort ohne Kontaktdaten").doesNotContain(w));
        eingaben.forEach(w -> assertThat(antwort.asString()).as("Antwort ohne Eingabewert").doesNotContain(w));
    }

    @Dann("antwortet die Kontaktpflege nach höchstens {int} Sekunden mit Status {int} und Fehlercode «{word}»")
    public void schnellerFehler(int sekunden, int status, String code) {
        fehler(status, code);
        assertThat(dauer).isLessThan(Duration.ofSeconds(sekunden));
    }

    @Dann("trägt die Antwort das ETag «{}»")
    public void etag(String etag) {
        assertThat(antwort.header("ETag")).isEqualTo(etag);
    }

    @Dann("listet die Kontaktliste {int} Kontakte, auf der Seite «{}»")
    public void listePruefen(int gesamt, String kennungen) {
        status(200);
        assertThat(antwort.<Number>path("gesamt").longValue()).isEqualTo(gesamt);
        List<String> ids = antwort.path("eintraege.id");
        assertThat(ids.stream().map(this::kennungZuKontakt).toList()).containsExactlyElementsOf(kennungen(kennungen));
    }

    @Dann("nennt die Kontaktliste Seite {int} und Grösse {int}")
    public void listeSeite(int seite, int groesse) {
        assertThat(List.of(antwort.<Integer>path("seite"), antwort.<Integer>path("groesse")))
                .containsExactly(seite, groesse);
    }

    @Dann("zeigt die Kontaktliste für {word} diese Felder:")
    public void listeFelder(String kennung, Map<String, String> felder) {
        status(200);
        List<Map<String, Object>> eintraege = antwort.path("eintraege");
        Map<String, Object> eintrag = eintraege.stream()
                .filter(e -> kontakt(kennung).id().toString().equals(e.get("id"))).findFirst().orElseThrow();
        assertThat(eintrag.keySet()).containsExactlyInAnyOrder("id", "typ", "name", "vorname", "plz", "ort", "email",
                "quelle", "aktiv", "nichtImExport");
        felder.forEach((feld, wert) -> assertThat(String.valueOf(eintrag.get(feld))).as(feld).isEqualTo(wert(wert)));
    }

    @Dann("zeigt das Detail diese Felder:")
    public void detailFelder(Map<String, String> felder) {
        status(200);
        felder.forEach((feld, wert) -> assertThat(String.valueOf(antwort.<Object>path(feld))).as(feld)
                .isEqualTo(wert(wert)));
    }

    @Dann("nennt das Detail keine Zahl der Dokumente")
    public void ohneDokumentzahl() throws Exception {
        JsonNode detail = new ObjectMapper().readTree(antwort.asString());
        List<String> felder = new ArrayList<>();
        detail.fieldNames().forEachRemaining(felder::add);
        assertThat(felder).noneMatch(f -> f.toLowerCase().contains("dokument"));
    }

    @Dann("zeigt das Detail «zusammengeführt aus» «{}»")
    public void zusammengefuehrtAus(String kennungen) {
        List<String> ids = antwort.path("zusammengefuehrtAus.id");
        assertThat(ids.stream().map(this::kennungZuKontakt).toList()).containsExactlyElementsOf(kennungen(kennungen));
    }

    @Dann("zählen die Verweise {int} Dokumente, {int} Verkaufschancen und {int} Korrekturbeispiele")
    public void verweiseZahlen(int dokumente, int verkaufschancen, int beispiele) {
        status(200);
        assertThat(List.of(antwort.<Integer>path("dokumente"), antwort.<Integer>path("verkaufschancen"),
                antwort.<Integer>path("korrekturbeispiele"))).containsExactly(dokumente, verkaufschancen, beispiele);
    }

    @Dann("meldet das Zusammenführen das Ziel {word} mit {int} Dokumenten, {int} Verkaufschancen und {int} "
            + "Korrekturbeispielen")
    public void zusammengefuehrt(String ziel, int dokumente, int verkaufschancen, int beispiele) {
        status(200);
        assertThat(antwort.<String>path("ziel")).isEqualTo(kontakt(ziel).id().toString());
        assertThat(List.of(antwort.<Integer>path("dokumente"), antwort.<Integer>path("verkaufschancen"),
                antwort.<Integer>path("korrekturbeispiele"))).containsExactly(dokumente, verkaufschancen, beispiele);
    }

    @Dann("enthält die Partnerauswahl {word}, aber nicht {word}")
    public void partnerauswahlPruefen(String mit, String ohne) {
        status(200);
        List<String> ids = antwort.path("id");
        assertThat(ids).contains(kontakt(mit).id().toString()).doesNotContain(kontakt(ohne).id().toString());
    }

    @Dann("enthält die Partnerauswahl keinen der Kontakte «{}»")
    public void partnerauswahlOhne(String kennungen) {
        status(200);
        List<String> ids = antwort.path("id");
        for (String k : kennungen(kennungen)) {
            assertThat(ids).doesNotContain(kontakt(k).id().toString());
        }
    }

    @Dann("zeigen die Dokumente des Kontakts insgesamt {int}, auf der Seite «{}»")
    public void dokumentePruefen(int gesamt, String kennungen) {
        status(200);
        assertThat(antwort.<Number>path("gesamt").longValue()).isEqualTo(gesamt);
        List<String> ids = antwort.path("treffer.id");
        assertThat(ids.stream().map(this::kennungZuDokument).toList()).containsExactlyElementsOf(kennungen(kennungen));
    }

    @Dann("haben die Dokumente «{}» den Partner {word}")
    public void partnerPruefen(String kennungen, String kennung) throws SQLException {
        for (String d : kennungen(kennungen)) {
            assertThat(dokumentWert(d, "partner_id")).as(d).isEqualTo(kontakt(kennung).id());
        }
    }

    @Dann("haben die Dokumente «{}» den Partner des KI-Vorschlags {word}")
    public void kiPartnerPruefen(String kennungen, String kennung) throws SQLException {
        for (String d : kennungen(kennungen)) {
            assertThat(dokumentWert(d, "ki_partner_id")).as(d).isEqualTo(kontakt(kennung).id());
        }
    }

    @Dann("hängen am Kontakt {word} {int} Verkaufschancen und {int} Korrekturbeispiele")
    public void verweisePruefen(String kennung, int verkaufschancen, int beispiele) throws SQLException {
        Ort k = kontakt(kennung);
        assertThat(zahl("select count(*) from " + schema(k.mandant()) + ".verkaufschance where kontakt_id = ?", k.id()))
                .isEqualTo(verkaufschancen);
        assertThat(zahl("select count(*) from " + schema(k.mandant()) + ".ki_beispiel where partner_id = ?", k.id()))
                .isEqualTo(beispiele);
    }

    @Dann("hat das Dokument {word} die Version {int}, den Partnernamen «{}» und {int} Fassungen mit dem Grund «{}»")
    public void dokumentPruefen(String dokument, int version, String partnerName, int fassungen, String grund)
            throws SQLException {
        Ort d = dokument(dokument);
        assertThat(dokumentWert(dokument, "version")).isEqualTo(version);
        assertThat(dokumentWert(dokument, "partner_name")).isEqualTo(partnerName);
        assertThat(zahl("select count(*) from " + schema(d.mandant()) + ".dokument_version where dokument_id = ? "
                + "and grund = ? and geaendert_von = ?", d.id(), grund, Testanmeldung.sub("inhaber-urinet")))
                .isEqualTo(fassungen);
    }

    @Dann("gibt es für das Dokument {word} keinen Auftrag «EMBEDDING»")
    public void keinEmbedding(String dokument) throws SQLException {
        assertThat(zahl("select count(*) from public.auftrag where typ = 'EMBEDDING' and nutzlast::text like ?",
                "%" + dokument(dokument).id() + "%")).isZero();
    }

    @Dann("ist der Kontakt {word} inaktiv und in {word} zusammengeführt, mit Version {int}")
    public void zusammengefuehrtPruefen(String kennung, String ziel, int version) throws SQLException {
        assertThat(kontaktStand(kennung)).containsExactly(false, true, kontakt(ziel).id(), version);
    }

    @Dann("ist der Kontakt {word} inaktiv ohne Zusammenführung, mit Version {int}")
    public void inaktivPruefen(String kennung, int version) throws SQLException {
        assertThat(kontaktStand(kennung)).containsExactly(false, true, null, version);
    }

    @Dann("ist der Kontakt {word} aktiv mit Version {int}")
    public void aktivPruefen(String kennung, int version) throws SQLException {
        assertThat(kontaktStand(kennung)).containsExactly(true, false, null, version);
    }

    @Dann("hat der Kontakt {word} den Namen «{}» und die Kontakt-ID «{}»")
    public void stammdatenPruefen(String kennung, String name, String crmId) throws SQLException {
        Ort k = kontakt(kennung);
        try (Connection verbindung = datenbank.get().getConnection(); PreparedStatement abfrage =
                verbindung.prepareStatement("select name, crm_id from " + schema(k.mandant()) + ".kontakt where id = ?")) {
            abfrage.setObject(1, k.id());
            try (ResultSet z = abfrage.executeQuery()) {
                assertThat(z.next()).isTrue();
                assertThat(Arrays.asList(z.getString(1), z.getString(2)))
                        .containsExactly(name, "null".equals(crmId) ? null : crmId);
            }
        }
    }

    @Dann("sind Kontakte und Dokumente unverändert")
    public void unveraendert() throws SQLException {
        assertThat(stand()).isEqualTo(stand);
    }

    @Dann("hält das Zugriffsprotokoll von «{word}» das Zusammenführen von {word} in {word} mit {int} Dokumenten, "
            + "{int} Verkaufschancen und {int} Korrekturbeispielen fest, dazu je einen Eintrag für «{}»")
    public void protokollZusammenfuehren(String mandant, String quelle, String ziel, int dokumente, int chancen,
                                         int beispiele, String kennungen) throws Exception {
        List<Map<String, Object>> eintraege = protokoll(mandant, "KONTAKT_ZUSAMMENFUEHREN", kontakt(quelle).id());
        ObjectMapper json = new ObjectMapper();
        List<Map<String, Object>> lauf = eintraege.stream().filter(e -> e.get("dokument") == null).toList();
        assertThat(lauf).hasSize(1);
        assertThat(lauf.getFirst().get("benutzer")).isEqualTo(Testanmeldung.sub("inhaber-urinet"));
        assertThat(lauf.getFirst().get("rolle")).isEqualTo("INHABER");
        JsonNode details = json.readTree((String) lauf.getFirst().get("details"));
        assertThat(details.path("quelle").asText()).isEqualTo(kontakt(quelle).id().toString());
        assertThat(details.path("ziel").asText()).isEqualTo(kontakt(ziel).id().toString());
        assertThat(details.path("grund").asText()).isEqualTo(GRUND);
        assertThat(List.of(details.path("dokumente").asInt(), details.path("verkaufschancen").asInt(),
                details.path("korrekturbeispiele").asInt())).containsExactly(dokumente, chancen, beispiele);
        List<Map<String, Object>> jeDokument = eintraege.stream().filter(e -> e.get("dokument") != null).toList();
        assertThat(jeDokument.stream().map(e -> kennungZuDokument(e.get("dokument").toString())).sorted().toList())
                .containsExactlyElementsOf(kennungen(kennungen).stream().sorted().toList());
        for (Map<String, Object> e : jeDokument) {
            JsonNode d = json.readTree((String) e.get("details"));
            List<String> namen = new ArrayList<>();
            d.fieldNames().forEachRemaining(namen::add);
            assertThat(namen).containsExactlyInAnyOrder("quelle", "ziel");
        }
    }

    @Dann("hält das Zugriffsprotokoll von «{word}» «{word}» für den Kontakt {word} mit dem Grund «{}» fest")
    public void protokollMarkiert(String mandant, String aktion, String kennung, String grund) throws Exception {
        List<Map<String, Object>> eintraege = protokoll(mandant, aktion, kontakt(kennung).id());
        assertThat(eintraege).hasSize(1);
        assertThat(eintraege.getFirst().get("dokument")).isNull();
        JsonNode details = new ObjectMapper().readTree((String) eintraege.getFirst().get("details"));
        assertThat(details.path("kontakt").asText()).isEqualTo(kontakt(kennung).id().toString());
        assertThat(details.path("grund").isMissingNode() ? "" : details.path("grund").asText()).isEqualTo(grund);
    }

    @Dann("endet eine der gleichzeitigen Zusammenführungen mit 200 und die andere mit 409 «STATUS_KONFLIKT»")
    public void gegenlaeufigPruefen() {
        assertThat(gleichzeitig).extracting(Ergebnis::status).containsExactlyInAnyOrder(200, 409);
        assertThat(gleichzeitig.stream().filter(e -> e.status() == 409).findFirst().orElseThrow().fehlercode())
                .isEqualTo("STATUS_KONFLIKT");
    }

    @Dann("antwortet das Ändern des Dokuments mit Status {int} und Fehlercode «{word}»")
    public void dokumentFehler(int status, String code) {
        assertThat(ProblemDetails.pruefen(antwort, status)).isEqualTo(code);
    }

    @After
    public void aufraeumen() throws Exception {
        if (mitschnitt != null) {
            mitschnitt.close();
        }
        if (sperre != null) {
            freigeben.countDown();
            sperre.get(15, TimeUnit.SECONDS);
        }
        for (String mandant : ausloeser) {
            ausfuehren("drop trigger if exists " + AUSLOESER + " on " + schema(mandant) + ".zugriff_protokoll");
            ausfuehren("drop function if exists " + schema(mandant) + "." + AUSLOESER + "()");
        }
        Map<String, List<UUID>> jeMandant = new HashMap<>();
        dokumente.values().forEach(o -> jeMandant.computeIfAbsent(o.mandant(), m -> new ArrayList<>()).add(o.id()));
        for (Map.Entry<String, List<UUID>> e : jeMandant.entrySet()) {
            Testdokumente.loeschen(datenbank.get(), e.getKey(), e.getValue());
        }
        Map<String, List<UUID>> kontakteJeMandant = new HashMap<>();
        kontakte.values().forEach(o -> kontakteJeMandant.computeIfAbsent(o.mandant(), m -> new ArrayList<>())
                .add(o.id()));
        try (Connection verbindung = datenbank.get().getConnection()) {
            for (Map.Entry<String, List<UUID>> e : kontakteJeMandant.entrySet()) {
                String schema = schema(e.getKey());
                for (String sql : List.of("delete from " + schema + ".verkaufschance where kontakt_id = any(?)",
                        "delete from " + schema + ".ki_beispiel where partner_id = any(?)",
                        "delete from " + schema + ".kontakt where id = any(?)")) {
                    try (PreparedStatement loeschen = verbindung.prepareStatement(sql)) {
                        loeschen.setArray(1, verbindung.createArrayOf("uuid", e.getValue().toArray()));
                        loeschen.executeUpdate();
                    }
                }
            }
        }
    }

    private Ergebnis senden(CountDownLatch start, String token, String quelle, String ifMatch,
                            Map<String, Object> koerper) {
        try {
            start.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
        Response r = given().auth().oauth2(token).contentType(ContentType.JSON).header("If-Match", ifMatch)
                .body(koerper).when().post(KONTAKTE + "/" + kontakt(quelle).id() + "/zusammenfuehren");
        return new Ergebnis(r.statusCode(), r.statusCode() == 200 ? null : r.path("fehlercode"));
    }

    private void zusammenfuehren(String benutzer, String quelle, String ifMatch, Map<String, Object> koerper) {
        RequestSpecification anfrage = anfrage(benutzer).body(koerper);
        if (ifMatch != null) {
            anfrage.header("If-Match", ifMatch);
        }
        long start = System.nanoTime();
        antwort = anfrage.when().post(KONTAKTE + "/" + kontakt(quelle).id() + "/zusammenfuehren");
        dauer = Duration.ofNanos(System.nanoTime() - start);
    }

    /** Status, Versionen und Verweise aller Kontakte und Dokumente des Szenarios, dazu ihre Protokolleinträge. */
    private Map<String, Object> stand() throws SQLException {
        Map<String, Object> s = new LinkedHashMap<>();
        for (Map.Entry<String, Ort> k : kontakte.entrySet()) {
            s.put("kontakt " + k.getKey(), kontaktStand(k.getKey()));
            String schema = schema(k.getValue().mandant());
            s.put("chancen " + k.getKey(), zahl("select count(*) from " + schema + ".verkaufschance where kontakt_id = ?",
                    k.getValue().id()));
            s.put("beispiele " + k.getKey(), zahl("select count(*) from " + schema + ".ki_beispiel where partner_id = ?",
                    k.getValue().id()));
            s.put("protokoll " + k.getKey(), zahl("select count(*) from " + schema + ".zugriff_protokoll where "
                    + "aktion like 'KONTAKT_%' and (details ->> 'quelle' = ? or details ->> 'kontakt' = ?)",
                    k.getValue().id().toString(), k.getValue().id().toString()));
        }
        for (Map.Entry<String, Ort> d : dokumente.entrySet()) {
            s.put("dokument " + d.getKey(), Arrays.asList(dokumentWert(d.getKey(), "partner_id"),
                    dokumentWert(d.getKey(), "ki_partner_id"), dokumentWert(d.getKey(), "version"),
                    zahl("select count(*) from " + schema(d.getValue().mandant()) + ".dokument_version "
                            + "where dokument_id = ?", d.getValue().id())));
        }
        return s;
    }

    private List<Object> kontaktStand(String kennung) throws SQLException {
        Ort k = kontakt(kennung);
        try (Connection verbindung = datenbank.get().getConnection(); PreparedStatement abfrage =
                verbindung.prepareStatement("select aktiv, inaktiv_seit is not null, zusammengefuehrt_in, version from "
                        + schema(k.mandant()) + ".kontakt where id = ?")) {
            abfrage.setObject(1, k.id());
            try (ResultSet z = abfrage.executeQuery()) {
                assertThat(z.next()).as(kennung).isTrue();
                return Arrays.asList(z.getBoolean(1), z.getBoolean(2), z.getObject(3, UUID.class), z.getInt(4));
            }
        }
    }

    private List<Map<String, Object>> protokoll(String mandant, String aktion, UUID kontakt) throws SQLException {
        String sql = "select benutzer, rolle, dokument_id, details::text from " + schema(mandant)
                + ".zugriff_protokoll where aktion = ? and (details ->> 'quelle' = ? or details ->> 'kontakt' = ?) "
                + "order by id";
        List<Map<String, Object>> eintraege = new ArrayList<>();
        try (Connection verbindung = datenbank.get().getConnection();
             PreparedStatement abfrage = verbindung.prepareStatement(sql)) {
            abfrage.setString(1, aktion);
            abfrage.setString(2, kontakt.toString());
            abfrage.setString(3, kontakt.toString());
            try (ResultSet z = abfrage.executeQuery()) {
                while (z.next()) {
                    Map<String, Object> e = new HashMap<>();
                    e.put("benutzer", z.getString(1));
                    e.put("rolle", z.getString(2));
                    e.put("dokument", z.getObject(3, UUID.class));
                    e.put("details", z.getString(4));
                    eintraege.add(e);
                }
            }
        }
        return eintraege;
    }

    private Object dokumentWert(String dokument, String spalte) throws SQLException {
        Ort d = dokument(dokument);
        try (Connection verbindung = datenbank.get().getConnection(); PreparedStatement abfrage =
                verbindung.prepareStatement("select " + spalte + " from " + schema(d.mandant()) + ".dokument where id = ?")) {
            abfrage.setObject(1, d.id());
            try (ResultSet z = abfrage.executeQuery()) {
                assertThat(z.next()).as(dokument).isTrue();
                return z.getObject(1);
            }
        }
    }

    private int version(String kennung) throws SQLException {
        return (Integer) kontaktStand(kennung).get(3);
    }

    private long zahl(String sql, Object... parameter) throws SQLException {
        try (Connection verbindung = datenbank.get().getConnection();
             PreparedStatement abfrage = verbindung.prepareStatement(sql)) {
            for (int i = 0; i < parameter.length; i++) {
                abfrage.setObject(i + 1, parameter[i]);
            }
            try (ResultSet z = abfrage.executeQuery()) {
                z.next();
                return z.getLong(1);
            }
        }
    }

    private void ausfuehren(String sql, Object... parameter) throws SQLException {
        try (Connection verbindung = datenbank.get().getConnection();
             PreparedStatement befehl = verbindung.prepareStatement(sql)) {
            for (int i = 0; i < parameter.length; i++) {
                if (parameter[i] == null) {
                    befehl.setNull(i + 1, Types.OTHER);
                } else if (parameter[i] instanceof LocalDate datum) {
                    befehl.setObject(i + 1, datum);
                } else {
                    befehl.setObject(i + 1, parameter[i]);
                }
            }
            befehl.execute();
        }
    }

    /** {@code <K2>} → ID des Kontakts K2; {@code null} → null; leer → leer. */
    private String wert(String wert) {
        if (wert == null) {
            return "null";
        }
        String text = wert;
        for (Map.Entry<String, Ort> k : kontakte.entrySet()) {
            text = text.replace("<" + k.getKey() + ">", k.getValue().id().toString());
        }
        return text;
    }

    private static String oder(String wert, String standard) {
        return wert == null || wert.isBlank() ? standard : wert;
    }

    private static List<String> kennungen(String kennungen) {
        return kennungen.isBlank() ? List.of() : Arrays.stream(kennungen.split(",")).map(String::strip).toList();
    }

    private String kennungZuKontakt(String id) {
        return kontakte.entrySet().stream().filter(e -> e.getValue().id().toString().equals(id)).map(Map.Entry::getKey)
                .findFirst().orElse("?" + id);
    }

    private String kennungZuDokument(String id) {
        return dokumente.entrySet().stream().filter(e -> e.getValue().id().toString().equals(id)).map(Map.Entry::getKey)
                .findFirst().orElse("?" + id);
    }

    private Ort kontakt(String kennung) {
        return Objects.requireNonNull(kontakte.get(kennung), "Kontakt " + kennung);
    }

    private Ort dokument(String kennung) {
        return Objects.requireNonNull(dokumente.get(kennung), "Dokument " + kennung);
    }

    private static RequestSpecification anfrage(String benutzer) {
        return given().auth().oauth2(Testanmeldung.token(benutzer)).contentType(ContentType.JSON);
    }

    private static String schema(String mandant) {
        return new MandantCode(mandant).schema();
    }
}
