package ch.urinet.archiv.ki.adapter.out.langchain4j;

import ch.urinet.archiv.ki.application.KiPort;
import ch.urinet.archiv.ki.domain.ArtEintrag;
import ch.urinet.archiv.ki.domain.ErfassungPrompt;
import ch.urinet.archiv.ki.domain.Feld;
import ch.urinet.archiv.ki.domain.KiKlassifikation;
import ch.urinet.archiv.ki.domain.Rohvorschlag;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.image.Image;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.internal.JsonSchemaElementUtils;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.request.json.JsonEnumSchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonSchema;
import dev.langchain4j.model.chat.request.json.JsonRawSchema;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;
import dev.langchain4j.service.output.JsonSchemas;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Typed;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Base64;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Adapter des {@link KiPort} mit Quarkus LangChain4j gegen Infomaniak AI Services (design.md «KI»; Req 7.1, 7.3): genau
 * ein Request je {@link #metadaten}, ohne Wiederholung ({@link KiModell}). Eine Antwort ausserhalb des Schemas (nicht
 * lesbar, leer oder gegen das strikte Schema ungültig, {@link AntwortSchema}) ist ein
 * {@link KiPort.Schemafehler}, jeder andere Fehler ein {@link KiPort.Ausfall}; die Gründe nennen nur Klassennamen (Req
 * 16.7). Ohne Token ({@link KiModell#bereit()}) kein Request, sondern {@link KiPort.OhneZugang}. Nur über
 * {@code KiAuswahl} wählbar.
 */
@ApplicationScoped
@Typed(LangChain4jKiAdapter.class)
public class LangChain4jKiAdapter implements KiPort {

    private final TokenZaehler zaehler;
    private final KiModell modell;

    public LangChain4jKiAdapter(TokenZaehler zaehler, KiModell modell) {
        this.zaehler = zaehler;
        this.modell = modell;
    }

    @Override
    public Antwort metadaten(Anfrage anfrage) {
        if (!modell.bereit()) {
            return new OhneZugang(new Verbrauch(modell.modell(), 0, 0));
        }
        zaehler.zuruecksetzen();
        try {
            String schema = antwortschema(anfrage);
            ChatResponse ergebnis = modell.get().chat(aufbau(anfrage, schema));
            String roh = ergebnis == null || ergebnis.aiMessage() == null ? null : ergebnis.aiMessage().text();
            TokenUsage tokenUsage = ergebnis == null ? null : ergebnis.tokenUsage();
            Verbrauch verbrauch = tokenUsage == null || tokenUsage.inputTokenCount() == null
                    || tokenUsage.outputTokenCount() == null
                    ? verbrauch(null) : verbrauch(tokenUsage, modell.modell());
            if (roh == null) {
                return new Schemafehler("leere Antwort", verbrauch);
            }
            if (!AntwortSchema.gueltig(roh, schema)) {
                return new Schemafehler("Antwort ausserhalb des Schemas", verbrauch);
            }
            return new Gueltig(roh(roh), verbrauch);
        } catch (RuntimeException e) {
            Verbrauch verbrauch = verbrauch(null);
            return KiFehler.istSchemafehler(e) ? new Schemafehler(KiFehler.grund(e), verbrauch)
                    : new Ausfall(KiFehler.grund(e), verbrauch);
        } finally {
            zaehler.zuruecksetzen();
        }
    }

    @Override
    public WissensAntwort wissensIngest(WissensAnfrage anfrage) {
        if (!modell.bereit()) {
            return new WissensOhneZugang(new Verbrauch(anfrage.modell(), 0, 0));
        }
        zaehler.zuruecksetzen();
        try {
            Class<?> antworttyp = WissenIngestSchemas.antworttyp(anfrage.schritt());
            JsonSchema schema = JsonSchema.builder()
                    .name("wissen_ingest_" + anfrage.schritt().name().toLowerCase(Locale.ROOT))
                    .rootElement(JsonSchemaElementUtils.jsonSchemaElementFrom(antworttyp))
                    .build();
            ChatRequest request = ChatRequest.builder()
                    .messages(SystemMessage.from(anfrage.systemPrompt()), UserMessage.from(anfrage.nachricht()))
                    .parameters(ChatRequestParameters.builder()
                            .modelName(anfrage.modell())
                            .temperature(0.0)
                            .topP(1.0)
                            .presencePenalty(0.0)
                            .frequencyPenalty(0.0)
                            .maxOutputTokens(anfrage.maxAusgabeTokens())
                            .responseFormat(schema)
                            .build())
                    .build();
            ChatResponse ergebnis = modell.get().chat(request);
            String roh = ergebnis == null || ergebnis.aiMessage() == null ? null : ergebnis.aiMessage().text();
            TokenUsage tokenUsage = ergebnis == null ? null : ergebnis.tokenUsage();
            Verbrauch verbrauch = tokenUsage == null || tokenUsage.inputTokenCount() == null
                    || tokenUsage.outputTokenCount() == null
                    ? new Verbrauch(anfrage.modell(),
                            anfrage.systemPrompt().getBytes(StandardCharsets.UTF_8).length
                                    + anfrage.nachricht().getBytes(StandardCharsets.UTF_8).length + 64L,
                            anfrage.maxAusgabeTokens())
                    : verbrauch(tokenUsage, anfrage.modell());
            if (!AntwortSchema.gueltig(roh, antworttyp)) {
                return new WissensSchemafehler("Antwort ausserhalb des Schemas", verbrauch);
            }
            return new WissensGueltig(WissenIngestSchemas.lesen(roh, anfrage.schritt()), verbrauch);
        } catch (RuntimeException e) {
            Verbrauch verbrauch = verbrauch(null, anfrage.modell());
            return KiFehler.istSchemafehler(e) ? new WissensSchemafehler(KiFehler.grund(e), verbrauch)
                    : new WissensAusfall(KiFehler.grund(e), verbrauch);
        } finally {
            zaehler.zuruecksetzen();
        }
    }

    private ChatRequest aufbau(Anfrage anfrage, String schema) {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(SystemMessage.from(anfrage.systemPrompt()));
        String text = (anfrage.beispiele().isEmpty() ? ""
                : ErfassungPrompt.BEISPIELE.replace("{beispiele}", anfrage.beispiele()))
                + benutzernachricht(anfrage);
        if (anfrage.fehler().isPresent()) {
            text += ErfassungPrompt.NACHFRAGE.replace("{fehler}", anfrage.fehler().get());
        }
        if (anfrage.bildJpeg().isPresent()) {
            Image bild = Image.builder().base64Data(Base64.getEncoder().encodeToString(anfrage.bildJpeg().get()))
                    .mimeType("image/jpeg").build();
            messages.add(UserMessage.from(TextContent.from(text), ImageContent.from(bild)));
        } else {
            messages.add(UserMessage.from(text));
        }
        JsonSchema antwortschema = JsonSchema.builder().name("metadaten_vorschlag")
                .rootElement(JsonRawSchema.builder().schema(schema).build()).build();
        return ChatRequest.builder().messages(messages)
                .parameters(ChatRequestParameters.builder()
                        .modelName(modell.modell())
                        .temperature(0.0)
                        .topP(1.0)
                        .presencePenalty(0.0)
                        .frequencyPenalty(0.0)
                        .responseFormat(antwortschema)
                        .build())
                .build();
    }

    private static String benutzernachricht(Anfrage anfrage) {
        String vorlage = anfrage.bildJpeg().isPresent() ? ErfassungPrompt.BENUTZER_K1 : ErfassungPrompt.BENUTZER;
        return vorlage.replace("{qr}", anfrage.qr()).replace("{text}", anfrage.text());
    }

    /** Tokens aus dem Listener (auch bei nicht parsebarer Antwort), sonst aus dem Ergebnis. */
    private Verbrauch verbrauch(TokenUsage nutzung) {
        return verbrauch(nutzung, modell.modell());
    }

    private Verbrauch verbrauch(TokenUsage nutzung, String modellname) {
        TokenZaehler.Stand stand = zaehler.stand();
        if (stand.antworten() > 0 || nutzung == null) {
            return new Verbrauch(modellname, stand.ein(), stand.aus());
        }
        return new Verbrauch(modellname, nutzung.inputTokenCount() == null ? 0 : nutzung.inputTokenCount(),
                nutzung.outputTokenCount() == null ? 0 : nutzung.outputTokenCount());
    }

    @Override
    public String antwortschema() {
        return schema();
    }

    @Override
    public String antwortschema(Anfrage anfrage) {
        return AntwortSchema.json(anfrage.arten(), anfrage.klassifikationen());
    }

    @Override
    public String antwortschemaFuerKennung(Anfrage anfrage) {
        return schemaFuerKennung(anfrage.arten(), anfrage.klassifikationen());
    }

    @Override
    public List<String> artCodes() {
        return List.of();
    }

    /** Antwortschema aus {@link MetadatenVorschlag}, wie es LangChain4j an das Modell sendet (Prompt-Hash). */
    public static String schema() {
        return JsonSchemas.jsonSchemaFrom(MetadatenVorschlag.class).map(Object::toString)
                .orElseThrow(() -> new IllegalStateException("kein JSON-Schema für MetadatenVorschlag"));
    }

    /** Art-Codes werden je Anfrage aus der Konfiguration gebildet. */
    public static List<String> codes() {
        return List.of();
    }

    static String schemaFuerKennung(List<ArtEintrag> arten, List<KiKlassifikation> klassifikationen) {
        boolean dynamischeFelder = arten.stream().flatMap(art -> art.kiFelder().stream())
                .anyMatch(feld -> !feld.equals("zugriff") && !feld.equals("veroeffentlicht"));
        boolean finanzfelder = arten.stream().anyMatch(art -> art.gruppen().contains("finanz"));
        if (arten.isEmpty() || !klassifikationen.isEmpty() || dynamischeFelder || !finanzfelder) {
            return AntwortSchema.json(arten, klassifikationen);
        }

        JsonObjectSchema basis = (JsonObjectSchema) JsonSchemaElementUtils
                .jsonSchemaElementFrom(MetadatenVorschlag.class);
        JsonEnumSchema art = JsonEnumSchema.builder()
                .description("Code der Dokumentart aus der Liste")
                .enumValues(arten.stream().map(ArtEintrag::code).toList())
                .build();
        JsonObjectSchema wurzel = basis.toBuilder().addProperty("art", art).build();
        return JsonSchema.builder().name(MetadatenVorschlag.class.getSimpleName()).rootElement(wurzel)
                .build().toString();
    }

    static Rohvorschlag roh(String inhalt) {
        JsonNode v;
        try {
            v = new ObjectMapper().readTree(inhalt);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalArgumentException("KI-Antwort ist kein gültiges JSON", e);
        }
        Map<Feld, BigDecimal> sicherheit = new EnumMap<>(Feld.class);
        JsonNode s = v.path("sicherheit");
        Map<String, BigDecimal> dynamischeSicherheit = new LinkedHashMap<>();
        if (s.isObject()) {
            s.properties().forEach(e -> {
                Feld feld = Arrays.stream(Feld.values()).filter(f -> f.schluessel().equals(e.getKey()))
                        .findFirst().orElse(null);
                if (feld != null && e.getValue().isNumber()) {
                    sicherheit.put(feld, e.getValue().decimalValue());
                } else if (e.getValue().isNumber()) {
                    dynamischeSicherheit.put(e.getKey(), e.getValue().decimalValue());
                }
            });
        }
        List<String> tags = new ArrayList<>();
        if (v.path("tags").isArray()) {
            v.path("tags").forEach(tag -> {
                if (tag.isTextual()) {
                    tags.add(tag.asText());
                }
            });
        }
        Map<String, Object> dynamischeWerte = new LinkedHashMap<>();
        v.properties().forEach(e -> {
            if ((e.getKey().startsWith("zusatz.") || e.getKey().startsWith("klassifikationen."))
                    && !e.getValue().isNull()) {
                dynamischeWerte.put(e.getKey(), new ObjectMapper().convertValue(e.getValue(), Object.class));
            }
        });
        return new Rohvorschlag(text(v, "art"), text(v, "titel"), text(v, "datum"), text(v, "partnerName"),
                text(v, "partnerUid"), text(v, "partnerIban"), text(v, "partnerEmail"), text(v, "belegnummer"),
                text(v, "richtung"), dezimal(v, "betrag"), text(v, "waehrung"), text(v, "faelligkeit"),
                text(v, "qrReferenz"), text(v, "vertraulichkeit"), tags, sicherheit, dynamischeWerte,
                dynamischeSicherheit);
    }

    private static String text(JsonNode node, String feld) {
        JsonNode wert = node.get(feld);
        return wert == null || wert.isNull() ? null : wert.asText();
    }

    private static BigDecimal dezimal(JsonNode node, String feld) {
        JsonNode wert = node.get(feld);
        return wert != null && wert.isNumber() ? wert.decimalValue() : null;
    }
}
