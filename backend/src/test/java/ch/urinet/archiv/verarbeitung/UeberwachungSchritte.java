package ch.urinet.archiv.verarbeitung;

import ch.urinet.archiv.dokument.Testdokumente;
import ch.urinet.archiv.mandant.domain.MandantCode;
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

    // nur über Compose Dev Services gesetzt; als Pflichtwert bräche er die Starttests ohne Dev Services (prod)
    @ConfigProperty(name = "archiv.entwicklung.mailpit-port")
    Optional<Integer> mailpitPort;

    private final Map<String, Ort> dokumente = new LinkedHashMap<>();
    private String metriken;

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
