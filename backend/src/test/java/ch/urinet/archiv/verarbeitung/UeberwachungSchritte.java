package ch.urinet.archiv.verarbeitung;

import ch.urinet.archiv.dokument.Testdokumente;
import ch.urinet.archiv.mandant.application.MandantRegister;
import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.sicherung.application.SicherungExportSpeicher;
import ch.urinet.archiv.sicherung.domain.SicherungsArt;
import ch.urinet.archiv.verarbeitung.adapter.in.kennzahlen.MicrometerKennzahlen;
import ch.urinet.archiv.verarbeitung.application.Ueberwachung;
import io.agroal.api.AgroalDataSource;
import io.cucumber.java.After;
import io.cucumber.java.de.Angenommen;
import io.cucumber.java.de.Dann;
import io.cucumber.java.de.Wenn;
import io.restassured.response.Response;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Schritte zu Req 16.4, 16.5 und 16.7 (Aufgabe 5.6): Überwachung mit Alarm-Mail an Mailpit, Kennzahlen unter
 * {@code /q/metrics} und die Traefik-Router aus {@code deploy/infomaniak/compose.yml}. Nur synthetische Testdokumente.
 */
public class UeberwachungSchritte {

    private static final Path COMPOSE = Path.of("..").toAbsolutePath().normalize()
            .resolve("deploy/infomaniak/compose.yml");
    /** Positive Pfad-Bedingung einer Traefik-Regel (nicht mit «!» verneint). */
    private static final Pattern PFAD = Pattern.compile("(?<!!)\\b(Path|PathPrefix)\\(`([^`]*)`\\)");

    private record Ort(String mandant, UUID id) {
    }

    @Inject
    Instance<AgroalDataSource> datenbank;

    @Inject
    Ueberwachung ueberwachung;

    @Inject
    MicrometerKennzahlen kennzahlen;

    @Inject
    MandantRegister mandanten;

    @Inject
    SicherungExportSpeicher sicherungen;

    // nur über Compose Dev Services gesetzt; als Pflichtwert bräche er die Starttests ohne Dev Services (prod)
    @ConfigProperty(name = "archiv.entwicklung.mailpit-port")
    Optional<Integer> mailpitPort;

    @ConfigProperty(name = "archiv.ueberwachung.betrieb-mandant")
    String sicherungsMandant;

    @ConfigProperty(name = "archiv.ueberwachung.sicherung-aktiv")
    Boolean sicherungAktiv;

    @ConfigProperty(name = "archiv.benachrichtigung.empfaenger.urinet")
    Optional<String> sicherungsEmpfaenger;

    private final Map<String, Ort> dokumente = new LinkedHashMap<>();
    private String metriken;
    private List<String> aktiveVorher;
    private boolean sicherungsSzenarioVorbereitet;

    private record SicherungsMail(String betreff, String text) {
    }

    @After
    public void aufraeumen() throws SQLException {
        Map<String, List<UUID>> jeMandant = new HashMap<>();
        for (Ort ort : dokumente.values()) {
            jeMandant.computeIfAbsent(ort.mandant(), m -> new ArrayList<>()).add(ort.id());
            ausfuehren("delete from " + schema(ort) + ".alarm where starts_with(schluessel, ?)",
                    "dokument:" + ort.id() + ":");
        }
        for (Map.Entry<String, List<UUID>> eintrag : jeMandant.entrySet()) {
            Testdokumente.loeschen(datenbank.get(), eintrag.getKey(), eintrag.getValue());
        }
        dokumente.clear();
        if (sicherungsSzenarioVorbereitet) {
            String schema = new MandantCode(sicherungsMandant).schema();
            ausfuehren("delete from " + schema + ".alarm where starts_with(schluessel, ?)", "sicherung:");
            ausfuehren("update public.mandant set aktiv = false");
            for (String code : aktiveVorher) {
                ausfuehren("update public.mandant set aktiv = true where code = ?", code);
            }
            aktiveVorher = null;
            sicherungsSzenarioVorbereitet = false;
        }
    }

