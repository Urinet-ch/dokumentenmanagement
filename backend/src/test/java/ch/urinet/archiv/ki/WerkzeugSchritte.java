package ch.urinet.archiv.ki;

import ch.urinet.archiv.dokument.Testdokumente;
import ch.urinet.archiv.gemeinsam.Testanmeldung;
import ch.urinet.archiv.gemeinsam.domain.Normalisierer;
import ch.urinet.archiv.ki.adapter.in.werkzeug.ArchivWerkzeuge;
import ch.urinet.archiv.ki.application.chat.WerkzeugKontext;
import ch.urinet.archiv.ki.application.chat.WerkzeugKontextRegister;
import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.sicherheit.domain.Benutzer;
import ch.urinet.archiv.sicherheit.domain.Rolle;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.sql.Types;
import java.time.LocalDate;
import java.time.OffsetDateTime;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Schritte zu Spec 02, Aufgabe 7.1a (Req 3.5, 5.1, 5.2): synthetische Dokumente per JDBC, die Werkzeuge direkt über CDI,
 * jeder Aufruf in einem eigenen Thread ohne Request-Kontext und Anmeldung. Der Kontext steht wie zu Beginn einer
 * Chat-Anfrage im {@link WerkzeugKontextRegister}; Rolle und Mandant folgen aus dem Namen des Testbenutzers
 * ({@code <rolle>-<mandant>}), die Benutzer-ID aus seiner Anmeldung.
 */
public class WerkzeugSchritte {

    private record Ort(String mandant, UUID id) {
    }

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern PLATZHALTER = Pattern.compile("\\{([A-Z_]+)}");
    private static final Set<String> DETAILS = Set.of("grund", "fehlercode", "kanal");

    @Inject
    Instance<AgroalDataSource> datenbank;

    @Inject
    ArchivWerkzeuge werkzeuge;

    @Inject
    WerkzeugKontextRegister register;

    private final String lauf = "chat-" + UUID.randomUUID();
    private final UUID unbekannt = UUID.randomUUID();
    private final Map<String, Ort> dokumente = new LinkedHashMap<>();
    private final Map<UUID, String> namen = new HashMap<>();
    private final Map<String, Ort> kontakte = new HashMap<>();
    private final Set<String> laeufe = new HashSet<>();
    private final Map<String, Long> stand = new HashMap<>();
    private JsonNode antwort;
    private String antwortText;

    @After
    public void aufraeumen() throws SQLException {
        laeufe.forEach(register::entfernen);
        laeufe.clear();
        Map<String, List<UUID>> jeMandant = new HashMap<>();
        dokumente.values().forEach(o -> jeMandant.computeIfAbsent(o.mandant(), m -> new ArrayList<>()).add(o.id()));
        for (Map.Entry<String, List<UUID>> eintrag : jeMandant.entrySet()) {
            Testdokumente.loeschen(datenbank.get(), eintrag.getKey(), eintrag.getValue());
        }
        try (Connection verbindung = datenbank.get().getConnection()) {
            for (Ort kontakt : kontakte.values()) {
                try (PreparedStatement loeschen = verbindung.prepareStatement(
                        "delete from " + schema(kontakt.mandant()) + ".kontakt where id = ?")) {
                    loeschen.setObject(1, kontakt.id());
                    loeschen.executeUpdate();
                }
            }
        }
        dokumente.clear();
        namen.clear();
        kontakte.clear();
    }

    // --- Daten ------------------------------------------------------------------------------------------------------

    @Angenommen("im Mandanten «{word}» gibt es die Kontakte «{}» und «{}»")
    public void kontakteAnlegen(String mandant, String erster, String zweiter) throws SQLException {
        try (Connection verbindung = datenbank.get().getConnection()) {
            for (String name : List.of(erster, zweiter)) {
                UUID id = UUID.randomUUID();
                try (PreparedStatement befehl = verbindung.prepareStatement("insert into " + schema(mandant)
                        + ".kontakt (id, typ, name, name_normalisiert, quelle) values (?, 'ORGANISATION', ?, ?, 'TEST')")) {
                    befehl.setObject(1, id);
                    befehl.setString(2, name);
                    befehl.setString(3, Normalisierer.name(name));
                    befehl.executeUpdate();
                }
                kontakte.put(name, new Ort(mandant, id));
            }
        }
    }

