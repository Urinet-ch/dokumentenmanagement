package ch.urinet.archiv.wissen;

import ch.urinet.archiv.dokument.Testdokumente;
import ch.urinet.archiv.gemeinsam.Testanmeldung;
import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.sicherheit.ProblemDetails;
import io.agroal.api.AgroalDataSource;
import io.cucumber.java.After;
import io.cucumber.java.de.Angenommen;
import io.cucumber.java.de.Dann;
import io.cucumber.java.de.Wenn;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Szenarien zum Start eines Ingests (Spec 04, Aufgabe 14.1a). Nur synthetische Inhalte (Spec 01, Req 16.15). Der
 * Handler {@code WISSEN_INGEST} kommt erst mit 14.1b; ein Start, der alle Prüfungen besteht, endet darum mit dem
 * Zwischenstand-Hinweis.
 */
public class WissenIngestSchritte {

    @Inject
    Instance<AgroalDataSource> datenbank;

    private record Ort(String mandant, UUID id) {
    }

    private record Einstellung(boolean vorhanden, Integer budget, Integer monatsbudget) {
    }

    private final Map<String, Ort> dokumente = new HashMap<>();
    private final Map<String, Object[]> vorher = new HashMap<>();
    private final Map<String, Einstellung> einstellungen = new HashMap<>();
    private final List<Long> verbrauch = new ArrayList<>();
    private Response antwort;

    @After
    public void aufraeumen() throws SQLException {
        for (Ort ort : dokumente.values()) {
            ausfuehren("delete from public.auftrag where typ = 'WISSEN_INGEST' and nutzlast ->> 'entwurfId' in "
                    + "(select id::text from " + schema(ort.mandant()) + ".wissens_entwurf where dokument_id = ?)",
                    ort.id());
        }
        Map<String, List<UUID>> jeMandant = new HashMap<>();
        dokumente.values().forEach(o -> jeMandant.computeIfAbsent(o.mandant(), m -> new ArrayList<>()).add(o.id()));
        for (Map.Entry<String, List<UUID>> eintrag : jeMandant.entrySet()) {
            Testdokumente.loeschen(datenbank.get(), eintrag.getKey(), eintrag.getValue());
        }
        for (Long id : verbrauch) {
            ausfuehren("delete from " + schema("urinet") + ".ki_verbrauch where id = ?", id);
        }
        for (Map.Entry<String, Einstellung> e : einstellungen.entrySet()) {
            if (e.getValue().vorhanden()) {
                ausfuehren("update " + schema(e.getKey()) + ".wissens_einstellung set budget_tokens = ?, "
                        + "monatsbudget_tokens = ? where id = 1", e.getValue().budget(), e.getValue().monatsbudget());
            } else {
                ausfuehren("delete from " + schema(e.getKey()) + ".wissens_einstellung where id = 1");
            }
        }
        dokumente.clear();
        vorher.clear();
        einstellungen.clear();
        verbrauch.clear();
    }

    @Angenommen("im Mandanten «{word}» liegt ein archiviertes Dokument «{word}» der Art «{word}»")
    public void dokumentAnlegen(String mandant, String name, String art) throws SQLException {
        quelleAnlegen(mandant, name, art, "NORMAL", 2);
    }

    @Angenommen("im Mandanten «{word}» liegt die archivierte Wissensquelle «{word}» mit Vertraulichkeit «{word}» "
            + "und {int} Seiten")
    public void wissensquelle(String mandant, String name, String vertraulichkeit, int seiten) throws SQLException {
        quelleAnlegen(mandant, name, "WISSENSQUELLE", vertraulichkeit, seiten);
    }

    @Angenommen("die Wissensquelle «{word}» liegt im Status «{word}»")
    public void status(String name, String status) throws SQLException {
        Ort ort = ort(name);
        ausfuehren("update " + schema(ort.mandant()) + ".dokument set status = ? where id = ?", status, ort.id());
        merken(name);
    }

    @Angenommen("für «{word}» wartet bereits ein Ingest")
    public void wartenderIngest(String name) throws SQLException {
        Ort ort = ort(name);
        ausfuehren("insert into " + schema(ort.mandant()) + ".wissens_entwurf (id, anlass, dokument_id, status, "
                + "schritt, ki_modell, erstellt_von, erstellt) values (?, 'INGEST', ?, 'WARTEND', 'KERNAUSSAGEN', "
                + "'test-modell', 'probe', clock_timestamp())", UUID.randomUUID(), ort.id());
    }

