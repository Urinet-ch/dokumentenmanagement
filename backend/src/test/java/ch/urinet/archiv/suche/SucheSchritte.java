package ch.urinet.archiv.suche;

import ch.urinet.archiv.dokument.Testdokumente;
import ch.urinet.archiv.gemeinsam.LogMitschnitt;
import ch.urinet.archiv.gemeinsam.Testanmeldung;
import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.sicherheit.ProblemDetails;
import io.agroal.api.AgroalDataSource;
import io.cucumber.datatable.DataTable;
import io.cucumber.java.After;
import io.cucumber.java.de.Angenommen;
import io.cucumber.java.de.Dann;
import io.cucumber.java.de.Und;
import io.cucumber.java.de.Wenn;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Schritte zu Aufgabe 4.5 (Req 14.1–14.5, 14.7): synthetische Dokumente per JDBC mit Finanzangaben und Volltext,
 * Suche über REST. Jedes Szenario hängt seinen Dokumenten einen eigenen Lauf-Tag an und filtert jede Suche darauf.
 */
public class SucheSchritte {

    private record Ort(String mandant, UUID id) {
    }

    @Inject
    Instance<AgroalDataSource> datenbank;

    private static final Pattern TAGS = Pattern.compile("\\{(\\d+) Tags}");

    private final String lauf = "lauf" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    private final Map<String, Ort> dokumente = new LinkedHashMap<>();
    private final Map<UUID, String> namen = new HashMap<>();
    private Response antwort;
    private LogMitschnitt log;

    @After
    public void aufraeumen() throws SQLException {
        if (log != null) {
            log.close();
            log = null;
        }
        Map<String, List<UUID>> jeMandant = new HashMap<>();
        dokumente.values().forEach(o -> jeMandant.computeIfAbsent(o.mandant(), m -> new ArrayList<>()).add(o.id()));
        for (Map.Entry<String, List<UUID>> eintrag : jeMandant.entrySet()) {
            Testdokumente.loeschen(datenbank.get(), eintrag.getKey(), eintrag.getValue());
        }
        dokumente.clear();
        namen.clear();
    }

    @Angenommen("im Mandanten «{word}» liegen diese Suchdokumente:")
    public void anlegen(String mandant, DataTable tabelle) throws SQLException {
        String schema = new MandantCode(mandant).schema();
        try (Connection verbindung = datenbank.get().getConnection()) {
            for (Map<String, String> zeile : tabelle.asMaps()) {
                UUID id = UUID.randomUUID();
                dokumentEinfuegen(verbindung, schema, id, zeile);
                if (zeile.get("betrag") != null || zeile.get("belegnummer") != null) {
                    finanzEinfuegen(verbindung, schema, id, zeile);
                }
                dokumente.put(zeile.get("name"), new Ort(mandant, id));
                namen.put(id, zeile.get("name"));
            }
        }
    }