    @Angenommen("im Mandanten «{word}» liegen diese Chat-Dokumente:")
    public void dokumenteAnlegen(String mandant, DataTable tabelle) throws SQLException {
        try (Connection verbindung = datenbank.get().getConnection()) {
            for (Map<String, String> zeile : tabelle.asMaps()) {
                dokumentEinfuegen(verbindung, mandant, zeile);
            }
        }
    }

    @Angenommen("die Chat-Dokumente haben diese Fristen:")
    public void fristenAnlegen(DataTable tabelle) throws SQLException {
        try (Connection verbindung = datenbank.get().getConnection()) {
            for (Map<String, String> z : tabelle.asMaps()) {
                Ort ort = dokumente.get(z.get("name"));
                String schema = schema(ort.mandant());
                if (z.get("faelligkeit") != null) {
                    ausfuehren(verbindung, "insert into " + schema + ".dokument_finanz (dokument_id, faelligkeit) "
                            + "values (?, ?) on conflict (dokument_id) do update set faelligkeit = excluded.faelligkeit",
                            ort.id(), LocalDate.parse(z.get("faelligkeit")));
                }
                if (z.get("naechste_frist") != null || z.get("gueltig_bis") != null) {
                    ausfuehren(verbindung, "insert into " + schema + ".dokument_vertrag (dokument_id, naechste_frist, "
                            + "gueltig_bis, kuendigungsfrist) values (?, ?, ?, ?)", ort.id(),
                            datum(z.get("naechste_frist")), datum(z.get("gueltig_bis")), z.get("kuendigungsfrist"));
                }
                if (z.get("angebot_gueltig_bis") != null) {
                    ausfuehren(verbindung, "update " + schema + ".dokument set zusatz = cast(? as jsonb) where id = ?",
                            JSON.createObjectNode().put("angebot_gueltig_bis", z.get("angebot_gueltig_bis")).toString(),
                            ort.id());
                }
            }
        }
    }

    @Angenommen("im Mandanten «{word}» liegen {int} weitere Rechnungen mit Fälligkeit am «{word}»")
    public void rechnungenAnlegen(String mandant, int anzahl, String faelligkeit) throws SQLException {
        try (Connection verbindung = datenbank.get().getConnection()) {
            for (int i = 0; i < anzahl; i++) {
                String name = "R_" + i;
                dokumentEinfuegen(verbindung, mandant, Map.of("name", name, "titel", "Rechnung Gondelwartung " + i,
                        "art", "AUSGANGSRECHNUNG", "datum", "2070-07-01", "betrag", "100.00", "vertraulichkeit",
                        "NORMAL", "bestaetigt", "ja", "erfasser", "inhaber-" + mandant, "status", "ARCHIVIERT"));
                ausfuehren(verbindung, "update " + schema(mandant) + ".dokument_finanz set faelligkeit = ? "
                        + "where dokument_id = ?", LocalDate.parse(faelligkeit), dokumente.get(name).id());
            }
        }
    }

    @Angenommen("im Mandanten «{word}» liegt das Chat-Dokument «{word}» mit {int} Zeichen Text")
    public void langesDokument(String mandant, String name, int zeichen) throws SQLException {
        StringBuilder text = new StringBuilder();
        while (text.length() < zeichen) {
            text.append("Gondelrevision Seil Rolle Bremse ");
        }
        try (Connection verbindung = datenbank.get().getConnection()) {
            dokumentEinfuegen(verbindung, mandant, Map.of("name", name, "titel", "Langer Bericht", "art",
                    "KORRESPONDENZ", "datum", "2071-01-02", "vertraulichkeit", "NORMAL", "bestaetigt", "ja", "erfasser",
                    "inhaber-" + mandant, "status", "ARCHIVIERT", "text", text.substring(0, zeichen)));
        }
    }

