package ch.urinet.archiv.ki.adapter.out.langchain4j;

import ch.urinet.archiv.ki.domain.chat.ChatEreignis;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Bildet einen Werkzeugaufruf auf {@link ChatEreignis.WerkzeugErgebnis} ab (Spec 02, Aufgabe 7.2): Name, Parameter
 * des Aufrufs, die gelieferten Dokumente mit Titel und der Datenstand der Zahlenwerkzeuge. Nie Dokumentinhalte (weder
 * {@code inhalt} noch {@code auszug}). Gelesen wird die Rohantwort des Werkzeugs vor der Kennzeichnung der Dokumenttexte.
 */
final class WerkzeugErgebnisse {

    private static final ZoneId ZUERICH = ZoneId.of("Europe/Zurich");
    private static final DateTimeFormatter ZEITPUNKT = DateTimeFormatter.ofPattern("dd.MM.uuuu HH:mm");

    private WerkzeugErgebnisse() {
    }

    static ChatEreignis.WerkzeugErgebnis abbilden(ObjectMapper json, String name, String argumente, String antwort,
                                                  Supplier<Instant> jetzt) {
        JsonNode wurzel = lesen(json, antwort);
        Map<String, ChatEreignis.QuellDokument> dokumente = new LinkedHashMap<>();
        for (JsonNode treffer : wurzel.path("treffer")) {
            dokument(treffer, dokumente);
        }
        if (wurzel.has("id") && wurzel.has("titel")) {
            dokument(wurzel, dokumente);
        }
        for (JsonNode frist : wurzel.path("fristen")) {
            dokument(frist.path("dokument"), dokumente);
        }
        Optional<ChatEreignis.Datenstand> datenstand = Optional.empty();
        JsonNode stand = wurzel.path("datenstand");
        if (stand.isObject()) {
            datenstand = Optional.of(new ChatEreignis.Datenstand(zeitpunkt(stand.path("zeitpunkt").asText(""), jetzt),
                    (int) Math.max(0, Math.min(Integer.MAX_VALUE, stand.path("anzahl").asLong(0)))));
        }
        Optional<ChatEreignis.QuellWissen> wissen = "wissen_seite_zeigen".equals(name)
                ? wissen(wurzel) : Optional.empty();
        return new ChatEreignis.WerkzeugErgebnis(name, parameter(json, argumente), List.copyOf(dokumente.values()),
                datenstand, wissen);
    }

    private record WissensDokument(ChatEreignis.QuellDokument dokument, int anzahl) { }

    private static Optional<ChatEreignis.QuellWissen> wissen(JsonNode wurzel) {
        if (!wurzel.hasNonNull("pfad") || !wurzel.hasNonNull("titel") || !wurzel.hasNonNull("stand")) {
            return Optional.empty();
        }
        Map<UUID, WissensDokument> dokumente = new LinkedHashMap<>();
        for (JsonNode quelle : wurzel.path("quellen")) {
            if (!quelle.hasNonNull("dokumentId")) continue;
            UUID id = UUID.fromString(quelle.path("dokumentId").asText());
            var dokument = new ChatEreignis.QuellDokument(id, quelle.path("titel").asText(),
                    seite(quelle.path("seiten")));
            int anzahl = quelle.path("anzahl").asInt();
            dokumente.merge(id, new WissensDokument(dokument, anzahl),
                    (alt, neu) -> new WissensDokument((neu.anzahl() > alt.anzahl() ? neu : alt).dokument(),
                            alt.anzahl() + neu.anzahl()));
        }
        var geordnet = new ArrayList<>(dokumente.values());
        geordnet.sort(Comparator.comparingInt(WissensDokument::anzahl).reversed());
        return Optional.of(new ChatEreignis.QuellWissen(wurzel.path("pfad").asText(), wurzel.path("titel").asText(),
                Instant.parse(wurzel.path("stand").asText()),
                geordnet.stream().limit(5).map(WissensDokument::dokument).toList(), List.copyOf(dokumente.keySet())));
    }

    private static void dokument(JsonNode knoten, Map<String, ChatEreignis.QuellDokument> ziel) {
        try {
            UUID id = UUID.fromString(knoten.path("id").asText(""));
            ziel.putIfAbsent(id.toString(), new ChatEreignis.QuellDokument(id, knoten.path("titel").asText(""),
                    seite(knoten.path("seiten"))));
        } catch (IllegalArgumentException e) {
            // keine gültige ID: kein Dokument
        }
    }

    private static String seite(JsonNode knoten) {
        if (!knoten.isTextual()) {
            return null;
        }
        String text = knoten.asText();
        return text.length() <= 20 && text.matches("[1-9]\\d*(?:-[1-9]\\d*)?") ? text : null;
    }

    /** «TT.MM.JJJJ HH:mm» (Zürich) des Werkzeugs; sonst der Zeitpunkt des Aufrufs. */
    private static Instant zeitpunkt(String text, Supplier<Instant> jetzt) {
        try {
            return LocalDateTime.parse(text, ZEITPUNKT).atZone(ZUERICH).toInstant();
        } catch (DateTimeParseException e) {
            return jetzt.get();
        }
    }

    private static JsonNode lesen(ObjectMapper json, String antwort) {
        try {
            return json.readTree(antwort);
        } catch (JsonProcessingException | RuntimeException e) {
            return json.createObjectNode();
        }
    }

    static Map<String, Object> parameter(ObjectMapper json, String argumente) {
        if (argumente == null || argumente.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, Object> alle = json.readValue(argumente, new TypeReference<LinkedHashMap<String, Object>>() {
            });
            Map<String, Object> ergebnis = new LinkedHashMap<>();
            alle.forEach((k, v) -> {
                if (v != null) {
                    ergebnis.put(k, v);
                }
            });
            return ergebnis;
        } catch (JsonProcessingException e) {
            return Map.of();
        }
    }
}
