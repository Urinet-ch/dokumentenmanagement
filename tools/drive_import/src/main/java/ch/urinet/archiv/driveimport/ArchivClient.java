package ch.urinet.archiv.driveimport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Sendet eine Datei an {@code POST /api/v1/dokumente} (Req 12.1, 12.8): eine Datei je Anfrage, Multipart mit den
 * Teilen {@code hinweis} und {@code dateien}, immer mit {@code Content-Length} (design.md «Umsetzung 2.4»). Das
 * Archiv behandelt sie wie jeden Upload: neues Dokument im Eingangskorb, Dublette oder Ablehnung mit Grund.
 * 503 und 429 wiederholt der Client nach {@code Retry-After}, Netzfehler nach kurzer Pause; 401 und 403 brechen den
 * Lauf ab. Das Token steht nur im Header {@code Authorization}, nie in einer Meldung; Weiterleitungen folgt der
 * Client nicht.
 */
final class ArchivClient {

    static final class ArchivFehler extends RuntimeException {
        ArchivFehler(String meldung) {
            super(meldung);
        }
    }

    static final String PFAD = "/api/v1/dokumente";
    private static final String QUELLEN_PFAD = "/api/v1/quellen";
    private static final String PRUEFSUMMEN_PFAD = "/api/v1/dokumente/pruefsummen";
    static final int MAX_VERSUCHE = 5;
    static final Duration MAX_WARTEN = Duration.ofSeconds(60);
    static final Duration NETZ_PAUSE = Duration.ofSeconds(5);
    static final String NICHT_LESBAR = "Datei nicht lesbar, bitte Leserechte prüfen (bei Google Drive für Desktop "
            + "die Datei offline verfügbar machen)";

    enum Status { UEBERTRAGEN, SCHON_VORHANDEN, ABGELEHNT, ABBRUCH }

    record Ergebnis(Status status, String grund) {
    }

    record QuelllaufZaehler(int gefunden, int neu, int dublettePruefsumme, int uebersprungen, int fehler) {
    }

    /** Wartet vor einer Wiederholung; in Tests ersetzbar. */
    @FunctionalInterface
    interface Warten {
        void warten(Duration dauer) throws InterruptedException;
    }

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final SecureRandom ZUFALL = new SecureRandom();

    private final URI ziel;
    private final String token;
    private final HttpClient http;
    private final Warten warten;

    ArchivClient(URI archiv, String token, Warten warten) {
        this.ziel = archiv.resolve(PFAD);
        this.token = token;
        this.warten = warten;
        this.http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(30))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    Ergebnis senden(Path datei, String dateiname, String hinweis) throws InterruptedException {
        return senden(datei, dateiname, hinweis, null, Map.of());
    }

    Ergebnis senden(Path datei, String dateiname, String hinweis, String art,
                    Map<String, List<String>> klassifikationen) throws InterruptedException {
        return senden(datei, dateiname, hinweis, art, klassifikationen, null);
    }