    private void dokumentEinfuegen(Connection verbindung, String mandant, Map<String, String> z) throws SQLException {
        UUID id = UUID.randomUUID();
        String schema = schema(mandant);
        boolean bestaetigt = "ja".equals(z.get("bestaetigt"));
        LocalDate datum = datum(z.get("datum"));
        String partner = z.get("partner");
        Ort kontakt = partner == null ? null : kontakte.get(partner);
        ausfuehren(verbindung, "insert into " + schema + ".dokument (id, art, titel, datum, quelle, partner_id, "
                        + "partner_name, tags, vertraulichkeit, person_benutzer, erstellt_von, status, bestaetigt, "
                        + "bestaetigt_von, geschaeftsjahr, volltext, dateiname) values (?, ?, ?, ?, 'UPLOAD', ?, ?, "
                        + "cast('{}' as text[]), ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                id, z.get("art"), z.get("titel"), datum, kontakt == null ? null : kontakt.id(), partner,
                z.get("vertraulichkeit"), z.get("person") == null ? null : Testanmeldung.sub(z.get("person")),
                Testanmeldung.sub(z.get("erfasser")), z.get("status"), bestaetigt ? OffsetDateTime.now() : null,
                bestaetigt ? "probe" : null, datum == null ? null : datum.getYear(), z.get("text"), z.get("dateiname"));
        if (z.get("betrag") != null || z.get("belegnummer") != null) {
            ausfuehren(verbindung, "insert into " + schema + ".dokument_finanz (dokument_id, belegnummer, richtung, "
                    + "betrag, waehrung) values (?, ?, 'EINNAHME', ?, 'CHF')", id, z.get("belegnummer"),
                    z.get("betrag") == null ? null : new BigDecimal(z.get("betrag")));
        }
        dokumente.put(z.get("name"), new Ort(mandant, id));
        namen.put(id, z.get("name"));
    }

    // --- Kontext und Aufrufe ------------------------------------------------------------------------------------------

    @Angenommen("«{word}» fragt im Chat über die IP «{}»")
    public void fragtUeberIp(String benutzer, String ip) {
        kontext(benutzer, ip);
    }

    /** Lauf-ID des Benutzers; hält beim ersten Aufruf den Kontext fest wie der Beginn einer Chat-Anfrage. */
    private String kontext(String benutzer) {
        String id = lauf + ":" + benutzer;
        return laeufe.contains(id) ? id : kontext(benutzer, null);
    }

    private String kontext(String benutzer, String ip) {
        String id = lauf + ":" + benutzer;
        int trenner = benutzer.indexOf('-');
        Rolle rolle = Rolle.valueOf(benutzer.substring(0, trenner).toUpperCase(Locale.ROOT));
        MandantCode mandant = new MandantCode(benutzer.substring(trenner + 1));
        register.festhalten(id, WerkzeugKontext.fuer(mandant, new Benutzer(Testanmeldung.sub(benutzer), rolle), ip));
        laeufe.add(id);
        return id;
    }

    private JsonNode suchen(String benutzer, String frage, String art, String partner, Boolean neueste) {
        String id = kontext(benutzer);
        return lesen(imFremdenThread(() -> werkzeuge.dokumenteSuchen(id, frage, art, partner, null, null, neueste, 10)));
    }

    private JsonNode zeigen(String benutzer, String dokument) {
        String id = kontext(benutzer);
        return lesen(imFremdenThread(() -> werkzeuge.dokumentZeigen(id, dokumentId(dokument))));
    }

    private JsonNode fristen(String benutzer, String von, String bis) {
        String id = kontext(benutzer);
        return lesen(imFremdenThread(() -> werkzeuge.fristen(id, von, bis)));
    }

    /** Jeder Aufruf in einem neuen Thread ohne Request-Kontext, wie ein Callback beim Streaming. */
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

    private JsonNode lesen(String text) {
        antwortText = text;
        try {
            antwort = JSON.readTree(text);
        } catch (Exception e) {
            throw new IllegalStateException("Antwort ist kein JSON", e);
        }
        return antwort;
    }

    // --- Prüfungen ----------------------------------------------------------------------------------------------------