    private void dokumentEinfuegen(Connection verbindung, String schema, UUID id, Map<String, String> z)
            throws SQLException {
        boolean bestaetigt = "ja".equals(z.get("bestaetigt"));
        LocalDate datum = z.get("datum") == null ? null : LocalDate.parse(z.get("datum"));
        List<String> tags = new ArrayList<>(z.get("tags") == null ? List.of() : Arrays.asList(z.get("tags").split(",")));
        tags.add(lauf);
        try (PreparedStatement befehl = verbindung.prepareStatement("insert into " + schema + ".dokument (id, art, "
                + "titel, datum, quelle, partner_id, partner_name, tags, vertraulichkeit, person_benutzer, erstellt_von, "
                + "status, bestaetigt, bestaetigt_von, geschaeftsjahr, volltext) "
                + "values (?, ?, ?, ?, 'UPLOAD', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            befehl.setObject(1, id);
            befehl.setString(2, z.get("art"));
            befehl.setString(3, z.get("titel"));
            befehl.setObject(4, datum, Types.DATE);
            befehl.setObject(5, z.get("partner") == null ? null : partnerId(z.get("partner")), Types.OTHER);
            befehl.setString(6, z.get("partner"));
            befehl.setArray(7, verbindung.createArrayOf("text", tags.toArray()));
            befehl.setString(8, z.get("vertraulichkeit"));
            befehl.setString(9, z.get("person") == null ? null : Testanmeldung.sub(z.get("person")));
            befehl.setString(10, Testanmeldung.sub(z.get("erfasser")));
            befehl.setString(11, z.get("status"));
            befehl.setObject(12, bestaetigt ? OffsetDateTime.now() : null, Types.TIMESTAMP_WITH_TIMEZONE);
            befehl.setString(13, bestaetigt ? "probe" : null);
            befehl.setObject(14, datum == null ? null : datum.getYear(), Types.INTEGER);
            befehl.setString(15, z.get("text"));
            befehl.executeUpdate();
        }
    }

    private static void finanzEinfuegen(Connection verbindung, String schema, UUID id, Map<String, String> z)
            throws SQLException {
        try (PreparedStatement befehl = verbindung.prepareStatement("insert into " + schema + ".dokument_finanz "
                + "(dokument_id, belegnummer, richtung, betrag, waehrung) values (?, ?, ?, ?, 'CHF')")) {
            befehl.setObject(1, id);
            befehl.setString(2, z.get("belegnummer"));
            befehl.setString(3, z.get("richtung"));
            befehl.setBigDecimal(4, z.get("betrag") == null ? null : new BigDecimal(z.get("betrag")));
            befehl.executeUpdate();
        }
    }

    /** Synthetische Partner-ID aus dem Namen, damit der Filter {@code partner} den Namen auflösen kann. */
    private static UUID partnerId(String name) {
        return UUID.nameUUIDFromBytes(("partner:" + name).getBytes(StandardCharsets.UTF_8));
    }

    @Angenommen("das Log der Anwendung wird mitgeschnitten")
    public void logMitschneiden() {
        log = LogMitschnitt.starten();
    }

    @Wenn("«{word}» sucht mit:")
    public void suchenMit(String benutzer, DataTable tabelle) {
        List<String[]> parameter = new ArrayList<>();
        tabelle.asLists().forEach(z -> parameter.add(new String[] {z.get(0), z.get(1)}));
        antwort = suchen(benutzer, parameter);
    }

    @Dann("finden die Benutzer mit dem Suchbegriff «{}» diese Dokumente:")
    public void rollen(String begriff, DataTable tabelle) {
        for (Map<String, String> zeile : tabelle.asMaps()) {
            Response r = suchen(zeile.get("benutzer"), List.<String[]>of(new String[] {"q", begriff},
                    new String[] {"groesse", "100"}));
            String fall = zeile.get("benutzer") + " (" + zeile.get("grund") + ")";
            assertThat(r.statusCode()).as(fall).isEqualTo(200);
            assertThat(trefferNamen(r)).as(fall).containsExactlyInAnyOrderElementsOf(liste(zeile.get("treffer")));
            assertThat(r.jsonPath().getLong("gesamt")).as(fall).isEqualTo(Long.parseLong(zeile.get("gesamt")));
        }
    }

    @Dann("findet «{word}» mit diesen Suchbegriffen diese Dokumente:")
    public void begriffe(String benutzer, DataTable tabelle) {
        for (Map<String, String> zeile : tabelle.asMaps()) {
            Response r = suchen(benutzer, List.<String[]>of(new String[] {"q", zeile.get("suchbegriff")}));
            String fall = zeile.get("suchbegriff") + " (" + zeile.get("grund") + ")";
            assertThat(r.statusCode()).as(fall).isEqualTo(200);
            assertThat(trefferNamen(r)).as(fall).containsExactlyInAnyOrderElementsOf(liste(zeile.get("treffer")));
            assertThat(r.jsonPath().getLong("gesamt")).as(fall).isEqualTo(liste(zeile.get("treffer")).size());
        }
    }

    @Dann("findet «{word}» mit diesen Filtern diese Dokumente in dieser Reihenfolge:")
    public void filter(String benutzer, DataTable tabelle) {
        for (Map<String, String> zeile : tabelle.asMaps()) {
            List<String[]> parameter = new ArrayList<>();
            for (String paar : zeile.get("filter").split("&")) {
                String[] teile = paar.split("=", 2);
                String wert = "partner".equals(teile[0]) ? partnerId(teile[1]).toString() : teile[1];
                parameter.add(new String[] {teile[0], wert});
            }
            Response r = suchen(benutzer, parameter);
            String fall = benutzer + ": " + zeile.get("filter");
            assertThat(r.statusCode()).as(fall).isEqualTo(200);
            assertThat(trefferNamen(r)).as(fall).containsExactlyElementsOf(liste(zeile.get("treffer")));
            assertThat(r.jsonPath().getLong("gesamt")).as(fall).isEqualTo(liste(zeile.get("treffer")).size());
        }
    }

    @Dann("antwortet die Suche für «{word}» so:")
    public void eingaben(String benutzer, DataTable tabelle) {
        for (Map<String, String> zeile : tabelle.asMaps()) {
            List<String[]> parameter = new ArrayList<>();
            String[] namenListe = zeile.get("parameter").split(",");
            String[] werte = namenListe.length > 1 ? zeile.get("wert").split(",") : new String[] {zeile.get("wert")};
            for (int i = 0; i < namenListe.length; i++) {
                switch (werte[i]) {
                    case "{201 Zeichen}" -> parameter.add(new String[] {namenListe[i], "a".repeat(201)});
                    case "{Steuerzeichen}" -> parameter.add(new String[] {namenListe[i], "Rech\u0007nung"});
                    case "{leer}" -> parameter.add(new String[] {namenListe[i], ""});
                    default -> {
                        Matcher tags = TAGS.matcher(werte[i]);
                        if (tags.matches()) {
                            // der Lauf-Tag zählt mit (suchen hängt ihn an)
                            for (int t = 1; t < Integer.parseInt(tags.group(1)); t++) {
                                parameter.add(new String[] {namenListe[i], "t" + t});
                            }
                        } else {
                            parameter.add(new String[] {namenListe[i], werte[i]});
                        }
                    }
                }
            }
            Response r = suchen(benutzer, parameter);
            String fall = zeile.get("parameter") + " = " + zeile.get("wert") + " (" + zeile.get("grund") + ")";
            int status = Integer.parseInt(zeile.get("status"));
            assertThat(r.statusCode()).as(fall).isEqualTo(status);
            if (status == 400) {
                assertThat(ProblemDetails.pruefen(r, 400)).as(fall).isEqualTo("ANFRAGE_UNGUELTIG");
                if (werte[0].length() >= 6) {
                    assertThat(r.asString()).as(fall).doesNotContain(werte[0]);
                }
            } else {
                assertThat(r.jsonPath().getList("treffer")).as(fall).isNotNull();
            }
        }
    }

    @Dann("antwortet die Suche ohne Anmeldung mit {int}")
    public void ohneAnmeldung(int status) {
        given().queryParam("q", "Rechnung").when().get("/api/v1/dokumente").then().statusCode(status);
    }

    @Dann("ist die Gesamtzahl {int}")
    public void gesamt(int gesamt) {
        assertThat(antwort.statusCode()).isEqualTo(200);
        assertThat(antwort.jsonPath().getLong("gesamt")).isEqualTo(gesamt);
    }

    @Und("die Seite enthält {int} Treffer")
    public void seite(int anzahl) {
        assertThat(antwort.jsonPath().getList("treffer")).hasSize(anzahl);
    }

    @Dann("lauten die Treffer der Reihe nach «{}»")
    public void reihenfolge(String erwartet) {
        assertThat(antwort.statusCode()).isEqualTo(200);
        assertThat(trefferNamen(antwort)).containsExactlyElementsOf(liste(erwartet));
    }

    @Dann("ist der erste Treffer «{word}»")
    public void ersterTreffer(String name) {
        assertThat(antwort.statusCode()).isEqualTo(200);
        assertThat(trefferNamen(antwort)).first().isEqualTo(name);
        assertThat(antwort.jsonPath().getString("treffer[0].belegnummer")).isNotNull();
    }

    @Und("die Treffer enthalten «{word}» nicht")
    public void enthaeltNicht(String name) {
        assertThat(trefferNamen(antwort)).doesNotContain(name);
        assertThat(antwort.jsonPath().getList("treffer.id", String.class))
                .doesNotContain(dokumente.get(name).id().toString());
    }

    @Dann("zeigt der Auszug von «{word}» «{}» hervorgehoben")
    public void auszug(String name, String begriff) {
        List<Map<String, Object>> teile = auszugVon(name);
        assertThat(teile).anySatisfy(t -> {
            assertThat(t.get("treffer")).isEqualTo(true);
            assertThat((String) t.get("text")).isEqualToIgnoringCase(begriff);
        });
        String text = String.join("", teile.stream().map(t -> (String) t.get("text")).toList());
        assertThat(text).doesNotContain("\u0002", "\u0003", "\u001f", "<b>");
    }

    @Dann("ist der Auszug von «{word}» leer")
    public void auszugLeer(String name) {
        assertThat(auszugVon(name)).isEmpty();
    }

    @Dann("enthält das Log «{}» nicht")
    public void logOhne(String begriff) {
        assertThat(log.volltext()).doesNotContainIgnoringCase(begriff);
    }

    private List<Map<String, Object>> auszugVon(String name) {
        assertThat(antwort.statusCode()).isEqualTo(200);
        List<Map<String, Object>> treffer = antwort.jsonPath().getList("treffer");
        String id = dokumente.get(name).id().toString();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> auszug = treffer.stream().filter(t -> id.equals(t.get("id"))).findFirst()
                .map(t -> (List<Map<String, Object>>) t.get("auszug")).orElseThrow();
        return auszug;
    }

    private Response suchen(String benutzer, List<String[]> parameter) {
        RequestSpecification anfrage = given().auth().oauth2(Testanmeldung.token(benutzer)).urlEncodingEnabled(true);
        Map<String, List<String>> werte = new LinkedHashMap<>();
        parameter.forEach(p -> werte.computeIfAbsent(p[0], k -> new ArrayList<>()).add(p[1]));
        werte.computeIfAbsent("tag", k -> new ArrayList<>()).add(lauf);
        werte.forEach((name, liste) -> anfrage.queryParam(name, liste));
        return anfrage.when().get("/api/v1/dokumente");
    }

    private List<String> trefferNamen(Response r) {
        return r.jsonPath().getList("treffer.id", String.class).stream()
                .map(id -> namen.getOrDefault(UUID.fromString(id), "fremd:" + id)).toList();
    }

    private static List<String> liste(String text) {
        return text == null ? List.of() : Arrays.stream(text.split(",")).map(String::trim).toList();
    }
}
