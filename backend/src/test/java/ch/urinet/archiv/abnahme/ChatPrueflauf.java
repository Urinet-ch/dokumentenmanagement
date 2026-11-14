package ch.urinet.archiv.abnahme;

import ch.urinet.archiv.abnahme.Pruefliste.ErwarteteQuelle;
import ch.urinet.archiv.abnahme.Pruefliste.Frage;
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
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Lauf der Chat-Prüfliste (Spec 02, Aufgabe 9.1, Req 13): stellt jede Frage an {@code POST <basis>/api/v1/chat}, liest
 * den SSE-Strom ({@code werkzeug}, {@code text}, {@code quellen}, {@code fertig}, {@code fehler}) und schlägt je Frage
 * «richtig/falsch» und «Quelle ja/nein» vor. Die Bewertung bleibt halbautomatisch: Fabian bestätigt jede Antwort im
 * Bericht (Req 13.2, «Nachweis: manuell [Fabian]»).
 *
 * <p>Zwei Ausgaben: der <b>vollständige Bericht</b> (Fragen, Antworten, Quellen) nur unter {@code ARCHIV_GOLDEN_DIR},
 * und der <b>Repo-Teil</b> nur mit Fragen-Nr. und Ergebnis, ohne Inhalte, für {@code docs/abnahme/phase2.md}. Das
 * Zugriffstoken geht nur in den Authorization-Header; weder Bericht noch Fehlermeldung enthalten es.
 */
public final class ChatPrueflauf {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final DateTimeFormatter ZEIT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    static final long KEIN_TEXT = -1;

    /** Quelle, wie der Chat sie im Ereignis {@code quellen} liefert: Dokument ({@code id}) oder Datenstand. */
    public record GelieferteQuelle(String id, String titel, String werkzeug, Integer belege) {
    }

    /** Antwort des Chats auf eine Frage; {@code status} 200 mit Strom, sonst nur der HTTP-Status. */
    public record Antwort(int status, String text, List<GelieferteQuelle> quellen, boolean fertig, String fehlermeldung,
                          long ersterTextMs) {
    }

    /** Vorschlag je Frage; {@code fehlendeKernwerte} nur im vollständigen Bericht. */
    public record Ergebnis(Frage frage, Antwort antwort, boolean richtig, boolean quelle, List<String> fehlendeKernwerte,
                           List<String> fehlendeQuellen) {
    }

    public record Lauf(List<Ergebnis> ergebnisse, boolean abgebrochen, int abbruchStatus) {
    }

    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
    private final URI basis;
    private final String token;
    private final Duration pause;
    private final Duration frist;