    @Angenommen("die Wissensbasis von «{word}» hat das Ingest-Budget {int} und das Monatsbudget {int} Tokens")
    public void budget(String mandant, int budget, int monatsbudget) throws SQLException {
        if (!einstellungen.containsKey(mandant)) {
            Map<String, Object> alt = zeile("select budget_tokens, monatsbudget_tokens from " + schema(mandant)
                    + ".wissens_einstellung where id = 1");
            einstellungen.put(mandant, new Einstellung(!alt.isEmpty(), (Integer) alt.get("budget_tokens"),
                    (Integer) alt.get("monatsbudget_tokens")));
        }
        ausfuehren("insert into " + schema(mandant) + ".wissens_einstellung (id, budget_tokens, monatsbudget_tokens) "
                + "values (1, ?, ?) on conflict (id) do update set budget_tokens = excluded.budget_tokens, "
                + "monatsbudget_tokens = excluded.monatsbudget_tokens", budget, monatsbudget);
    }

    @Angenommen("die Wissensbasis von «urinet» hat diesen Monat {int} Tokens verbraucht")
    public void verbraucht(int tokens) throws SQLException {
        verbrauch.add(((Number) zeile("insert into " + schema("urinet") + ".ki_verbrauch (zweck, modell, tokens_ein, "
                + "tokens_aus) values ('WISSEN_INGEST', 'test-modell', ?, 0) returning id", tokens).get("id"))
                .longValue());
    }

    @Wenn("«{word}» den Ingest für «{word}» startet")
    public void ingestStarten(String benutzer, String name) throws SQLException {
        ingestStarten(benutzer, name, Map.of());
    }

    @Wenn("«{word}» den Ingest für «{word}» mit den Seiten {int} bis {int} startet")
    public void ingestMitSeiten(String benutzer, String name, int von, int bis) throws SQLException {
        ingestStarten(benutzer, name, Map.of("seiteVon", von, "seiteBis", bis));
    }

    @Wenn("«{word}» den Ingest für ein unbekanntes Dokument startet")
    public void unbekannt(String benutzer) {
        antwort = given().auth().oauth2(Testanmeldung.token(benutzer)).contentType(ContentType.JSON)
                .body(Map.of()).when().post("/api/v1/dokumente/" + UUID.randomUUID() + "/wissen-ingest");
    }

    @Dann("antwortet der Ingest-Endpunkt mit Status {int} und Fehlercode «{word}»")
    public void fehlercode(int status, String code) {
        assertThat(ProblemDetails.pruefen(antwort, status, "felder")).as(antwort.asString()).isEqualTo(code);
    }

    @Dann("antwortet der Ingest-Endpunkt mit Status {int}")
    public void status(int status) {
        assertThat(antwort.statusCode()).as(antwort.asString()).isEqualTo(status);
    }

    @Dann("antwortet der Ingest-Endpunkt mit Status 202, Entwurfsstatus «{word}» und dem Hinweis {string}")
    public void angenommen(String status, String hinweis) {
        assertThat(antwort.statusCode()).as(antwort.asString()).isEqualTo(202);
        assertThat(antwort.<String>path("status")).isEqualTo(status);
        assertThat(antwort.<String>path("hinweis")).isEqualTo(hinweis);
    }

    @Dann("antwortet der Ingest-Endpunkt mit Status 202, Entwurfsstatus «{word}» und einem Hinweis, der mit "
            + "{string} beginnt")
    public void angenommenMitBeginn(String status, String beginn) {
        assertThat(antwort.statusCode()).as(antwort.asString()).isEqualTo(202);
        assertThat(antwort.<String>path("status")).isEqualTo(status);
        assertThat(antwort.<String>path("hinweis")).startsWith(beginn);
    }

    @Dann("hat «{word}» genau einen Ingest-Entwurf im Status «{word}» mit dem Hinweis der Antwort")
    public void entwurf(String name, String status) throws SQLException {
        Ort ort = ort(name);
        Map<String, Object> z = zeile("select count(*) as anzahl, max(id::text) as id, max(status) as status, "
                + "max(hinweis) as hinweis from " + schema(ort.mandant()) + ".wissens_entwurf where dokument_id = ? "
                + "and anlass = 'INGEST' and status = ?", ort.id(), status);
        assertThat(((Number) z.get("anzahl")).intValue()).isEqualTo(1);
        assertThat(z.get("id")).isEqualTo(antwort.<String>path("entwurfId"));
        assertThat(z.get("hinweis")).isEqualTo(antwort.<String>path("hinweis"));
    }

    @Dann("hat «{word}» keinen neuen Ingest-Entwurf")
    public void keinNeuerEntwurf(String name) throws SQLException {
        Ort ort = ort(name);
        assertThat(((Number) zeile("select count(*) as anzahl from " + schema(ort.mandant())
                + ".wissens_entwurf where dokument_id = ? and erstellt_von <> 'probe'", ort.id()).get("anzahl"))
                .intValue()).isZero();
    }

