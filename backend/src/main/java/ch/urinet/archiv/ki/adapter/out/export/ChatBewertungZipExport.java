package ch.urinet.archiv.ki.adapter.out.export;

import ch.urinet.archiv.ki.application.chat.ChatAuswertungSpeicher;
import ch.urinet.archiv.ki.application.chat.ChatBewertungZipPort;
import ch.urinet.archiv.ki.domain.chat.Schwaerzung;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.enterprise.context.ApplicationScoped;

import java.io.BufferedWriter;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** JSON- und ZIP-Adapter für den Chat-Bewertungsexport. */
@ApplicationScoped
public class ChatBewertungZipExport implements ChatBewertungZipPort {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ZoneId ZUERICH = ZoneId.of("Europe/Zurich");

    @Override
    public void schreiben(ChatAuswertungSpeicher.ExportSnapshot snapshot, Schwaerzung schwaerzung, byte[] salz,
                          OutputStream ziel) throws IOException {
        try (ZipOutputStream zip = new ZipOutputStream(new NichtSchliessend(ziel), StandardCharsets.UTF_8)) {
            eintrag(zip, "schema.json", ressourcenText("/chat-bewertungen-schema.json"));
            eintrag(zip, "README.txt", readme());
            zip.putNextEntry(new ZipEntry("bewertungen.jsonl"));
            try (BufferedWriter text = new BufferedWriter(new OutputStreamWriter(
                    new NichtSchliessend(zip), StandardCharsets.UTF_8))) {
                snapshot.exportZeilen(zeile -> schreibeZeile(text, zeile, schwaerzung, salz));
                text.flush();
            }
            zip.closeEntry();
            zip.finish();
        }
    }

    private static void schreibeZeile(BufferedWriter ziel, ChatAuswertungSpeicher.ExportZeile zeile,
                                       Schwaerzung schwaerzung, byte[] salz) {
        ObjectNode json = JSON.createObjectNode();
        json.put("datum", zeile.zeit().atZone(ZUERICH).toLocalDate().toString());
        json.put("modus", zeile.modus().name());
        json.put("sterne", zeile.sterne());
        optional(json, "grund", zeile.grund());
        optionalText(json, "freitext", zeile.freitext(), schwaerzung);
        optionalText(json, "frage", zeile.frage(), schwaerzung);
        optionalText(json, "antwort", zeile.antwort(), schwaerzung);
        try {
            ArrayNode werkzeuge = json.putArray("werkzeuge");
            ArrayNode treffer = json.putArray("treffer");
            Set<String> suchwege = new LinkedHashSet<>();
            Map<String, Double> aehnlichkeiten = new HashMap<>();
            JsonNode toolNodes = JSON.readTree(zeile.werkzeugeJson() == null ? "[]" : zeile.werkzeugeJson());
            for (JsonNode tool : toolNodes) {
                ObjectNode exportWerkzeug = werkzeuge.addObject();
                String name = tool.path("name").asText("");
                exportWerkzeug.put("name", name);
                JsonNode parameter = tool.path("parameter");
                exportWerkzeug.set("parameter", bereinigen(parameter, schwaerzung, salz));
                int trefferzahl = tool.path("dokumentIds").isArray() ? tool.path("dokumentIds").size() : 0;
                exportWerkzeug.put("anzahlTreffer", trefferzahl);
                if (tool.path("suchweg").isTextual()) {
                    suchwege.add(tool.path("suchweg").asText());
                }
                tool.path("aehnlichkeiten").fields().forEachRemaining(eintrag -> {
                    if (eintrag.getValue().isNumber()) {
                        aehnlichkeiten.putIfAbsent(eintrag.getKey(), eintrag.getValue().doubleValue());
                    }
                });
            }
            int rang = 1;
            JsonNode quellen = JSON.readTree(zeile.quellenJson() == null ? "[]" : zeile.quellenJson());
            for (JsonNode quelle : quellen) {
                if (quelle.hasNonNull("id")) {
                    treffer.addObject().put("typ", "DOKUMENT")
                            .put("kennung", hash(salz, quelle.path("id").asText()))
                            .put("rang", rang++).put("aehnlichkeit",
                                    aehnlichkeiten.getOrDefault(quelle.path("id").asText(), null));
                } else if (quelle.path("wissen").hasNonNull("link")) {
                    String link = quelle.path("wissen").path("link").asText();
                    treffer.addObject().put("typ", "WISSENSSEITE")
                            .put("kennung", hash(salz, link)).put("rang", rang++).putNull("aehnlichkeit");
                    suchwege.add("WISSENSSEITEN");
                    for (JsonNode dokument : quelle.path("dokumente")) {
                        String dokumentLink = dokument.path("link").asText();
                        treffer.addObject().put("typ", "DOKUMENT")
                                .put("kennung", hash(salz, dokumentLink)).put("rang", rang++)
                                .putNull("aehnlichkeit");
                    }
                }
            }
            json.set("suchweg", JSON.valueToTree(List.copyOf(suchwege)));
            if (zeile.zeitBisErstemTextMs() == null) {
                json.putNull("zeitBisErstemTextMs");
            } else {
                json.put("zeitBisErstemTextMs", zeile.zeitBisErstemTextMs());
            }
            ObjectNode tokens = json.putObject("tokens");
            if (zeile.tokensEin() == null) {
                tokens.putNull("ein");
            } else {
                tokens.put("ein", zeile.tokensEin());
            }
            if (zeile.tokensAus() == null) {
                tokens.putNull("aus");
            } else {
                tokens.put("aus", zeile.tokensAus());
            }
            optional(json, "modellkennung", zeile.modellkennung());
            optional(json, "systempromptVersion", zeile.systempromptVersion());
            ziel.write(JSON.writeValueAsString(json));
            ziel.newLine();
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Chat-Bewertung nicht exportierbar", e);
        } catch (IOException e) {
            throw new IllegalStateException("Chat-Bewertung nicht exportierbar", e);
        }
    }

