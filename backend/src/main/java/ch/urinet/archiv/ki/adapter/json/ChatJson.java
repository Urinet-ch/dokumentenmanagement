package ch.urinet.archiv.ki.adapter.json;

import ch.urinet.archiv.ki.domain.chat.ChatAusgabe;
import ch.urinet.archiv.ki.domain.chat.ChatEreignis;
import ch.urinet.archiv.ki.domain.chat.Quelle;
import ch.urinet.archiv.ki.domain.chat.WerkzeugAufruf;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * JSON-Formen des Chats (design.md «Vertrag Welle 7»), gleich für die Ereignisse des Streams, den Verlauf und die
 * Spalten {@code quellen} und {@code werkzeuge}:
 * <ul>
 *   <li>Quelle Dokument {@code {"id", "titel", "link": "/dokument/<id>"}}, Quelle Datenstand
 *       {@code {"werkzeug", "datenstand": "<ISO-8601 UTC>", "belege": n}}</li>
 *   <li>Werkzeugaufruf {@code {"name", "parameter": {…}, "dokumentIds": […], "datenstand"?: {"zeitpunkt", "belege"}}}</li>
 *   <li>SSE: {@code werkzeug {"name"}}, {@code text {"delta"}}, {@code quellen […]},
 *       {@code fertig {"gespraechId", "tokens": {"ein", "aus"}}}, {@code fehler {"meldung", "gespraechId"}}</li>
 * </ul>
 */
public final class ChatJson {

    private static final ObjectMapper JSON = new ObjectMapper();

    private ChatJson() {
    }

    /** Daten ({@code data:}) des Ereignisses; der Name ({@code event:}) ist {@link ChatAusgabe#name()}. */
    public static String daten(ChatAusgabe ausgabe) {
        return text(switch (ausgabe) {
            case ChatAusgabe.Werkzeug w -> Map.of("name", w.werkzeug());
            case ChatAusgabe.Text t -> Map.of("delta", t.delta());
            case ChatAusgabe.Quellen q -> quellenWerte(q.quellen());
            case ChatAusgabe.Fertig f -> {
                Map<String, Object> werte = new LinkedHashMap<>();
                werte.put("gespraechId", f.gespraechId().toString());
                Map<String, Object> tokens = new LinkedHashMap<>();
                tokens.put("ein", f.tokens().tokensEin());
                tokens.put("aus", f.tokens().tokensAus());
                werte.put("tokens", tokens);
                yield werte;
            }
            case ChatAusgabe.Fehler f -> {
                Map<String, Object> werte = new LinkedHashMap<>();
                werte.put("meldung", f.meldung());
                werte.put("gespraechId", f.gespraechId().toString());
                yield werte;
            }
        });
    }

    public static String quellen(List<Quelle> quellen) {
        return text(quellenWerte(quellen));
    }

    public static List<Map<String, Object>> quellenWerte(List<Quelle> quellen) {
        List<Map<String, Object>> werte = new ArrayList<>();
        for (Quelle quelle : quellen) {
            Map<String, Object> wert = new LinkedHashMap<>();
            switch (quelle) {
                case Quelle.Dokument d -> {
                    wert.put("id", d.id().toString());
                    wert.put("titel", d.titel());
                    wert.put("link", d.link());
                }
                case Quelle.Stand s -> {
                    wert.put("werkzeug", s.werkzeug());
                    wert.put("datenstand", s.datenstand().zeitpunkt().toString());
                    wert.put("belege", s.datenstand().belege());
                }
            }
            werte.add(wert);
        }
        return werte;
    }

    /** Quellen aus der Spalte {@code quellen}; {@code null} ergibt keine. */
    public static List<Quelle> quellenLesen(String json) {
        if (json == null) {
            return List.of();
        }
        List<Quelle> quellen = new ArrayList<>();
        for (JsonNode wert : baum(json)) {
            if (wert.hasNonNull("link")) {
                quellen.add(new Quelle.Dokument(UUID.fromString(wert.path("id").asText()), wert.path("titel").asText()));
            } else {
                quellen.add(new Quelle.Stand(wert.path("werkzeug").asText(), new ChatEreignis.Datenstand(
                        Instant.parse(wert.path("datenstand").asText()), wert.path("belege").asInt())));
            }
        }
        return quellen;
    }

    public static String werkzeuge(List<WerkzeugAufruf> aufrufe) {
        List<Map<String, Object>> werte = new ArrayList<>();
        for (WerkzeugAufruf aufruf : aufrufe) {
            Map<String, Object> wert = new LinkedHashMap<>();
            wert.put("name", aufruf.name());
            wert.put("parameter", aufruf.parameter());
            wert.put("dokumentIds", aufruf.dokumentIds().stream().map(UUID::toString).toList());
            aufruf.datenstand().ifPresent(s -> {
                Map<String, Object> stand = new LinkedHashMap<>();
                stand.put("zeitpunkt", s.zeitpunkt().toString());
                stand.put("belege", s.belege());
                wert.put("datenstand", stand);
            });
            werte.add(wert);
        }
        return text(werte);
    }

    private static JsonNode baum(String json) {
        try {
            return JSON.readTree(json);
        } catch (JsonProcessingException e) {
            // ohne Ursache: ihre Meldung zitiert den Inhalt (Req 16.7)
            throw new IllegalStateException("Chat-JSON nicht lesbar");
        }
    }

    private static String text(Object wert) {
        try {
            return JSON.writeValueAsString(wert);
        } catch (JsonProcessingException e) {
            // ohne Meldung: sie könnte Werte enthalten (Req 16.7)
            throw new IllegalStateException("Chat-JSON nicht schreibbar");
        }
    }
}
