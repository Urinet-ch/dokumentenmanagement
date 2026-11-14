package ch.urinet.archiv.ki.adapter.out.langchain4j;

import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.UserMessage;
import io.quarkiverse.langchain4j.RegisterAiService;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.UUID;

/**
 * Der Chat-Agent (Spec 02, Aufgabe 7.2; design.md «Chat-Agent»): AI-Service mit dem streamenden {@link ChatModell}.
 * Der Systemprompt kommt fertig gerendert ({@code ChatSystemPrompt}, Datum und Mandantenname), die Werkzeuge je Anfrage
 * über den {@link ArchivToolProvider} (nicht über {@code tools = …}, weil die Liste je Benutzer verschieden ist), der
 * Gesprächsspeicher des Durchgangs über {@link AgentSpeicher}. Die Memory-ID ist die Lauf-ID, unter der
 * {@code WerkzeugKontextRegister} den Kontext der Anfrage hält. {@code maxToolCallingRoundTrips} ist nur die harte
 * Obergrenze der Bibliothek; die Grenze {@code archiv.ki.chat.runden} setzt {@link AgentWerkzeug} früher durch.
 * {@code @ApplicationScoped}, weil der Durchgang auf einem eigenen Thread ohne Request-Kontext läuft.
 */
@ApplicationScoped
@RegisterAiService(streamingChatLanguageModelSupplier = ChatModell.class,
        chatMemoryProviderSupplier = AgentSpeicher.class,
        toolProviderSupplier = ArchivToolProvider.class,
        toolHallucinationStrategy = UnbekanntesWerkzeug.class,
        maxToolCallingRoundTrips = ArchivAgent.MAX_RUNDEN)
public interface ArchivAgent {

    /** Harte Obergrenze der Bibliothek; {@code archiv.ki.chat.runden} muss darunter liegen (Prüfung beim Start). */
    int MAX_RUNDEN = 10;

    @SystemMessage("{systemPrompt}")
    @UserMessage("{frage}")
    TokenStream antworten(@MemoryId UUID lauf, String systemPrompt, String frage);
}
