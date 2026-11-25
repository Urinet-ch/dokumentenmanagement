package ch.urinet.archiv.ki.adapter.out.langchain4j;

import ch.urinet.archiv.ki.adapter.in.werkzeug.OeffentlicheWerkzeuge;
import ch.urinet.archiv.ki.adapter.in.werkzeug.OeffentlicheWissenWerkzeuge;
import ch.urinet.archiv.ki.application.oeffentlich.OeffentlicherKontextRegister;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.agent.tool.ToolSpecifications;
import dev.langchain4j.service.tool.DefaultToolExecutor;
import dev.langchain4j.service.tool.ToolExecutor;
import dev.langchain4j.service.tool.ToolProvider;
import dev.langchain4j.service.tool.ToolProviderRequest;
import dev.langchain4j.service.tool.ToolProviderResult;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Die Werkzeuge je öffentlicher Frage: entweder die Dokumentwerkzeuge aus {@link OeffentlicheWerkzeuge} oder die
 * getrennten Wissenswerkzeuge aus {@link OeffentlicheWissenWerkzeuge}, nie die des internen Chats.
 * Ohne Durchgang oder Kontext gibt es keine Werkzeuge (fail closed). Jeder Aufruf geht über {@link AgentWerkzeug} (Runden,
 * Ereignisse, Kennzeichnung der Dokumenttexte); unbekannte Namen erreicht kein Code ({@link UnbekanntesWerkzeug}).
 */
@ApplicationScoped
public class OeffentlicherToolProvider implements java.util.function.Supplier<ToolProvider> {

    private record Eintrag(ToolSpecification spezifikation, Method methode) {
    }

    private static final Map<String, Eintrag> WERKZEUGE = werkzeuge(OeffentlicheWerkzeuge.class);
    private static final Map<String, Eintrag> WISSEN_WERKZEUGE = werkzeuge(OeffentlicheWissenWerkzeuge.class);

    private final AgentLaeufe laeufe;
    private final OeffentlicherKontextRegister register;
    private final OeffentlicheWerkzeuge werkzeuge;
    private final OeffentlicheWissenWerkzeuge wissen;
    private final ObjectMapper json;
    private final int runden;

    OeffentlicherToolProvider(AgentLaeufe laeufe, OeffentlicherKontextRegister register,
                              OeffentlicheWerkzeuge werkzeuge, OeffentlicheWissenWerkzeuge wissen, ObjectMapper json,
                              @ConfigProperty(name = "archiv.webseite.chat.runden") int runden) {
        this.laeufe = laeufe;
        this.register = register;
        this.werkzeuge = werkzeuge;
        this.wissen = wissen;
        this.json = json;
        this.runden = runden;
    }

    @Override
    public ToolProvider get() {
        return this::bereitstellen;
    }

    ToolProviderResult bereitstellen(ToolProviderRequest anfrage) {
        Optional<AgentLauf> lauf = laeufe.finden(anfrage.chatMemoryId());
        Map<ToolSpecification, ToolExecutor> ergebnis = new LinkedHashMap<>();
        var kontext = register.finden(anfrage.chatMemoryId());
        if (lauf.isPresent() && kontext.isPresent()) {
            boolean wissenschat = kontext.orElseThrow().wissen() != null;
            Map<String, Eintrag> auswahl = wissenschat ? WISSEN_WERKZEUGE : WERKZEUGE;
            Object instanz = wissenschat ? wissen : werkzeuge;
            for (Map.Entry<String, Eintrag> e : auswahl.entrySet()) {
                Eintrag eintrag = e.getValue();
                ToolExecutor echt = DefaultToolExecutor.builder().object(instanz)
                        .originalMethod(eintrag.methode()).methodToInvoke(eintrag.methode()).build();
                ergebnis.put(eintrag.spezifikation(), new AgentWerkzeug(e.getKey(), echt, lauf.get(), json, runden));
            }
        }
        return new ToolProviderResult(ergebnis);
    }

    private static Map<String, Eintrag> werkzeuge(Class<?> klasse) {
        Map<String, Eintrag> alle = new LinkedHashMap<>();
        Map<String, ToolSpecification> spezifikationen = new LinkedHashMap<>();
        ToolSpecifications.toolSpecificationsFrom(klasse)
                .forEach(s -> spezifikationen.put(s.name(), s));
        for (Method methode : klasse.getDeclaredMethods()) {
            Tool tool = methode.getAnnotation(Tool.class);
            if (tool != null && spezifikationen.containsKey(tool.name())) {
                alle.put(tool.name(), new Eintrag(spezifikationen.get(tool.name()), methode));
            }
        }
        return Map.copyOf(alle);
    }
}
