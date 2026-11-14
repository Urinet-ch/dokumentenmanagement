package ch.urinet.archiv.ki.adapter.out.langchain4j;

import ch.urinet.archiv.ki.adapter.in.werkzeug.ArchivWerkzeuge;
import ch.urinet.archiv.ki.application.chat.WerkzeugAuswahl;
import ch.urinet.archiv.ki.application.chat.WerkzeugKontext;
import ch.urinet.archiv.ki.application.chat.WerkzeugKontextRegister;
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
import org.jboss.logging.Logger;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Die Werkzeuge je Anfrage (Spec 02, Aufgabe 7.2; design.md «Chat-Agent»): Statt {@code tools = ArchivWerkzeuge.class}
 * stellt dieser {@link ToolProvider} die Liste je Durchgang zusammen. Kontext über die Memory-ID aus dem
 * {@link WerkzeugKontextRegister}, Liste aus {@link WerkzeugAuswahl#angeboten} (Rolle, später Funktionen des Mandanten),
 * Spezifikationen aus den {@code @Tool}-Methoden von {@link ArchivWerkzeuge}. Ohne Durchgang oder Kontext gibt es keine
 * Werkzeuge (fail closed). Jeder Aufruf geht über {@link AgentWerkzeug} (Runden, Ereignisse, Kennzeichnung der
 * Dokumenttexte); unbekannte oder nicht angebotene Namen erreicht kein Code ({@link UnbekanntesWerkzeug}).
 */
@ApplicationScoped
public class ArchivToolProvider implements java.util.function.Supplier<ToolProvider> {

    private static final Logger LOG = Logger.getLogger(ArchivToolProvider.class);

    private record Eintrag(ToolSpecification spezifikation, Method methode) {
    }

    /** Alle {@code @Tool}-Methoden von {@link ArchivWerkzeuge} unter ihrem Werkzeugnamen. */
    private static final Map<String, Eintrag> WERKZEUGE = werkzeuge();

    private final AgentLaeufe laeufe;
    private final WerkzeugKontextRegister register;
    private final WerkzeugAuswahl auswahl;
    private final ArchivWerkzeuge werkzeuge;
    private final ObjectMapper json;
    private final int runden;

    ArchivToolProvider(AgentLaeufe laeufe, WerkzeugKontextRegister register, WerkzeugAuswahl auswahl,
                       ArchivWerkzeuge werkzeuge, ObjectMapper json,
                       @ConfigProperty(name = "archiv.ki.chat.runden") int runden) {
        this.laeufe = laeufe;
        this.register = register;
        this.auswahl = auswahl;
        this.werkzeuge = werkzeuge;
        this.json = json;
        this.runden = runden;
    }

    @Override
    public ToolProvider get() {
        return this::bereitstellen;
    }

    ToolProviderResult bereitstellen(ToolProviderRequest anfrage) {
        Optional<AgentLauf> lauf = laeufe.finden(anfrage.chatMemoryId());
        Optional<WerkzeugKontext> kontext = register.finden(anfrage.chatMemoryId());
        Map<ToolSpecification, ToolExecutor> ergebnis = new LinkedHashMap<>();
        if (lauf.isPresent() && kontext.isPresent()) {
            for (String name : auswahl.angeboten(kontext.get())) {
                Eintrag eintrag = WERKZEUGE.get(name);
                if (eintrag == null) {
                    LOG.warnf("Werkzeug %s angeboten, aber nicht implementiert", name);
                    continue;
                }
                ToolExecutor echt = DefaultToolExecutor.builder().object(werkzeuge)
                        .originalMethod(eintrag.methode()).methodToInvoke(eintrag.methode()).build();
                ergebnis.put(eintrag.spezifikation(), new AgentWerkzeug(name, echt, lauf.get(), json, runden));
            }
        }
        return new ToolProviderResult(ergebnis);
    }

    private static Map<String, Eintrag> werkzeuge() {
        Map<String, Eintrag> alle = new LinkedHashMap<>();
        Map<String, ToolSpecification> spezifikationen = new LinkedHashMap<>();
        ToolSpecifications.toolSpecificationsFrom(ArchivWerkzeuge.class)
                .forEach(s -> spezifikationen.put(s.name(), s));
        for (Method methode : ArchivWerkzeuge.class.getDeclaredMethods()) {
            Tool tool = methode.getAnnotation(Tool.class);
            if (tool != null && spezifikationen.containsKey(tool.name())) {
                alle.put(tool.name(), new Eintrag(spezifikationen.get(tool.name()), methode));
            }
        }
        return Map.copyOf(alle);
    }
}
