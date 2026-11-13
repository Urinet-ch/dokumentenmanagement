package ch.urinet.archiv.ki.adapter.out.langchain4j;

import ch.urinet.archiv.ki.domain.ErfassungPrompt;
import dev.langchain4j.data.image.Image;
import dev.langchain4j.service.Result;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import io.quarkiverse.langchain4j.ImageUrl;
import io.quarkiverse.langchain4j.RegisterAiService;
import io.quarkiverse.langchain4j.RegisterAiService.NoChatMemoryProviderSupplier;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Ein Aufruf je Methode (Req 7.1), ohne Chat-Memory, JSON-Schema strict (design.md «KI»), wie im Golden-Lauf. Der
 * System-Prompt kommt fertig gerendert ({@link ErfassungPrompt#system}), damit die Kennung der Prompt-Fassung genau den
 * gesendeten Text abdeckt. {@code @ApplicationScoped}, weil der Worker ohne Request-Kontext läuft.
 */
@ApplicationScoped
@RegisterAiService(chatLanguageModelSupplier = KiModell.class,
        chatMemoryProviderSupplier = NoChatMemoryProviderSupplier.class)
public interface MetadatenExtraktor {

    @SystemMessage("{systemPrompt}")
    @UserMessage(ErfassungPrompt.BENUTZER)
    Result<MetadatenVorschlag> extrahiere(String systemPrompt, String qr, String text);

    /** Req 7.3: einmal mit Fehlerhinweis nachfragen. */
    @SystemMessage("{systemPrompt}")
    @UserMessage(ErfassungPrompt.BENUTZER + ErfassungPrompt.NACHFRAGE)
    Result<MetadatenVorschlag> nachfrage(String systemPrompt, String qr, String text, String fehler);

    /** Bildweg: Seitenbild statt Text (Req 7.1). */
    @SystemMessage("{systemPrompt}")
    @UserMessage(ErfassungPrompt.BENUTZER_K1)
    Result<MetadatenVorschlag> extrahiereBild(String systemPrompt, String qr, @ImageUrl Image bild);

    /** Req 7.3 für den Bildweg: Nachfrage mit demselben Bild. */
    @SystemMessage("{systemPrompt}")
    @UserMessage(ErfassungPrompt.BENUTZER_K1 + ErfassungPrompt.NACHFRAGE)
    Result<MetadatenVorschlag> nachfrageBild(String systemPrompt, String qr, @ImageUrl Image bild, String fehler);

    /** Mit Korrekturbeispielen (Req 7.9, nur mit {@code archiv.ki.beispiele.aktiv=true}). */
    @SystemMessage("{systemPrompt}")
    @UserMessage(ErfassungPrompt.BEISPIELE + ErfassungPrompt.BENUTZER)
    Result<MetadatenVorschlag> extrahiereMitBeispielen(String systemPrompt, String beispiele, String qr, String text);

    @SystemMessage("{systemPrompt}")
    @UserMessage(ErfassungPrompt.BEISPIELE + ErfassungPrompt.BENUTZER + ErfassungPrompt.NACHFRAGE)
    Result<MetadatenVorschlag> nachfrageMitBeispielen(String systemPrompt, String beispiele, String qr, String text,
                                                      String fehler);

    @SystemMessage("{systemPrompt}")
    @UserMessage(ErfassungPrompt.BEISPIELE + ErfassungPrompt.BENUTZER_K1)
    Result<MetadatenVorschlag> extrahiereBildMitBeispielen(String systemPrompt, String beispiele, String qr,
                                                           @ImageUrl Image bild);

    @SystemMessage("{systemPrompt}")
    @UserMessage(ErfassungPrompt.BEISPIELE + ErfassungPrompt.BENUTZER_K1 + ErfassungPrompt.NACHFRAGE)
    Result<MetadatenVorschlag> nachfrageBildMitBeispielen(String systemPrompt, String beispiele, String qr,
                                                          @ImageUrl Image bild, String fehler);
}