    @Dann("liefern die Werkzeuge mit der Suche «{}» und den Fristen von «{word}» bis «{word}» diese Ergebnisse:")
    public void ergebnisse(String frage, String von, String bis, DataTable tabelle) {
        for (Map<String, String> zeile : tabelle.asMaps()) {
            String benutzer = zeile.get("benutzer");
            JsonNode suche = suchen(benutzer, frage, null, null, null);
            assertThat(suche.has("fehler")).as(benutzer + ": " + suche).isFalse();
            assertThat(trefferNamen(suche)).as(benutzer + " Treffer")
                    .containsExactlyInAnyOrderElementsOf(liste(zeile.get("treffer")));
            assertThat(suche.path("gesamt").asLong()).as(benutzer + " Gesamtzahl")
                    .isEqualTo(liste(zeile.get("treffer")).size());
            for (String name : liste(zeile.get("sichtbar"))) {
                JsonNode dokument = zeigen(benutzer, name);
                assertThat(dokument.path("id").asText()).as(benutzer + " zeigt " + name + ": " + dokument)
                        .isEqualTo(dokumente.get(name).id().toString());
            }
            for (String name : liste(zeile.get("unsichtbar"))) {
                zeigen(benutzer, name);
                assertThat(antwortText).as(benutzer + " zeigt " + name).isEqualTo("{\"fehler\":\"nicht gefunden\"}");
            }
            JsonNode frist = fristen(benutzer, von, bis);
            assertThat(frist.path("gesamt").asLong()).as(benutzer + " Gesamtzahl der Fristen: " + frist)
                    .isEqualTo(Long.parseLong(zeile.get("fristen")));
            assertThat(frist.path("fristen").size()).as(benutzer + " Fristen").isEqualTo(
                    Integer.parseInt(zeile.get("fristen")));
            assertThat(frist.path("datenstand").path("anzahl").asLong()).as(benutzer + " Belege im Datenstand")
                    .isEqualTo(Long.parseLong(zeile.get("belege")));
            assertThat(frist.path("datenstand").path("zeitpunkt").asText()).matches("\\d{2}\\.\\d{2}\\.\\d{4} \\d{2}:\\d{2}");
            if ("ja".equals(zeile.get("hinweis"))) {
                assertThat(frist.path("hinweis").asText()).as(benutzer).isEqualTo("nur Belege, die du sehen darfst");
            } else {
                assertThat(frist.has("hinweis")).as(benutzer + " ohne Hinweis").isFalse();
            }
            for (JsonNode f : frist.path("fristen")) {
                assertThat(namen.get(UUID.fromString(f.path("dokument").path("id").asText()))).as(benutzer)
                        .isNotNull();
            }
        }
    }

    @Dann("antwortet jedes Werkzeug ohne Kontext mit «{}»")
    public void ohneKontext(String erwartet) {
        for (String id : Arrays.asList(lauf + ":niemand", null)) {
            assertAlleWerkzeuge(id, erwartet);
        }
    }

    @Dann("nach dem Ende der Chat-Anfrage von «{word}» antwortet jedes Werkzeug mit «{}»")
    public void nachDemEnde(String benutzer, String erwartet) {
        String id = kontext(benutzer);
        assertThat(imFremdenThread(() -> werkzeuge.fristen(id, "2071-01-01", "2071-12-31"))).doesNotContain("fehler");
        register.entfernen(id);
        laeufe.remove(id);
        assertAlleWerkzeuge(id, erwartet);
    }

    private void assertAlleWerkzeuge(String id, String erwartet) {
        String dokument = dokumente.get("U_NORMAL").id().toString();
        assertThat(imFremdenThread(() -> werkzeuge.dokumenteSuchen(id, "Gondelrevision", null, null, null, null, null,
                null))).isEqualTo(erwartet);
        assertThat(imFremdenThread(() -> werkzeuge.dokumentZeigen(id, dokument))).isEqualTo(erwartet);
        assertThat(imFremdenThread(() -> werkzeuge.fristen(id, "2071-01-01", "2071-12-31"))).isEqualTo(erwartet);
    }

    @Dann("sind in den Treffern von «{word}» zu «{}» genau «{}» unbestätigt")
    public void unbestaetigt(String benutzer, String frage, String erwartet) {
        JsonNode suche = suchen(benutzer, frage, null, null, null);
        List<String> offen = new ArrayList<>();
        for (JsonNode t : suche.path("treffer")) {
            assertThat(t.has("bestaetigt")).isTrue();
            if (!t.path("bestaetigt").asBoolean()) {
                offen.add(namen.get(UUID.fromString(t.path("id").asText())));
            }
        }
        assertThat(offen).as(benutzer).containsExactlyInAnyOrderElementsOf(liste(erwartet));
    }