    @Angenommen("im Mandanten «{word}» steht das Dokument «{word}» seit {int} Minuten im Status «{word}»")
    public void steht(String mandant, String name, int minuten, String status) throws SQLException {
        UUID id = Testdokumente.anlegen(datenbank.get(),
                new Testdokumente.Angaben(mandant, null, "NORMAL", false, "probe", null, status));
        dokumente.put(name, new Ort(mandant, id));
        zurueckdatieren(name, minuten);
    }

    @Angenommen("das Dokument «{word}» wechselt in den Status «{word}»")
    public void wechselt(String name, String status) throws SQLException {
        Ort ort = ort(name);
        ausfuehren("update " + schema(ort) + ".dokument set status = ? where id = ?", status, ort.id());
    }

    @Angenommen("das Dokument «{word}» steht seit {int} Minuten in diesem Status")
    public void zurueckdatieren(String name, int minuten) throws SQLException {
        Ort ort = ort(name);
        // ändert status nicht, der Trigger von Changeset 012 setzt status_seit darum nicht neu
        ausfuehren("update " + schema(ort) + ".dokument set status_seit = clock_timestamp() - ? * interval '1 minute' "
                + "where id = ?", minuten, ort.id());
    }

    @Wenn("die Überwachung läuft")
    public void laeuft() {
        ueberwachung.pruefen();
    }

    @Angenommen("die Sicherungsüberwachung ist aktiviert und es gibt keine Exporte für den Betriebsmandanten")
    public void sicherungsueberwachungVorbereiten() throws SQLException {
        assertThat(sicherungAktiv).isTrue();
        MandantCode code = new MandantCode(sicherungsMandant);
        aktiveVorher = aktiveMandanten();
        assertThat(mandanten.existiert(code)).isTrue();
        sicherungsSzenarioVorbereitet = true;
        ausfuehren("update public.mandant set aktiv = false");
        ausfuehren("update public.mandant set aktiv = true where code = ?", sicherungsMandant);
        ausfuehren("delete from " + code.schema() + ".alarm where starts_with(schluessel, ?)", "sicherung:");
    }

    @Wenn("ein globaler DB-Export festgehalten wird")
    public void globalerDatenbankExport() {
        sicherungen.festhalten(SicherungsArt.DB, Optional.empty(),
                UUID.randomUUID().toString().replace("-", "").repeat(2).substring(0, 64));
    }

    @Dann("hat der Inhaber genau sechs Sicherungs-Mails erhalten, nur Codes und Status")
    public void genauSechsSicherungsMails() {
        sicherungsMailsPruefen();
    }

    @Dann("hat der Inhaber weiterhin genau sechs Sicherungs-Mails erhalten")
    public void weiterhinSechsSicherungsMails() {
        sicherungsMailsPruefen();
    }

    @Dann("ist der fehlende DB-Sicherungsalarm erledigt")
    public void datenbankAlarmErledigt() throws SQLException {
        String sql = "select erledigt from " + new MandantCode(sicherungsMandant).schema()
                + ".alarm where schluessel = ?";
        try (Connection verbindung = datenbank.get().getConnection();
             PreparedStatement abfrage = verbindung.prepareStatement(sql)) {
            abfrage.setString(1, "sicherung:db:FEHLT");
            try (var ergebnis = abfrage.executeQuery()) {
                assertThat(ergebnis.next()).isTrue();
                assertThat(ergebnis.getObject(1)).isNotNull();
            }
        }
    }

    @Wenn("die Kennzahlen erhoben werden")
    public void erheben() {
        kennzahlen.aktualisieren();
        Response antwort = given().when().get("/q/metrics");
        assertThat(antwort.statusCode()).isEqualTo(200);
        metriken = antwort.asString();
    }

