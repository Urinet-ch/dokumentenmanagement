package ch.urinet.archiv.spike.golden;

import dev.langchain4j.data.image.Image;
import dev.langchain4j.service.Result;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import io.quarkiverse.langchain4j.ImageUrl;
import io.quarkiverse.langchain4j.RegisterAiService;
import io.quarkiverse.langchain4j.RegisterAiService.NoChatMemoryProviderSupplier;

/** Ein Aufruf je Beleg (Req 7.1), ohne Chat-Memory, JSON-Schema strict (design.md «KI»). */
@RegisterAiService(chatMemoryProviderSupplier = NoChatMemoryProviderSupplier.class)
public interface MetadatenExtraktor {

    String PROMPT_VERSION = "v3";

    @SystemMessage(Prompt.SYSTEM)
    @UserMessage(Prompt.BENUTZER)
    Result<MetadatenVorschlag> extrahiere(String mandant, String dokumentarten, String qr, String text);

    /** Req 7.3: einmal mit Fehlerhinweis nachfragen. */
    @SystemMessage(Prompt.SYSTEM)
    @UserMessage(Prompt.BENUTZER + Prompt.NACHFRAGE)
    Result<MetadatenVorschlag> nachfrage(String mandant, String dokumentarten, String qr, String text, String fehler);

    /** Variante K1 (optional): Seitenbild statt OCR-Text. */
    @SystemMessage(Prompt.SYSTEM)
    @UserMessage(Prompt.BENUTZER_K1)
    Result<MetadatenVorschlag> extrahiereBild(String mandant, String dokumentarten, String qr, @ImageUrl Image bild);

    /** Req 7.3 für K1: Nachfrage mit demselben Bild. */
    @SystemMessage(Prompt.SYSTEM)
    @UserMessage(Prompt.BENUTZER_K1 + Prompt.NACHFRAGE)
    Result<MetadatenVorschlag> nachfrageBild(String mandant, String dokumentarten, String qr, @ImageUrl Image bild,
                                             String fehler);
}
