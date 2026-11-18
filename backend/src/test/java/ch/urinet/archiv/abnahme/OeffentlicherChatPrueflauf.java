package ch.urinet.archiv.abnahme;

import ch.urinet.archiv.abnahme.OeffentlicheChatPruefliste.ErwarteteQuelle;
import ch.urinet.archiv.abnahme.OeffentlicheChatPruefliste.Frage;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** Lauf und Bericht für die spätere Prüfliste des öffentlichen Chatbots (Spec 03, Aufgabe 11.3). */
public final class OeffentlicherChatPrueflauf {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final DateTimeFormatter ZEIT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    public record Quelle(String dokumentId, String titel, String reihe, String ausgabe, Integer jahr, String seite) {
    }

    public record Antwort(int status, String text, List<Quelle> quellen, boolean fertig, String hinweis,
                          String fehlermeldung) {
    }

    public record Ergebnis(Frage frage, Antwort antwort, boolean antwortRichtig, boolean quellenRichtig,
                           boolean hinweisRichtig, List<String> fehlendeKernwerte, List<String> fehlendeQuellen) {
    }

    public record Lauf(List<Ergebnis> ergebnisse, boolean abgebrochen, int abbruchStatus) {
    }

    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    private final URI basis;
    private final String token;
    private final String clientHash;
    private final Duration pause;
    private final Duration frist;

