package ch.urinet.archiv.ki.application.chat;

import ch.urinet.archiv.gemeinsam.domain.FachFehler;
import ch.urinet.archiv.gemeinsam.domain.Fehlercode;
import ch.urinet.archiv.ki.domain.chat.Schwaerzung;
import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.protokoll.domain.IpAdresse;
import ch.urinet.archiv.protokoll.domain.ProtokollAktion;
import ch.urinet.archiv.protokoll.domain.ProtokollDetails;
import ch.urinet.archiv.protokoll.domain.ZugriffEintrag;
import ch.urinet.archiv.sicherheit.domain.Rolle;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import jakarta.transaction.Transactional.TxType;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Auswertung und nicht persistierter ZIP-Download der Bewertungen (Spec 02, Requirement 15.5–15.8). */
@ApplicationScoped
public class ChatBewertungAuswertung {

    public static final int MAX_EXPORT_ZEILEN = 5_000;
    public static final int MAX_SCHLECHTE_ANTWORTEN = 100;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final ZoneId ZUERICH = ZoneId.of("Europe/Zurich");

    private final ChatAuswertungSpeicher speicher;
    private final ChatAuswertungProtokoll protokoll;

    public ChatBewertungAuswertung(ChatAuswertungSpeicher speicher, ChatAuswertungProtokoll protokoll) {
        this.speicher = speicher;
        this.protokoll = protokoll;
    }

    @Transactional
    public ChatAuswertungSpeicher.Auswertung auswertung(LocalDate von, LocalDate bis) {
        return speicher.auswertung(filter(von, bis, 1, 5));
    }

    @Transactional(TxType.NOT_SUPPORTED)
    public Export vorbereiten(LocalDate von, LocalDate bis, int minSterne, int maxSterne, boolean schwaerzen,
                              boolean klartextBestaetigt, MandantCode mandant, String benutzer,
                              Optional<IpAdresse> ip) {
        if (minSterne < 1 || maxSterne > 5 || minSterne > maxSterne
                || !schwaerzen && !klartextBestaetigt) {
            throw new FachFehler(Fehlercode.ANFRAGE_UNGUELTIG);
        }
        ChatAuswertungSpeicher.Filter filter = filter(von, bis, minSterne, maxSterne);
        ChatAuswertungSpeicher.ExportSnapshot snapshot = speicher.exportSnapshot(filter);
        try {
            long anzahl = snapshot.anzahl();
            if (anzahl > MAX_EXPORT_ZEILEN) {
                throw new FachFehler(Fehlercode.BEWERTUNG_EXPORT_ZU_GROSS);
            }
            protokoll.schreiben(new ZugriffEintrag(Optional.of(benutzer), Optional.of(Rolle.INHABER.name()),
                    ProtokollAktion.CHAT_BEWERTUNG_EXPORT, Optional.empty(), ip,
                    new ProtokollDetails("von=" + von + ";bis=" + bis + ";anzahl=" + anzahl
                            + ";modus=" + (schwaerzen ? "GESCHWAERZT" : "KLARTEXT"), null, null),
                    Optional.empty()));

            Schwaerzung redaktion = schwaerzen ? new Schwaerzung(snapshot.kontaktUndPartnerNamen()) : null;
            byte[] salz = new byte[32];
            RANDOM.nextBytes(salz);
            return new Export(snapshot, redaktion, salz);
        } catch (RuntimeException e) {
            snapshot.close();
            throw e;
        }
    }

    public static final class Export implements AutoCloseable {
        private final ChatAuswertungSpeicher.ExportSnapshot snapshot;
        private final Schwaerzung redaktion;
        private final byte[] salz;

        private Export(ChatAuswertungSpeicher.ExportSnapshot snapshot, Schwaerzung redaktion, byte[] salz) {
            this.snapshot = snapshot;
            this.redaktion = redaktion;
            this.salz = salz;
        }

        public void schreiben(OutputStream ziel) throws IOException {
            try (ZipOutputStream zip = new ZipOutputStream(new NichtSchliessend(ziel), StandardCharsets.UTF_8)) {
                eintrag(zip, "schema.json", ressourcenText("/chat-bewertungen-schema.json"));
                eintrag(zip, "README.txt", readme());
                zip.putNextEntry(new ZipEntry("bewertungen.jsonl"));
                try (BufferedWriter text = new BufferedWriter(new OutputStreamWriter(
                        new NichtSchliessend(zip), StandardCharsets.UTF_8))) {
                    snapshot.exportZeilen(zeile -> schreibeZeile(text, zeile, redaktion, salz));
                    text.flush();
                }
                zip.closeEntry();
                zip.finish();
            }
            snapshot.commit();
        }

        @Override
        public void close() {
            snapshot.close();
        }
    }

    private static ChatAuswertungSpeicher.Filter filter(LocalDate von, LocalDate bis, int minSterne, int maxSterne) {
        if (von == null || bis == null || bis.isBefore(von)
                || bis.isAfter(LocalDate.now(ZUERICH)) || von.isBefore(bis.minusMonths(12))) {
            throw new FachFehler(Fehlercode.ANFRAGE_UNGUELTIG);
        }
        return new ChatAuswertungSpeicher.Filter(von.atStartOfDay(ZUERICH).toInstant(),
                bis.plusDays(1).atStartOfDay(ZUERICH).toInstant(), minSterne, maxSterne);
    }

    private static void schreibeZeile(BufferedWriter ziel, ChatAuswertungSpeicher.ExportZeile zeile,
                                       Schwaerzung schwaerzung, byte[] salz) {
        ObjectNode json = JSON.createObjectNode();
        json.put("datum", zeile.zeit().atZone(ZUERICH).toLocalDate().toString());
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
            throw new IllegalStateException("Chat-Bewertung nicht exportierbar");
        } catch (IOException e) {
            throw new IllegalStateException("Chat-Bewertung nicht exportierbar");
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
        try (var quelle = ChatBewertungAuswertung.class.getResourceAsStream(pfad)) {
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

    private static final class NichtSchliessend extends java.io.FilterOutputStream {
        NichtSchliessend(OutputStream ziel) {
            super(ziel);
        }

        @Override
        public void close() {
        }
    }
}
