package ch.urinet.archiv.spike.golden;

import dev.langchain4j.data.image.Image;
import dev.langchain4j.service.Result;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import io.quarkiverse.langchain4j.ImageUrl;
import io.quarkiverse.langchain4j.RegisterAiService;
import io.quarkiverse.langchain4j.RegisterAiService.NoChatMemoryProviderSupplier;

/**
 * Ein Aufruf je Beleg (Req 7.1), ohne Chat-Memory, JSON-Schema strict (design.md «KI»). Der System-Prompt kommt fertig
 * gerendert ({@link Prompt#system}), damit der Prompt-Hash genau den gesendeten Text abdeckt.
 */
@RegisterAiService(chatLanguageModelSupplier = KiModell.class, chatMemoryProviderSupplier = NoChatMemoryProviderSupplier.class)
public interface MetadatenExtraktor {

    String PROMPT_VERSION = "v5";

    @SystemMessage("{systemPrompt}")
    @UserMessage(Prompt.BENUTZER)
    Result<MetadatenVorschlag> extrahiere(String systemPrompt, String qr, String text);

    /** Req 7.3: einmal mit Fehlerhinweis nachfragen. */
    @SystemMessage("{systemPrompt}")
    @UserMessage(Prompt.BENUTZER + Prompt.NACHFRAGE)
    Result<MetadatenVorschlag> nachfrage(String systemPrompt, String qr, String text, String fehler);

    /** Bildweg: Seitenbild statt OCR-Text. */
    @SystemMessage("{systemPrompt}")
    @UserMessage(Prompt.BENUTZER_K1)
    Result<MetadatenVorschlag> extrahiereBild(String systemPrompt, String qr, @ImageUrl Image bild);

    /** Req 7.3 für den Bildweg: Nachfrage mit demselben Bild. */
    @SystemMessage("{systemPrompt}")
    @UserMessage(Prompt.BENUTZER_K1 + Prompt.NACHFRAGE)
    Result<MetadatenVorschlag> nachfrageBild(String systemPrompt, String qr, @ImageUrl Image bild, String fehler);
}