    public OeffentlicherChatPrueflauf(URI basis, String token, String clientHash, Duration pause, Duration frist) {
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("Kein Zugriffstoken");
        }
        if (clientHash == null || !clientHash.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Client-Hash muss aus 64 Kleinbuchstaben-Hexziffern bestehen");
        }
        this.basis = basis;
        this.token = token;
        this.clientHash = clientHash;
        this.pause = pause;
        this.frist = frist;
    }

    public Lauf laufen(List<Frage> fragen) throws InterruptedException {
        List<Ergebnis> ergebnisse = new ArrayList<>();
        boolean erste = true;
        for (Frage frage : fragen) {
            if (!erste) {
                Thread.sleep(pause.toMillis());
            }
            erste = false;
            Antwort antwort = fragen(frage.frage());
            if (antwort.status() == 401) {
                return new Lauf(List.copyOf(ergebnisse), true, 401);
            }
            ergebnisse.add(bewerten(frage, antwort));
        }
        return new Lauf(List.copyOf(ergebnisse), false, 0);
    }

    Antwort fragen(String frage) {
        String koerper = JSON.createObjectNode().put("frage", frage).toString();
        HttpRequest anfrage = HttpRequest.newBuilder(URI.create(basis.toString().replaceAll("/+$", "")
                        + "/public/v1/chat"))
                .header("Authorization", "Bearer " + token)
                .header("X-Archiv-Zugriff", "oeffentlich")
                .header("X-Archiv-Client", clientHash)
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .timeout(frist)
                .POST(HttpRequest.BodyPublishers.ofString(koerper, StandardCharsets.UTF_8)).build();
        try {
            HttpResponse<InputStream> r = client.send(anfrage, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream rumpf = r.body()) {
                if (r.statusCode() != 200) {
                    return new Antwort(r.statusCode(), "", List.of(), false, null, "HTTP " + r.statusCode());
                }
                return lesen(rumpf);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Antwort(0, "", List.of(), false, null, "unterbrochen");
        } catch (IOException | RuntimeException e) {
            return new Antwort(0, "", List.of(), false, null, e.getClass().getSimpleName());
        }
    }

    static Antwort lesen(InputStream rumpf) throws IOException {
        BufferedReader zeilen = new BufferedReader(new InputStreamReader(rumpf, StandardCharsets.UTF_8));
        StringBuilder text = new StringBuilder();
        List<Quelle> quellen = new ArrayList<>();
        boolean fertig = false;
        String hinweis = null;
        String fehler = null;
        String name = null;
        StringBuilder daten = new StringBuilder();
        String zeile;
        while ((zeile = zeilen.readLine()) != null) {
            if (zeile.isEmpty()) {
                if (name != null) {
                    JsonNode d = daten.isEmpty() ? JSON.createObjectNode() : JSON.readTree(daten.toString());
                    switch (name) {
                        case "text" -> text.append(d.path("delta").asString(""));
                        case "quellen" -> d.forEach(q -> quellen.add(quelle(q)));
                        case "fertig" -> {
                            fertig = true;
                            hinweis = d.path("hinweis").asString(null);
                        }
                        case "fehler" -> fehler = d.path("meldung").asString("Fehler");
                        default -> { // werkzeug: für die fachliche Bewertung ohne Bedeutung
                        }
                    }
                }
                name = null;
                daten.setLength(0);
            } else if (zeile.startsWith("event:")) {
                name = zeile.substring("event:".length()).strip();
            } else if (zeile.startsWith("data:")) {
                if (!daten.isEmpty()) {
                    daten.append('\n');
                }
                daten.append(zeile.substring("data:".length()).strip());
            }
        }
        return new Antwort(200, text.toString(), List.copyOf(quellen), fertig, hinweis, fehler);
    }

    private static Quelle quelle(JsonNode q) {
        JsonNode seite = q.path("fundstelle").path("seite");
        return new Quelle(q.path("dokumentId").asString(null), q.path("titel").asString(null),
                q.path("reihe").path("code").asString(null), q.path("ausgabe").asString(null),
                q.path("jahr").isIntegralNumber() ? q.path("jahr").asInt() : null,
                seite.isTextual() ? seite.asString() : null);
    }

    static Ergebnis bewerten(Frage frage, Antwort antwort) {
        String text = normal(antwort.text());
        List<String> fehlend = frage.kernwerte().stream().filter(k -> !text.contains(normal(k))).toList();
        List<String> fehlendeQuellen = new ArrayList<>();
        for (ErwarteteQuelle erwartet : frage.quellen()) {
            boolean da = antwort.quellen().stream().anyMatch(g -> quellePasst(erwartet, g));
            if (!da) {
                fehlendeQuellen.add("dokument " + erwartet.dokument());
            }
        }
        boolean statusOk = antwort.status() == 200 && antwort.fertig() && antwort.fehlermeldung() == null;
        boolean alleQuellenErwartet = antwort.quellen().size() == frage.quellen().size()
                && antwort.quellen().stream().allMatch(g -> frage.quellen().stream().anyMatch(e -> quellePasst(e, g)));
        boolean hinweisOk = antwort.hinweis() != null
                && antwort.hinweis().contains("automatisch aus Verbandsdokumenten")
                && antwort.hinweis().contains("Massgebend");
        return new Ergebnis(frage, antwort, statusOk && fehlend.isEmpty(), statusOk && alleQuellenErwartet,
                statusOk && hinweisOk, fehlend, List.copyOf(fehlendeQuellen));
    }

    private static boolean quellePasst(ErwarteteQuelle e, Quelle q) {
        return e.dokument().toString().equalsIgnoreCase(q.dokumentId()) && e.titel().equals(q.titel())
                && e.reihe().equals(q.reihe()) && e.ausgabe().equals(q.ausgabe())
                && Objects.equals(e.jahr(), q.jahr()) && Objects.equals(e.seite(), q.seite());
    }

    private static String normal(String s) {
        return s.toLowerCase(Locale.ROOT).replaceAll("['’‘`´]", "").replaceAll("[\\s  ]+", " ").strip();
    }

    /** Nur Prüflauf-Nummern und Ergebnisse; keine Frage, Antwort, Quelle, Client-ID oder Zugriffstoken. */
    public static String repoTeil(Lauf lauf) {
        StringBuilder b = new StringBuilder("| Nr. | Antwort | Quellen | Hinweis |\n| ---: | --- | --- | --- |\n");
        int antworten = 0;
        int quellen = 0;
        int hinweise = 0;
        for (Ergebnis e : lauf.ergebnisse()) {
            antworten += e.antwortRichtig() ? 1 : 0;
            quellen += e.quellenRichtig() ? 1 : 0;
            hinweise += e.hinweisRichtig() ? 1 : 0;
            b.append("| ").append(e.frage().nr()).append(" | ").append(ja(e.antwortRichtig())).append(" | ")
                    .append(ja(e.quellenRichtig())).append(" | ").append(ja(e.hinweisRichtig())).append(" |\n");
        }
        b.append("\nAntworten: ").append(antworten).append(" von ").append(lauf.ergebnisse().size())
                .append("; Quellen: ").append(quellen).append("; Hinweise: ").append(hinweise).append(".\n");
        if (lauf.abgebrochen()) {
            b.append("Lauf nach ").append(lauf.ergebnisse().size()).append(" Fragen abgebrochen: HTTP ")
                    .append(lauf.abbruchStatus()).append(".\n");
        }
        return b.toString();
    }

    /** Vollständiger Bericht; nur im externen Golden-Verzeichnis ablegen. */
    public static String bericht(Lauf lauf) {
        StringBuilder b = new StringBuilder("# Öffentlicher Chatbot: Prüflauf (ausserhalb des Repositorys)\n\n");
        for (Ergebnis e : lauf.ergebnisse()) {
            b.append("## Frage ").append(e.frage().nr()).append("\n\n")
                    .append("Frage: ").append(e.frage().frage()).append("\n\n")
                    .append("Antwort: ").append(e.antwort().text()).append("\n\n")
                    .append("Hinweis: ").append(e.antwort().hinweis()).append("\n\n")
                    .append("Quellen: ").append(e.antwort().quellen()).append("\n\n")
                    .append("Vorschlag: Antwort ").append(ja(e.antwortRichtig())).append(", Quellen ")
                    .append(ja(e.quellenRichtig())).append(", Hinweis ").append(ja(e.hinweisRichtig())).append("\n\n");
        }
        return b.append("---\n\n").append(repoTeil(lauf)).toString();
    }

    public static Map<String, Path> schreiben(Lauf lauf, Path verzeichnis) throws IOException {
        if (!Files.isDirectory(verzeichnis)) {
            throw new IOException("Berichtsverzeichnis fehlt");
        }
        String zeit = ZEIT.format(LocalDateTime.now());
        Path voll = verzeichnis.resolve("oeffentlicher-chat-prueflauf-" + zeit + ".md");
        Path repo = verzeichnis.resolve("oeffentlicher-chat-prueflauf-" + zeit + "-repo-teil.md");
        ablegen(voll, bericht(lauf));
        ablegen(repo, repoTeil(lauf));
        return Map.of("bericht", voll, "repoTeil", repo);
    }

    private static void ablegen(Path datei, String inhalt) throws IOException {
        try {
            Files.createFile(datei, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        } catch (UnsupportedOperationException e) {
            Files.createFile(datei);
        }
        Files.writeString(datei, inhalt, StandardCharsets.UTF_8);
    }

    private static String ja(boolean wert) {
        return wert ? "ja" : "nein";
    }
}
