package ch.urinet.archiv.ki.adapter.json;

import ch.urinet.archiv.ki.domain.chat.ChatAusgabe;
import ch.urinet.archiv.ki.domain.chat.ChatEreignis;
import ch.urinet.archiv.ki.domain.chat.Quelle;
import ch.urinet.archiv.ki.domain.chat.WerkzeugAufruf;
import ch.urinet.archiv.ki.application.chat.ChatAnalyseDaten;
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
 *   <li>Werkzeugaufruf {@code {"name", "parameter": {…}, "dokumentIds": […], "kontextstufe"?, "datenstand"?, "suchweg"?}}</li>
 *   <li>SSE: {@code werkzeug {"name"}}, {@code text {"delta"}}, {@code quellen […]},
 *       {@code fertig {"gespraechId", "nachrichtId", "tokens": {"ein", "aus"}}}, {@code fehler {"meldung", "gespraechId"}}</li>
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
                werte.put("nachrichtId", Long.toString(f.nachrichtId()));
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
                case Quelle.Wissen w -> {
                    wert.put("wissen", Map.of("titel", w.titel(), "link", w.link(), "stand", w.stand().toString()));
                    wert.put("dokumente", w.dokumente().stream().map(d -> {
                        Map<String, Object> dokument = new LinkedHashMap<>();
                        dokument.put("titel", d.titel());
                        dokument.put("link", d.link());
                        if (d.seite() != null) {
                            dokument.put("seiten", d.seite());
                        }
                        return dokument;
                    }).toList());
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
            if (wert.hasNonNull("wissen")) {
                JsonNode wissen = wert.path("wissen");
                String link = wissen.path("link").asText();
                if (!link.startsWith("/wissen/")) {
                    throw new IllegalStateException("Chat-Wissensquelle enthält einen ungültigen Link");
                }
                List<Quelle.Dokument> dokumente = new ArrayList<>();
                for (JsonNode dokument : wert.path("dokumente")) {
                    String dokumentLink = dokument.path("link").asText();
                    if (!dokumentLink.startsWith("/dokument/")) {
                        throw new IllegalStateException("Chat-Wissensquelle enthält einen ungültigen Dokumentlink");
                    }
                    dokumente.add(new Quelle.Dokument(UUID.fromString(dokumentLink.substring("/dokument/".length())),
                            dokument.path("titel").asText(), dokument.hasNonNull("seiten")
                                    ? dokument.path("seiten").asText() : null));
                }
                quellen.add(new Quelle.Wissen(link.substring("/wissen/".length()), wissen.path("titel").asText(),
                        Instant.parse(wissen.path("stand").asText()), dokumente));
            } else if (wert.hasNonNull("link")) {
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
            if (aufruf.suchweg() != null) {
                wert.put("suchweg", aufruf.suchweg());
            }
            if (!aufruf.aehnlichkeiten().isEmpty()) {
                Map<String, Double> aehnlichkeiten = new LinkedHashMap<>();
                aufruf.aehnlichkeiten().forEach((id, score) -> aehnlichkeiten.put(id.toString(), score));
                wert.put("aehnlichkeiten", aehnlichkeiten);
            }
            aufruf.kontextstufe().ifPresent(s -> wert.put("kontextstufe", s.name()));
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

    public static List<ChatAnalyseDaten.Werkzeug> analyseWerkzeugeLesen(String json) {
        if (json == null) {
            throw new IllegalStateException("Chat-Werkzeugdaten nicht lesbar");
        }
        JsonNode wurzel = baum(json);
        if (wurzel == null || !wurzel.isArray()) {
            throw new IllegalStateException("Chat-Werkzeugdaten nicht lesbar");
        }
        List<ChatAnalyseDaten.Werkzeug> aufrufe = new ArrayList<>();
        for (JsonNode wert : wurzel) {
            if (!wert.path("name").isTextual()) {
                throw new IllegalStateException("Chat-Werkzeugname nicht lesbar");
            }
            JsonNode stufe = wert.get("kontextstufe");
            aufrufe.add(new ChatAnalyseDaten.Werkzeug(wert.path("name").asText(),
                    stufe != null && stufe.isTextual() ? stufe.asText() : null));
        }
        return List.copyOf(aufrufe);
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