    private static JsonNode bereinigen(JsonNode quelle, Schwaerzung schwaerzung, byte[] salz) {
        if (quelle == null || quelle.isNull()) {
            return quelle == null ? JSON.nullNode() : quelle.deepCopy();
        }
        if (quelle.isTextual()) {
            String text = schwaerzung == null ? quelle.asText() : schwaerzung.anwenden(quelle.asText());
            try {
                text = hash(salz, UUID.fromString(text).toString());
            } catch (IllegalArgumentException ignored) {
                // Kein UUID-Parameter: die Textschwärzung bleibt erhalten.
            }
            return JSON.getNodeFactory().textNode(text);
        }
        if (quelle.isArray()) {
            ArrayNode ziel = JSON.createArrayNode();
            quelle.forEach(wert -> ziel.add(bereinigen(wert, schwaerzung, salz)));
            return ziel;
        }
        if (quelle.isObject()) {
            ObjectNode ziel = JSON.createObjectNode();
            quelle.fields().forEachRemaining(feld ->
                    ziel.set(feld.getKey(), bereinigen(feld.getValue(), schwaerzung, salz)));
            return ziel;
        }
        return quelle.deepCopy();
    }

    private static void optionalText(ObjectNode objekt, String feld, String wert, Schwaerzung schwaerzung) {
        if (wert == null) {
            objekt.putNull(feld);
        } else {
            objekt.put(feld, schwaerzung == null ? wert : schwaerzung.anwenden(wert));
        }
    }

    private static void optional(ObjectNode objekt, String feld, String wert) {
        if (wert == null) {
            objekt.putNull(feld);
        } else {
            objekt.put(feld, wert);
        }
    }

    private static String hash(byte[] salz, String id) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(salz);
            return HexFormat.of().formatHex(digest.digest(id.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 nicht verfügbar", e);
        }
    }

    private static void eintrag(ZipOutputStream zip, String name, String inhalt) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(inhalt.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private static String ressourcenText(String pfad) throws IOException {
        try (var quelle = ChatBewertungZipExport.class.getResourceAsStream(pfad)) {
            if (quelle == null) {
                throw new IOException("Chat-Bewertungsschema fehlt");
            }
            return new String(quelle.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String readme() {
        return """
                Chat-Bewertungen, Format chat-bewertungen/v1

                Die Datei bewertungen.jsonl enthält eine JSON-Zeile je Bewertung. schema.json beschreibt die Felder.
                Die Treffer-Kennungen sind mit einem nur für diesen Export verwendeten Salz gehashte IDs.
                Zeit bis zum ersten Text, Modellkennung und Prompt-Version sind null, wenn sie für eine historische
                Antwort nicht gespeichert wurden. Ähnlichkeit ist null, wenn sie nicht erfasst wurde oder der Treffer
                aus der Volltextsuche stammt.

                Bei aktivierter Schwärzung werden übliche E-Mail-Adressen, Telefonnummern, gültige IBANs,
                UID- und AHV-Nummern, Geldbeträge, Datumsangaben und bekannte Kontakt- und Partnernamen ersetzt.
                Freie Personennamen und Zusammenhänge werden nicht zuverlässig erkannt. Prüfen Sie die Datei
                vor jeder Weitergabe manuell.
                """;
    }

    private static final class NichtSchliessend extends FilterOutputStream {
        NichtSchliessend(OutputStream ziel) {
            super(ziel);
        }

        @Override
        public void close() {
        }
    }
}
