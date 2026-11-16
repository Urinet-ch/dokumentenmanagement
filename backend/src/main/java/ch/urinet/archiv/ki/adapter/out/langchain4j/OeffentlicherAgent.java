package ch.urinet.archiv.ki.adapter.out.langchain4j;

import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.UserMessage;
import io.quarkiverse.langchain4j.RegisterAiService;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.UUID;

/**
 * Der öffentliche Chat-Agent (Spec 03, Aufgabe 11.3; design.md «OeffentlicherChatAgent»): eigener AI-Service mit dem
 * streamenden {@link ChatModell}, aber mit eigenem {@link OeffentlicherToolProvider} (nur {@code dokumente_suchen} und
 * {@code dokument_zeigen}) und eigenem Systemprompt. Gesprächsspeicher und Durchgang sind die des internen Chats
 * ({@link AgentSpeicher}, {@link AgentLaeufe}); die Memory-ID ist die Lauf-ID, unter der
 * {@code OeffentlicherKontextRegister} den Kontext der Anfrage hält.
 */
@ApplicationScoped
@RegisterAiService(streamingChatLanguageModelSupplier = ChatModell.class,
        chatMemoryProviderSupplier = AgentSpeicher.class,
        toolProviderSupplier = OeffentlicherToolProvider.class,
        toolHallucinationStrategy = UnbekanntesWerkzeug.class,
        maxToolCallingRoundTrips = ArchivAgent.MAX_RUNDEN)
public interface OeffentlicherAgent {

    @SystemMessage("{systemPrompt}")
    @UserMessage("{frage}")
    TokenStream antworten(@MemoryId UUID lauf, String systemPrompt, String frage);
}