    @Dann("zeigt dokument_zeigen «{word}» für «{word}» mit «{word}» «{word}»")
    public void zeigtFeld(String dokument, String benutzer, String feld, String wert) {
        assertThat(zeigen(benutzer, dokument).path(feld).asText()).as(antwortText).isEqualTo(wert);
    }

    @Wenn("«{word}» im Chat die Fristen von «{word}» bis «{word}» abfragt")
    public void fristenAbfragen(String benutzer, String von, String bis) {
        fristen(benutzer, von, bis);
    }

    @Dann("lauten die Fristen der Reihe nach:")
    public void fristenReihenfolge(DataTable tabelle) {
        List<Map<String, String>> erwartet = tabelle.asMaps();
        JsonNode liste = antwort.path("fristen");
        assertThat(liste.size()).as(antwortText).isEqualTo(erwartet.size());
        for (int i = 0; i < erwartet.size(); i++) {
            Map<String, String> e = erwartet.get(i);
            JsonNode f = liste.get(i);
            String fall = "Frist " + (i + 1) + ": " + f;
            assertThat(f.path("datum").asText()).as(fall).isEqualTo(e.get("datum"));
            assertThat(f.path("frist").asText()).as(fall).isEqualTo(e.get("frist"));
            assertThat(f.path("dokumentart").asText()).as(fall).isEqualTo(e.get("dokumentart"));
            String id = dokumente.get(e.get("dokument")).id().toString();
            assertThat(f.path("dokument").path("id").asText()).as(fall).isEqualTo(id);
            assertThat(f.path("dokument").path("link").asText()).as(fall).isEqualTo("/dokument/" + id);
            assertThat(f.path("dokument").path("titel").asText()).as(fall).isNotEmpty();
            assertThat(f.path("beschreibung").isMissingNode() ? null : f.path("beschreibung").asText()).as(fall)
                    .isEqualTo(e.get("beschreibung"));
        }
    }

    @Dann("die Fristen haben die Gesamtzahl {int}, den Datenstand mit {int} Belegen und keinen Hinweis")
    public void fristenZahlen(int gesamt, int belege) {
        assertThat(antwort.path("gesamt").asInt()).isEqualTo(gesamt);
        assertThat(antwort.path("datenstand").path("anzahl").asInt()).isEqualTo(belege);
        assertThat(antwort.has("hinweis")).isFalse();
    }

    @Dann("enthält die Antwort {int} Fristen bei der Gesamtzahl {int} und den Datenstand mit {int} Belegen")
    public void fristenBegrenzt(int anzahl, int gesamt, int belege) {
        assertThat(antwort.path("fristen").size()).isEqualTo(anzahl);
        assertThat(antwort.path("gesamt").asInt()).isEqualTo(gesamt);
        assertThat(antwort.path("datenstand").path("anzahl").asInt()).isEqualTo(belege);
    }

    @Dann("findet «{word}» mit dokumente_suchen zu «{}» keine Treffer")
    public void keineTreffer(String benutzer, String frage) {
        JsonNode suche = suchen(benutzer, frage, null, null, null);
        assertThat(suche.path("treffer").size()).as(antwortText).isZero();
        assertThat(suche.path("gesamt").asLong()).isZero();
    }

    @Dann("findet «{word}» mit dokumente_suchen zu «{}» die Treffer «{}»")
    public void treffer(String benutzer, String frage, String erwartet) {
        assertThat(trefferNamen(suchen(benutzer, frage, null, null, null))).as(antwortText)
                .containsExactlyInAnyOrderElementsOf(liste(erwartet));
    }

    @Dann("keine Antwort von dokumente_suchen zu «{}» oder von dokument_zeigen für «{word}» und «{word}» enthält den Dateinamen")
    public void ohneDateiname(String frage, String erstes, String zweites) {
        List<String> antworten = new ArrayList<>();
        JsonNode suche = suchen("inhaber-urinet", frage, null, null, null);
        assertThat(suche.path("treffer").size()).isPositive();
        antworten.add(antwortText);
        for (String name : List.of(erstes, zweites)) {
            assertThat(zeigen("inhaber-urinet", name).has("id")).as(antwortText).isTrue();
            antworten.add(antwortText);
        }
        for (String text : antworten) {
            assertThat(text).doesNotContain("dateiname", "Zahnradbahn", "Gondelrevision_Scan", "Scan", ".pdf");
        }
    }