    @Dann("hat der Inhaber zu «{word}» Alarm-Mails zu den Status «{word}» erhalten, die nur ID und Status nennen")
    public void alarmMails(String name, String status) {
        Ort ort = ort(name);
        List<String> ids = alarme(ort);
        String mailpit = "http://localhost:" + mailpitPort.orElseThrow();
        List<String> gemeldet = new ArrayList<>();
        for (String id : ids) {
            Response nachricht = given().baseUri(mailpit).when().get("/api/v1/message/" + id);
            assertThat(nachricht.jsonPath().<String>getList("To.Address")).containsExactly("inhaber@example.invalid");
            String betreff = nachricht.jsonPath().getString("Subject");
            String text = nachricht.jsonPath().getString("Text");
            gemeldet.add(betreff.substring(betreff.lastIndexOf(' ') + 1));
            assertThat(betreff + "\n" + text).contains(ort.id().toString()).contains("Mandant: " + ort.mandant())
                    .doesNotContain("Testdokument");
        }
        assertThat(gemeldet).containsExactlyInAnyOrder(status.split(","));
    }

    @Dann("hat der Inhaber zu «{word}» keine Alarm-Mail erhalten")
    public void keineAlarmMail(String name) {
        assertThat(alarme(ort(name))).isEmpty();
    }

    @Dann("nennen die Kennzahlen für «{word}» und «{word}» Aufträge, Dokumente je Status und den Eingangskorb")
    public void kennzahlenVorhanden(String a, String b) {
        for (String mandant : List.of(a, b)) {
            for (String status : List.of("OFFEN", "LAEUFT", "FEHLER")) {
                assertThat(wert("archiv_auftraege", mandant, status)).isNotNull();
            }
            for (String status : List.of("NEU", "VERARBEITUNG", "EINGANGSKORB", "ARCHIVIERUNG", "ARCHIVIERUNG_FEHLER",
                    "FEHLER")) {
                assertThat(wert("archiv_dokumente", mandant, status)).isNotNull();
            }
            assertThat(wert("archiv_eingangskorb", mandant, null)).isNotNull();
        }
    }

    @Dann("zählt der Eingangskorb von «{word}» mindestens {int} Dokument(e)")
    public void eingangskorb(String mandant, int mindestens) {
        assertThat(wert("archiv_eingangskorb", mandant, null)).isGreaterThanOrEqualTo(mindestens);
    }

    @Dann("leitet Traefik von aussen unter «\\/q\\/» nur «{word}» weiter")
    public void traefik(String oeffentlich) throws IOException {
        List<String> regeln = new ArrayList<>();
        Map<String, Object> dienste = karte(karte(new Yaml(new SafeConstructor(new LoaderOptions()))
                .load(Files.readString(COMPOSE, StandardCharsets.UTF_8))).get("services"));
        dienste.values().forEach(d -> {
            Object labels = karte(d).get("labels");
            if (labels instanceof Map<?, ?> karte) {
                karte.forEach((k, v) -> {
                    if (String.valueOf(k).matches("traefik\\.http\\.routers\\.[^.]+\\.rule")) {
                        regeln.add(String.valueOf(v));
                    }
                });
            }
        });
        assertThat(regeln).isNotEmpty();
        List<String> offen = new ArrayList<>();
        for (String regel : regeln) {
            Matcher pfad = PFAD.matcher(regel);
            boolean eingeschraenkt = false;
            while (pfad.find()) {
                eingeschraenkt = true;
                boolean unterQ = pfad.group(2).startsWith("/q");
                if (unterQ && !("Path".equals(pfad.group(1)) && oeffentlich.equals(pfad.group(2)))) {
                    offen.add(regel);
                }
            }
            if (!eingeschraenkt && !regel.contains("!PathPrefix(`/q/`)")) {
                offen.add(regel);
            }
        }
        assertThat(offen).as("Traefik-Regeln, die mehr als %s unter /q/ öffentlich machen", oeffentlich).isEmpty();
        assertThat(regeln).anyMatch(r -> r.contains("Path(`" + oeffentlich + "`)"));
    }

    private Double wert(String name, String mandant, String status) {
        String tags = "mandant=\"" + mandant + "\"" + (status == null ? "" : ",status=\"" + status + "\"");
        Matcher zeile = Pattern.compile("(?m)^" + name + "\\{" + Pattern.quote(tags) + ",?} (\\S+)$")
                .matcher(metriken);
        return zeile.find() ? Double.valueOf(zeile.group(1)) : null;
    }