    /**
     * @param basis Basis-URL ohne Pfad (der Lauf hängt {@code /api/v1/chat} an)
     * @param token Access-Token eines {@code inhaber}; wird nie ausgegeben
     * @param pause Pause zwischen den Fragen gegen das Rate-Limit (Req 5.5)
     * @param frist Höchstdauer je Frage
     */
    public ChatPrueflauf(URI basis, String token, Duration pause, Duration frist) {
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("Kein Zugriffstoken");
        }
        this.basis = basis;
        this.token = token;
        this.pause = pause;
        this.frist = frist;
    }

    /** Stellt die Fragen der Reihe nach; ein 401 bricht den Lauf ab (Token abgelaufen), alles andere zählt als falsch. */
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
        HttpRequest anfrage = HttpRequest.newBuilder(URI.create(basis.toString().replaceAll("/+$", "") + "/api/v1/chat"))
                .header("Authorization", "Bearer " + token).header("Content-Type", "application/json")
                .header("Accept", "text/event-stream").timeout(frist)
                .POST(HttpRequest.BodyPublishers.ofString(koerper, StandardCharsets.UTF_8)).build();
        long start = System.nanoTime();
        try {
            HttpResponse<InputStream> r = client.send(anfrage, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream rumpf = r.body()) {
                if (r.statusCode() != 200) {
                    return new Antwort(r.statusCode(), "", List.of(), false, "HTTP " + r.statusCode(), KEIN_TEXT);
                }
                return lesen(rumpf, start);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Antwort(0, "", List.of(), false, "unterbrochen", KEIN_TEXT);
        } catch (IOException | RuntimeException e) {
            // nur der Klassenname: Meldungen können die URL oder Inhalte enthalten
            return new Antwort(0, "", List.of(), false, e.getClass().getSimpleName(), KEIN_TEXT);
        }
    }

    /** Liest den Strom bis zu seinem Ende; die Zeit bis zum ersten {@code text}-Ereignis misst Req 3.8. */
    static Antwort lesen(InputStream rumpf, long startNanos) throws IOException {
        BufferedReader zeilen = new BufferedReader(new InputStreamReader(rumpf, StandardCharsets.UTF_8));
        StringBuilder text = new StringBuilder();
        List<GelieferteQuelle> quellen = new ArrayList<>();
        boolean fertig = false;
        String fehler = null;
        long ersterText = KEIN_TEXT;
        String name = null;
        StringBuilder daten = new StringBuilder();
        String zeile;
        while ((zeile = zeilen.readLine()) != null) {
            if (zeile.isEmpty()) {
                if (name != null) {
                    JsonNode d = daten.isEmpty() ? JSON.createObjectNode() : JSON.readTree(daten.toString());
                    switch (name) {
                        case "text" -> {
                            if (ersterText == KEIN_TEXT) {
                                ersterText = (System.nanoTime() - startNanos) / 1_000_000;
                            }
                            text.append(d.path("delta").asString(""));
                        }
                        case "quellen" -> d.forEach(q -> quellen.add(new GelieferteQuelle(q.path("id").asString(null),
                                q.path("titel").asString(null), q.path("werkzeug").asString(null),
                                q.has("belege") ? q.path("belege").asInt() : null)));
                        case "fertig" -> fertig = true;
                        case "fehler" -> fehler = d.path("meldung").asString("Fehler");
                        default -> { // werkzeug: nur der Name, nichts zu merken
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
        return new Antwort(200, text.toString(), List.copyOf(quellen), fertig, fehler, ersterText);
    }

    /** Vorschlag: richtig, wenn der Strom mit {@code fertig} endete und alle Kernwerte vorkommen; Quelle ja, wenn alle erwarteten da sind. */
    static Ergebnis bewerten(Frage frage, Antwort antwort) {
        String text = normal(antwort.text());
        List<String> fehlend = frage.kernwerte().stream().filter(k -> !text.contains(normal(k))).toList();
        List<String> fehlendeQuellen = new ArrayList<>();
        for (ErwarteteQuelle erwartet : frage.quellen()) {
            boolean da = antwort.quellen().stream().anyMatch(g -> erwartet.dokument() != null
                    ? erwartet.dokument().toString().equalsIgnoreCase(g.id())
                    : erwartet.datenstand().equals(g.werkzeug()));
            if (!da) {
                fehlendeQuellen.add(erwartet.dokument() != null ? "dokument " + erwartet.dokument()
                        : "datenstand " + erwartet.datenstand());
            }
        }
        boolean ok = antwort.status() == 200 && antwort.fertig() && antwort.fehlermeldung() == null;
        return new Ergebnis(frage, antwort, ok && fehlend.isEmpty(), ok && fehlendeQuellen.isEmpty(), fehlend,
                List.copyOf(fehlendeQuellen));
    }

    /** Ohne Gross-/Kleinschreibung, Apostrophe (Tausendertrenner) und mehrfache Leerzeichen. */
    static String normal(String s) {
        return s.toLowerCase(Locale.ROOT).replaceAll("['’‘`´]", "").replaceAll("[\\s  ]+", " ")
                .strip();
    }

    /** 95. Perzentil (nächster Rang) der Zeit bis zum ersten Text in ms; -1 ohne Messwerte. */
    static long p95(List<Ergebnis> ergebnisse) {
        List<Long> zeiten = ergebnisse.stream().map(e -> e.antwort().ersterTextMs()).filter(z -> z >= 0)
                .sorted(Comparator.naturalOrder()).toList();
        return zeiten.isEmpty() ? KEIN_TEXT : zeiten.get((int) Math.ceil(0.95 * zeiten.size()) - 1);
    }

    /** Repo-Teil: nur Nummern, Vorschläge und Zahlen, keine Inhalte (für {@code docs/abnahme/phase2.md}). */
    public static String repoTeil(Lauf lauf) {
        StringBuilder b = new StringBuilder("| Nr. | Vorschlag richtig | Quelle | Bestätigt (Fabian) |\n| ---: | --- | --- | --- |\n");
        int richtig = 0;
        int beispielRichtig = 0;
        for (Ergebnis e : lauf.ergebnisse()) {
            boolean voll = e.richtig() && e.quelle();
            richtig += voll ? 1 : 0;
            beispielRichtig += voll && e.frage().beispielfrage() ? 1 : 0;
            b.append("| ").append(e.frage().nr()).append(" | ").append(ja(e.richtig(), "richtig", "falsch")).append(" | ")
                    .append(ja(e.quelle(), "ja", "nein")).append(" | |\n");
        }
        b.append("\nVorschlag richtig mit Quelle: ").append(richtig).append(" von ").append(lauf.ergebnisse().size())
                .append(", davon Beispielfragen ").append(beispielRichtig).append(" von ").append(Pruefliste.BEISPIELFRAGEN)
                .append(".\n");
        long p95 = p95(lauf.ergebnisse());
        b.append("Zeit bis zum ersten Text, p95: ").append(p95 < 0 ? "ohne Messwert" : p95 + " ms").append(" (Req 3.8, Ziel höchstens 10'000 ms).\n");
        if (lauf.abgebrochen()) {
            b.append("Lauf abgebrochen nach ").append(lauf.ergebnisse().size()).append(" Fragen: HTTP ")
                    .append(lauf.abbruchStatus()).append(" (Token abgelaufen).\n");
        }
        return b.toString();
    }

    /** Vollständiger Bericht mit Fragen, Antworten und Quellen: nur für {@code ARCHIV_GOLDEN_DIR}. */
    public static String bericht(Lauf lauf) {
        StringBuilder b = new StringBuilder("# Chat-Prüfliste, vollständiger Bericht (nicht ins Repository)\n\n");
        b.append("Vorschläge des Laufs; jede Antwort bestätigt Fabian von Hand (Req 13.2).\n\n");
        for (Ergebnis e : lauf.ergebnisse()) {
            Frage f = e.frage();
            Antwort a = e.antwort();
            b.append("## Frage ").append(f.nr()).append(f.beispielfrage() ? " (Beispielfrage)" : "").append("\n\n");
            b.append("Frage: ").append(f.frage()).append("\n\n");
            if (!f.hinweis().isBlank()) {
                b.append("Hinweis: ").append(f.hinweis()).append("\n\n");
            }
            b.append("Antwort: ").append(a.status() == 200 ? a.text() : "(keine, " + a.fehlermeldung() + ")").append("\n\n");
            b.append("Quellen: ").append(a.quellen().isEmpty() ? "keine" : a.quellen().stream()
                    .map(q -> q.id() != null ? "Dokument " + q.id() + " «" + q.titel() + "»"
                            : "Datenstand " + q.werkzeug() + " (" + q.belege() + " Belege)").toList()).append("\n\n");
            if (a.fehlermeldung() != null && a.status() == 200) {
                b.append("Fehlerereignis: ").append(a.fehlermeldung()).append("\n\n");
            }
            b.append("Erster Text nach: ").append(a.ersterTextMs() < 0 ? "ohne" : a.ersterTextMs() + " ms").append("\n\n");
            b.append("Vorschlag: ").append(ja(e.richtig(), "richtig", "falsch")).append(", Quelle ")
                    .append(ja(e.quelle(), "ja", "nein")).append("\n\n");
            if (!e.fehlendeKernwerte().isEmpty()) {
                b.append("Kernwerte nicht gefunden: ").append(e.fehlendeKernwerte()).append("\n\n");
            }
            if (!e.fehlendeQuellen().isEmpty()) {
                b.append("Erwartete Quellen nicht geliefert: ").append(e.fehlendeQuellen()).append("\n\n");
            }
            b.append("Bestätigung Fabian: richtig [ ] falsch [ ], Quelle ja [ ] nein [ ]\n\n");
        }
        b.append("---\n\n## Repo-Teil\n\n").append(repoTeil(lauf));
        return b.toString();
    }

    /**
     * Schreibt Bericht und Repo-Teil als {@code chat-pruefliste-bericht-<zeit>.md} bzw. {@code …-repo-teil.md} in das
     * Verzeichnis (Rechte 600) und gibt die beiden Pfade zurück. Das Verzeichnis muss schon bestehen.
     */
    public static Map<String, Path> schreiben(Lauf lauf, Path verzeichnis, Clock uhr) throws IOException {
        if (!Files.isDirectory(verzeichnis)) {
            throw new IOException("Berichtsverzeichnis fehlt");
        }
        String zeit = ZEIT.format(LocalDateTime.now(uhr));
        Path voll = verzeichnis.resolve("chat-pruefliste-bericht-" + zeit + ".md");
        Path repo = verzeichnis.resolve("chat-pruefliste-bericht-" + zeit + "-repo-teil.md");
        ablegen(voll, bericht(lauf));
        ablegen(repo, repoTeil(lauf));
        return Map.of("bericht", voll, "repoTeil", repo);
    }

    private static void ablegen(Path datei, String inhalt) throws IOException {
        try {
            Files.createFile(datei, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        } catch (UnsupportedOperationException e) {
            Files.createFile(datei); // kein POSIX-Dateisystem
        }
        Files.writeString(datei, inhalt, StandardCharsets.UTF_8);
    }

    private static String ja(boolean wert, String ja, String nein) {
        return wert ? ja : nein;
    }
}