    @Dann("findet «{word}» mit dokumente_suchen diese Treffer:")
    public void trefferMitFiltern(String benutzer, DataTable tabelle) {
        for (Map<String, String> zeile : tabelle.asMaps()) {
            JsonNode suche = suchen(benutzer, zeile.get("frage"), zeile.get("art"), zeile.get("partner"),
                    zeile.get("neueste") == null ? null : Boolean.valueOf(zeile.get("neueste")));
            String fall = zeile + ": " + antwortText;
            assertThat(suche.has("fehler")).as(fall).isFalse();
            if ("true".equals(zeile.get("neueste"))) {
                assertThat(trefferNamen(suche)).as(fall).containsExactlyElementsOf(liste(zeile.get("treffer")));
            } else {
                assertThat(trefferNamen(suche)).as(fall).containsExactlyInAnyOrderElementsOf(liste(zeile.get("treffer")));
            }
            if (zeile.get("hinweis") == null) {
                assertThat(suche.has("hinweis")).as(fall).isFalse();
            } else {
                assertThat(suche.path("hinweis").asText()).as(fall).isEqualTo(zeile.get("hinweis"));
                assertThat(suche.path("gesamt").asLong()).as(fall).isZero();
            }
        }
    }

    @Dann("antwortet dokumente_suchen für «{word}» mit der Art «{word}» mit dem Fehler «{}» und den aktiven Codes, darunter «{word}»")
    public void unbekannteArt(String benutzer, String art, String fehler, String code) {
        JsonNode suche = suchen(benutzer, "Gondelrevision", art, null, null);
        assertThat(suche.path("fehler").asText()).as(antwortText).isEqualTo(fehler);
        List<String> arten = new ArrayList<>();
        suche.path("arten").forEach(a -> arten.add(a.asText()));
        assertThat(arten).contains(code).doesNotContain(art);
    }

    @Dann("antwortet dokument_zeigen für «{word}» bei diesen IDs gleich mit «{}»:")
    public void gleichNichtGefunden(String benutzer, String erwartet, DataTable tabelle) {
        for (Map<String, String> zeile : tabelle.asMaps()) {
            zeigen(benutzer, zeile.get("id"));
            assertThat(antwortText).as(zeile.get("grund")).isEqualTo(erwartet);
        }
    }

    @Wenn("«{word}» im Chat das Dokument «{word}» zeigen lässt")
    public void zeigenLassen(String benutzer, String dokument) {
        zeigen(benutzer, dokument);
    }

    @Dann("zeigt die Antwort diese Werte:")
    public void werte(DataTable tabelle) {
        for (Map<String, String> zeile : tabelle.asMaps()) {
            JsonNode knoten = antwort;
            for (String teil : zeile.get("feld").split("\\.")) {
                knoten = knoten.path(teil);
            }
            assertThat(knoten.asText()).as(zeile.get("feld") + ": " + antwortText).isEqualTo(ersetzen(zeile.get("wert")));
        }
        assertThat(antwortText).doesNotContain("dateiname");
    }

    @Dann("ist der Inhalt der Antwort {int} Zeichen lang")
    public void inhaltLaenge(int zeichen) {
        assertThat(antwort.path("inhalt").asText()).hasSize(zeichen);
    }

    @Dann("ist die Antwort ein Fehler ohne Inhalt")
    public void fehlerOhneInhalt() {
        assertThat(antwort.has("fehler")).as(antwortText).isTrue();
        assertThat(antwortText).doesNotContain("inhalt", "Nordbahn", "Revision Gondel");
    }

    // --- Zugriffsprotokoll --------------------------------------------------------------------------------------------

    @Angenommen("der Stand des Chat-Protokolls von «{word}» ist gemerkt")
    public void protokollMerken(String mandant) throws SQLException {
        try (Connection verbindung = datenbank.get().getConnection();
             PreparedStatement abfrage = verbindung.prepareStatement("select coalesce(max(id), 0) from "
                     + schema(mandant) + ".zugriff_protokoll");
             ResultSet zeile = abfrage.executeQuery()) {
            zeile.next();
            stand.put(mandant, zeile.getLong(1));
        }
    }