    @Dann("ist für «{word}» kein Auftrag «WISSEN_INGEST» eingereiht")
    public void keinAuftrag(String name) throws SQLException {
        Ort ort = ort(name);
        assertThat(((Number) zeile("select count(*) as anzahl from public.auftrag where typ = 'WISSEN_INGEST' "
                + "and nutzlast ->> 'entwurfId' in (select id::text from " + schema(ort.mandant())
                + ".wissens_entwurf where dokument_id = ?)", ort.id()).get("anzahl")).intValue()).isZero();
    }

    @Dann("hält das Zugriffsprotokoll den Ingest-Start von «{word}» für «{word}» fest")
    public void protokoll(String benutzer, String name) throws SQLException {
        Ort ort = ort(name);
        assertThat(((Number) zeile("select count(*) as anzahl from " + schema(ort.mandant())
                + ".zugriff_protokoll where aktion = 'WISSEN_INGEST' and dokument_id = ? and benutzer = ?",
                ort.id(), Testanmeldung.sub(benutzer)).get("anzahl")).intValue()).isEqualTo(1);
    }

    @Dann("sind Hinweis und Version von «{word}» unverändert")
    public void unveraendert(String name) throws SQLException {
        Ort ort = ort(name);
        Map<String, Object> jetzt = zeile("select hinweis, version, (select count(*) from " + schema(ort.mandant())
                + ".dokument_version v where v.dokument_id = d.id) as versionen from " + schema(ort.mandant())
                + ".dokument d where id = ?", ort.id());
        Object[] alt = vorher.get(name);
        assertThat(jetzt.get("hinweis")).isEqualTo(alt[0]);
        assertThat(jetzt.get("version")).isEqualTo(alt[1]);
        assertThat(jetzt.get("versionen")).isEqualTo(alt[2]);
    }

    private void ingestStarten(String benutzer, String name, Map<String, Object> angaben) throws SQLException {
        merken(name);
        antwort = given().auth().oauth2(Testanmeldung.token(benutzer)).contentType(ContentType.JSON)
                .body(angaben).when().post("/api/v1/dokumente/" + ort(name).id() + "/wissen-ingest");
    }

    private void quelleAnlegen(String mandant, String name, String art, String vertraulichkeit, int seiten)
            throws SQLException {
        UUID id = Testdokumente.anlegen(datenbank.get(),
                new Testdokumente.Angaben(mandant, art, vertraulichkeit, true, "probe", null, "ARCHIVIERT"));
        dokumente.put(name, new Ort(mandant, id));
        StringBuilder volltext = new StringBuilder();
        for (int i = 1; i <= seiten; i++) {
            volltext.append("Synthetische Seite ").append(i).append('\f');
        }
        ausfuehren("update " + schema(mandant) + ".dokument set volltext = ?, seiten = ?, s3_pdfa = ?, "
                + "s3_version_pdfa = 'v1', pdfa_fehlt = false where id = ?", volltext.toString(), seiten,
                "archiv/2026/" + id + "/pdfa.pdf", id);
    }

    private void merken(String name) throws SQLException {
        Ort ort = ort(name);
        Map<String, Object> z = zeile("select hinweis, version, (select count(*) from " + schema(ort.mandant())
                + ".dokument_version v where v.dokument_id = d.id) as versionen from " + schema(ort.mandant())
                + ".dokument d where id = ?", ort.id());
        vorher.put(name, new Object[]{z.get("hinweis"), z.get("version"), z.get("versionen")});
    }

    private Ort ort(String name) {
        Ort ort = dokumente.get(name);
        if (ort == null) {
            throw new IllegalArgumentException("Unbekanntes Testdokument: " + name);
        }
        return ort;
    }

    private Map<String, Object> zeile(String sql, Object... werte) throws SQLException {
        try (Connection verbindung = datenbank.get().getConnection();
             PreparedStatement abfrage = verbindung.prepareStatement(sql)) {
            for (int i = 0; i < werte.length; i++) {
                abfrage.setObject(i + 1, werte[i]);
            }
            try (ResultSet z = abfrage.executeQuery()) {
                Map<String, Object> zeile = new HashMap<>();
                if (z.next()) {
                    for (int i = 1; i <= z.getMetaData().getColumnCount(); i++) {
                        zeile.put(z.getMetaData().getColumnLabel(i), z.getObject(i));
                    }
                }
                return zeile;
            }
        }
    }

    private void ausfuehren(String sql, Object... werte) throws SQLException {
        try (Connection verbindung = datenbank.get().getConnection();
             PreparedStatement befehl = verbindung.prepareStatement(sql)) {
            for (int i = 0; i < werte.length; i++) {
                befehl.setObject(i + 1, werte[i]);
            }
            befehl.execute();
        }
    }

    private static String schema(String mandant) {
        return new MandantCode(mandant).schema();
    }
}