    private List<String> alarme(Ort ort) {
        String mailpit = "http://localhost:" + mailpitPort.orElseThrow();
        Response nachrichten = given().baseUri(mailpit)
                .queryParam("query", "subject:\"" + ort.id() + "\"").when().get("/api/v1/search");
        assertThat(nachrichten.statusCode()).isEqualTo(200);
        List<Map<String, Object>> liste = nachrichten.jsonPath().getList("messages");
        return liste.stream().filter(m -> String.valueOf(m.get("Subject")).contains(" hängt in "))
                .map(m -> String.valueOf(m.get("ID"))).toList();
    }

    private void sicherungsMailsPruefen() {
        List<SicherungsMail> mails = sicherungsMails();
        assertThat(mails).hasSize(6);
        List<String> gegenstaende = List.of("db", "keycloak", "realm", "schluessel",
                "bucket-" + sicherungsMandant, "inventar-" + sicherungsMandant);
        assertThat(mails).extracting(SicherungsMail::betreff).containsExactlyInAnyOrderElementsOf(gegenstaende.stream()
                .map(gegenstand -> "UriNet Archiv: Sicherung " + gegenstand + " FEHLT").toList());
        for (SicherungsMail mail : mails) {
            assertThat(mail.text()).contains("Mandant: " + sicherungsMandant, "FEHLT")
                    .doesNotContain("SHA-256", "sha256", "archiv/", "/manifest/");
        }
    }

    private List<SicherungsMail> sicherungsMails() {
        String mailpit = "http://localhost:" + mailpitPort.orElseThrow();
        String empfaengerErwartet = sicherungsEmpfaenger.orElseThrow();
        Response suchergebnis = given().baseUri(mailpit)
                .queryParam("query", "subject:\"UriNet Archiv: Sicherung\"")
                .when().get("/api/v1/search");
        assertThat(suchergebnis.statusCode()).isEqualTo(200);
        List<Map<String, Object>> nachrichten = suchergebnis.jsonPath().getList("messages");
        List<SicherungsMail> mails = new ArrayList<>();
        for (Map<String, Object> nachricht : nachrichten) {
            if (!String.valueOf(nachricht.get("Subject")).startsWith("UriNet Archiv: Sicherung")) {
                continue;
            }
            Response detail = given().baseUri(mailpit).when().get("/api/v1/message/" + nachricht.get("ID"));
            assertThat(detail.statusCode()).isEqualTo(200);
            List<String> empfaenger = detail.jsonPath().getList("To.Address");
            if (!empfaenger.contains(empfaengerErwartet)) {
                continue;
            }
            String text = detail.jsonPath().getString("Text");
            if (text.contains("Mandant: " + sicherungsMandant)) {
                assertThat(empfaenger).containsExactly(empfaengerErwartet);
                mails.add(new SicherungsMail(detail.jsonPath().getString("Subject"), text));
            }
        }
        return mails;
    }

    private List<String> aktiveMandanten() throws SQLException {
        try (Connection verbindung = datenbank.get().getConnection();
             PreparedStatement abfrage = verbindung.prepareStatement(
                     "select code from public.mandant where aktiv order by code");
             var ergebnis = abfrage.executeQuery()) {
            List<String> codes = new ArrayList<>();
            while (ergebnis.next()) {
                codes.add(ergebnis.getString(1));
            }
            return List.copyOf(codes);
        }
    }

    private Ort ort(String name) {
        return Optional.ofNullable(dokumente.get(name)).orElseThrow(() -> new IllegalStateException(name));
    }

    private static String schema(Ort ort) {
        return new MandantCode(ort.mandant()).schema();
    }

    private void ausfuehren(String sql, Object... werte) throws SQLException {
        try (Connection verbindung = datenbank.get().getConnection();
             PreparedStatement befehl = verbindung.prepareStatement(sql)) {
            for (int i = 0; i < werte.length; i++) {
                befehl.setObject(i + 1, werte[i]);
            }
            befehl.executeUpdate();
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> karte(Object wert) {
        assertThat(wert).isInstanceOf(Map.class);
        return (Map<String, Object>) wert;
    }
}