    Ergebnis senden(Path datei, String dateiname, String hinweis, String art,
                    Map<String, List<String>> klassifikationen, String quelllauf) throws InterruptedException {
        if (dateiname.codePoints().anyMatch(c -> Character.getType(c) == Character.CONTROL)) {
            return abgelehnt("Dateiname enthält Steuerzeichen, bitte umbenennen");
        }
        if (!lesbar(datei)) {
            return abgelehnt(NICHT_LESBAR);
        }
        String grund = null;
        for (int versuch = 1; versuch <= MAX_VERSUCHE; versuch++) {
            HttpResponse<String> antwort;
            try {
                antwort = http.send(anfrage(datei, dateiname, hinweis, art, klassifikationen, quelllauf),
                        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            } catch (FileNotFoundException e) {
                return abgelehnt(NICHT_LESBAR);
            } catch (IOException e) {
                grund = "Archiv nicht erreichbar (" + e.getClass().getSimpleName() + "), bitte später erneut starten";
                if (versuch < MAX_VERSUCHE) {
                    warten.warten(NETZ_PAUSE);
                }
                continue;
            }
            int status = antwort.statusCode();
            if (status == 503 || status == 429) {
                grund = beschreiben(antwort) + ", bitte später erneut starten";
                if (versuch < MAX_VERSUCHE) {
                    warten.warten(wartezeit(antwort));
                }
                continue;
            }
            return auswerten(antwort);
        }
        return abgelehnt(grund);
    }

    String quelllaufStarten(String quelle, boolean trockenlauf) {
        JsonNode antwort = postJson(QUELLEN_PFAD + "/" + quelle + "/laeufe",
                Map.of("art", trockenlauf ? "TROCKEN" : "ECHT"), 201);
        String id = antwort == null ? null : antwort.path("id").asText(null);
        if (id == null || id.isBlank()) {
            throw new ArchivFehler("Das Archiv hat keinen Quelllauf zurückgegeben.");
        }
        return id;
    }

    Set<String> pruefsummenAbfragen(List<String> sha256) {
        if (sha256.isEmpty() || sha256.size() > 1000) {
            throw new ArchivFehler("Die Prüfsummen-Abfrage muss 1 bis 1'000 Prüfsummen enthalten.");
        }
        JsonNode antwort = postJson(PRUEFSUMMEN_PFAD, Map.of("sha256", sha256), 200);
        JsonNode vorhanden = antwort == null ? null : antwort.path("vorhanden");
        if (vorhanden == null || !vorhanden.isArray()) {
            throw new ArchivFehler("Das Archiv hat keine gültige Prüfsummen-Antwort zurückgegeben.");
        }
        java.util.HashSet<String> ergebnis = new java.util.HashSet<>();
        vorhanden.forEach(wert -> ergebnis.add(wert.asText()));
        return Set.copyOf(ergebnis);
    }

    QuelllaufZaehler quelllaufAbschliessen(String laufId, Bericht bericht, boolean trockenlauf,
                                            boolean abgebrochen) {
        Map<String, Object> inhalt;
        if (trockenlauf) {
            inhalt = Map.of("zaehler", Map.of(
                    "gefunden", bericht.gefunden(),
                    "neu", bericht.anzahlUebertragen(),
                    "dublettePruefsumme", bericht.anzahlSchonVorhanden(),
                    "dubletteFachlich", 0,
                    "uebersprungen", bericht.uebersprungen(),
                    "fehler", bericht.abgelehnt()));
        } else {
            inhalt = Map.of("uebersprungen", bericht.uebersprungen(),
                    "meldungen", List.of(), "abgebrochen", abgebrochen);
        }
        JsonNode antwort = postJson(QUELLEN_PFAD + "/laeufe/" + laufId + "/abschliessen", inhalt, 200);
        JsonNode zaehler = antwort == null ? null : antwort.path("zaehler");
        if (zaehler == null || !zaehler.isObject()) {
            throw new ArchivFehler("Das Archiv hat keine Laufzähler zurückgegeben.");
        }
        return new QuelllaufZaehler(zaehler.path("gefunden").asInt(), zaehler.path("neu").asInt(),
                zaehler.path("dublettePruefsumme").asInt(), zaehler.path("uebersprungen").asInt(),
                zaehler.path("fehler").asInt());
    }

    static String sha256(Path datei) {
        try (var eingabe = Files.newInputStream(datei)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] puffer = new byte[8192];
            int gelesen;
            while ((gelesen = eingabe.read(puffer)) != -1) {
                digest.update(puffer, 0, gelesen);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException e) {
            throw new ArchivFehler("Datei nicht lesbar, bitte Leserechte prüfen.");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 wird von der Laufzeit nicht unterstützt", e);
        }
    }

    /**
     * Öffnet die Datei einmal zur Probe, bevor sie gesendet wird: Fehlt das Leserecht, meldet {@code ofFile} das
     * sonst erst beim Senden als {@link IOException}, und der Lauf hielte es für einen Netzfehler (Review PR #64, S3).
     */
    private static boolean lesbar(Path datei) {
        try (SeekableByteChannel kanal = Files.newByteChannel(datei, StandardOpenOption.READ)) {
            return kanal.isOpen();
        } catch (IOException | SecurityException e) {
            return false;
        }
    }

    private HttpRequest anfrage(Path datei, String dateiname, String hinweis, String art,
                                Map<String, List<String>> klassifikationen, String quelllauf)
            throws FileNotFoundException {
        String grenze = "----drive-import-" + HexFormat.of().formatHex(zufall());
        String kopf = "--" + grenze + "\r\n"
                + "Content-Disposition: form-data; name=\"hinweis\"\r\n"
                + "Content-Type: text/plain; charset=UTF-8\r\n\r\n"
                + hinweis + "\r\n";
        if (quelllauf != null) {
            kopf += "--" + grenze + "\r\n"
                    + "Content-Disposition: form-data; name=\"quelllauf\"\r\n"
                    + "Content-Type: text/plain; charset=UTF-8\r\n\r\n"
                    + quelllauf + "\r\n";
        }
        if (art != null) {
            kopf += "--" + grenze + "\r\n"
                    + "Content-Disposition: form-data; name=\"art\"\r\n"
                    + "Content-Type: text/plain; charset=UTF-8\r\n\r\n"
                    + art + "\r\n"
                    + "--" + grenze + "\r\n"
                    + "Content-Disposition: form-data; name=\"klassifikationen\"\r\n"
                    + "Content-Type: application/json; charset=UTF-8\r\n\r\n"
                    + json(klassifikationen) + "\r\n";
        }
        kopf += "--" + grenze + "\r\n"
                + "Content-Disposition: form-data; name=\"dateien\"; filename=\"" + dateiname.replace("\"", "%22")
                + "\"\r\n"
                + "Content-Type: application/octet-stream\r\n\r\n";
        String fuss = "\r\n--" + grenze + "--\r\n";
        HttpRequest.BodyPublisher inhalt = HttpRequest.BodyPublishers.concat(
                HttpRequest.BodyPublishers.ofByteArray(kopf.getBytes(StandardCharsets.UTF_8)),
                HttpRequest.BodyPublishers.ofFile(datei),
                HttpRequest.BodyPublishers.ofByteArray(fuss.getBytes(StandardCharsets.UTF_8)));
        return HttpRequest.newBuilder(ziel)
                .timeout(Duration.ofMinutes(10))
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/json, application/problem+json")
                .header("Content-Type", "multipart/form-data; boundary=" + grenze)
                .POST(inhalt)
                .build();
    }

    private JsonNode postJson(String pfad, Object koerper, int erwarteterStatus) {
        String inhalt;
        try {
            inhalt = JSON.writeValueAsString(koerper);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("Anfrage konnte nicht serialisiert werden", e);
        }
        HttpRequest anfrage = HttpRequest.newBuilder(ziel.resolve(pfad))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/json, application/problem+json")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(inhalt, StandardCharsets.UTF_8))
                .build();
        try {
            HttpResponse<String> antwort = http.send(anfrage,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            JsonNode json = json(antwort.body());
            if (antwort.statusCode() != erwarteterStatus) {
                throw new ArchivFehler(beschreiben(antwort));
            }
            return json;
        } catch (IOException e) {
            throw new ArchivFehler("Das Archiv ist für den Quelllauf nicht erreichbar.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ArchivFehler("Der Quelllauf wurde unterbrochen.");
        }
    }

    private static String json(Map<String, List<String>> klassifikationen) {
        try {
            return JSON.writeValueAsString(klassifikationen);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("Klassifikationen konnten nicht serialisiert werden", e);
        }
    }

    private static byte[] zufall() {
        byte[] bytes = new byte[12];
        ZUFALL.nextBytes(bytes);
        return bytes;
    }

    private static Ergebnis auswerten(HttpResponse<String> antwort) {
        int status = antwort.statusCode();
        JsonNode inhalt = json(antwort.body());
        if (status == 401) {
            return new Ergebnis(Status.ABBRUCH, "Archiv lehnt das Token ab (401 " + code(inhalt, "NICHT_ANGEMELDET")
                    + "); bitte ein gültiges Token mit Scope «import» ablegen");
        }
        if (status == 403) {
            return new Ergebnis(Status.ABBRUCH, "Token darf nicht erfassen (403 " + code(inhalt, "NICHT_ERLAUBT")
                    + "); bitte ein Token mit Scope «import» verwenden");
        }
        JsonNode ergebnis = null;
        if (status == 202 && inhalt != null && inhalt.isArray() && !inhalt.isEmpty()) {
            ergebnis = inhalt.get(0);
        } else if (inhalt != null && inhalt.path("ergebnisse").isArray() && !inhalt.path("ergebnisse").isEmpty()) {
            ergebnis = inhalt.path("ergebnisse").get(0);
        }
        if (ergebnis != null) {
            switch (ergebnis.path("status").asText()) {
                case "NEU":
                    return new Ergebnis(Status.UEBERTRAGEN, null);
                case "DUBLETTE":
                    return new Ergebnis(Status.SCHON_VORHANDEN, "schon vorhanden");
                case "ABGELEHNT":
                    JsonNode fehler = ergebnis.path("fehler");
                    return abgelehnt(fehler.path("detail").asText("vom Archiv abgelehnt") + " ("
                            + fehler.path("status").asInt(status) + " " + fehler.path("fehlercode").asText("") + ")");
                default:
                    break;
            }
        }
        return abgelehnt(beschreiben(antwort));
    }

    private static String beschreiben(HttpResponse<String> antwort) {
        JsonNode inhalt = json(antwort.body());
        String code = code(inhalt, "");
        String detail = inhalt == null ? "" : inhalt.path("detail").asText("");
        StringBuilder text = new StringBuilder(detail.isEmpty() ? "Archiv antwortet mit Fehler" : detail);
        text.append(" (").append(antwort.statusCode());
        if (!code.isEmpty()) {
            text.append(' ').append(code);
        }
        return text.append(')').toString();
    }

    private static String code(JsonNode inhalt, String sonst) {
        return inhalt == null ? sonst : inhalt.path("fehlercode").asText(sonst);
    }

    private static Duration wartezeit(HttpResponse<String> antwort) {
        long sekunden = antwort.headers().firstValue("Retry-After").map(wert -> {
            try {
                return Long.parseLong(wert.strip());
            } catch (NumberFormatException e) {
                return -1L;
            }
        }).orElse(-1L);
        if (sekunden < 0) {
            sekunden = antwort.statusCode() == 429 ? 1 : 30;
        }
        Duration dauer = Duration.ofSeconds(sekunden);
        return dauer.compareTo(MAX_WARTEN) > 0 ? MAX_WARTEN : dauer;
    }

    private static JsonNode json(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return JSON.readTree(text);
        } catch (IOException e) {
            return null;
        }
    }

    private static Ergebnis abgelehnt(String grund) {
        return new Ergebnis(Status.ABGELEHNT, grund);
    }
}