    @Dann("das Chat-Protokoll von «{word}» hat diese neuen Einträge:")
    public void protokoll(String mandant, DataTable tabelle) throws Exception {
        List<Map<String, String>> neu = new ArrayList<>();
        try (Connection verbindung = datenbank.get().getConnection();
             PreparedStatement abfrage = verbindung.prepareStatement("select benutzer, rolle, aktion, "
                     + "cast(dokument_id as text) as dokument, host(ip) as ip, cast(details as text) as details from "
                     + schema(mandant) + ".zugriff_protokoll where id > ? order by id")) {
            abfrage.setLong(1, stand.get(mandant));
            try (ResultSet zeile = abfrage.executeQuery()) {
                while (zeile.next()) {
                    Map<String, String> eintrag = new HashMap<>();
                    for (String spalte : List.of("benutzer", "rolle", "aktion", "dokument", "ip", "details")) {
                        eintrag.put(spalte, zeile.getString(spalte));
                    }
                    neu.add(eintrag);
                }
            }
        }
        List<Map<String, String>> erwartet = tabelle.asMaps();
        assertThat(neu).hasSize(erwartet.size());
        for (int i = 0; i < erwartet.size(); i++) {
            Map<String, String> e = erwartet.get(i);
            Map<String, String> ist = neu.get(i);
            String fall = "Eintrag " + (i + 1) + ": " + ist;
            assertThat(ist.get("benutzer")).as(fall).isEqualTo(Testanmeldung.sub(e.get("benutzer")));
            assertThat(ist.get("rolle")).as(fall).isEqualTo(e.get("rolle"));
            assertThat(ist.get("aktion")).as(fall).isEqualTo(e.get("aktion"));
            assertThat(ist.get("dokument")).as(fall).isEqualTo(e.get("dokument") == null ? null
                    : dokumentId(e.get("dokument")));
            if (e.containsKey("ip")) {
                assertThat(ist.get("ip")).as(fall).isEqualTo(e.get("ip"));
            }
            JsonNode details = JSON.readTree(ist.get("details"));
            details.fieldNames().forEachRemaining(n -> assertThat(DETAILS).as(fall).contains(n));
            for (String feld : DETAILS) {
                String wert = details.has(feld) ? details.get(feld).asText() : null;
                assertThat(wert).as(fall + " " + feld).isEqualTo(e.get(feld));
            }
        }
    }

    // --- Hilfen -------------------------------------------------------------------------------------------------------

    private String dokumentId(String name) {
        return switch (name) {
            case "{unbekannt}" -> unbekannt.toString();
            case "{ungültig}" -> "keine-uuid";
            default -> dokumente.get(name).id().toString();
        };
    }

    private String ersetzen(String wert) {
        Matcher m = PLATZHALTER.matcher(wert);
        StringBuilder text = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(text, dokumente.get(m.group(1)).id().toString());
        }
        m.appendTail(text);
        return text.toString();
    }

    private List<String> trefferNamen(JsonNode suche) {
        List<String> liste = new ArrayList<>();
        for (JsonNode t : suche.path("treffer")) {
            assertThat(t.path("link").asText()).isEqualTo("/dokument/" + t.path("id").asText());
            liste.add(namen.getOrDefault(UUID.fromString(t.path("id").asText()), "fremd:" + t.path("id").asText()));
        }
        return liste;
    }

    private static List<String> liste(String text) {
        return text == null || text.isBlank() ? List.of()
                : Arrays.stream(text.split(",")).map(String::strip).toList();
    }

    private static LocalDate datum(String text) {
        return text == null ? null : LocalDate.parse(text);
    }

    private static String schema(String mandant) {
        return new MandantCode(mandant).schema();
    }

    private static void ausfuehren(Connection verbindung, String sql, Object... werte) throws SQLException {
        try (PreparedStatement befehl = verbindung.prepareStatement(sql)) {
            for (int i = 0; i < werte.length; i++) {
                Object wert = werte[i];
                switch (wert) {
                    case null -> befehl.setNull(i + 1, Types.NULL);
                    case LocalDate d -> befehl.setObject(i + 1, d, Types.DATE);
                    case OffsetDateTime o -> befehl.setObject(i + 1, o, Types.TIMESTAMP_WITH_TIMEZONE);
                    case UUID u -> befehl.setObject(i + 1, u, Types.OTHER);
                    default -> befehl.setObject(i + 1, wert);
                }
            }
            befehl.executeUpdate();
        }
    }
}
