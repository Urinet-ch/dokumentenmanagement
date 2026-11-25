package ch.urinet.archiv.ki.adapter.out.langchain4j;

import ch.urinet.archiv.integration.domain.webseite.Lesezugriff;
import ch.urinet.archiv.integration.domain.webseite.Zugriffsstufe;
import ch.urinet.archiv.ki.adapter.in.werkzeug.OeffentlicheWerkzeuge;
import ch.urinet.archiv.ki.adapter.in.werkzeug.OeffentlicheWissenWerkzeuge;
import ch.urinet.archiv.ki.application.chat.ChatAbbruch;
import ch.urinet.archiv.ki.application.oeffentlich.OeffentlicherKontext;
import ch.urinet.archiv.ki.application.oeffentlich.OeffentlicherKontextRegister;
import ch.urinet.archiv.ki.application.oeffentlich.OeffentlichesWissenDurchgang;
import ch.urinet.archiv.mandant.domain.MandantCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.service.tool.ToolProviderRequest;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
class OeffentlicherToolProviderTest {
    @Test
    void oeffentlicherWissenschatKenntKeineArchivwerkzeuge() {
        var id = UUID.randomUUID();
        var mandant = new MandantCode("urinet");
        var zugriff = new Lesezugriff("probe", Zugriffsstufe.OEFFENTLICH, Zugriffsstufe.OEFFENTLICH, Optional.empty());
        var laeufe = new AgentLaeufe();
        laeufe.festhalten(new AgentLauf(id, mandant, List.of(), e -> { }, new ChatAbbruch(), 4));
        var register = new OeffentlicherKontextRegister();
        var json = new ObjectMapper();
        var provider = new OeffentlicherToolProvider(laeufe, register,
                new OeffentlicheWerkzeuge(null, null, json),
                new OeffentlicheWissenWerkzeuge(null, null, null, null, json), json, 3);
        var anfrage = new ToolProviderRequest(id, UserMessage.from("Probe"));
        assertThat(provider.bereitstellen(anfrage).tools()).isEmpty();
        register.festhalten(id, new OeffentlicherKontext(mandant, zugriff, new OeffentlichesWissenDurchgang()));
        assertThat(provider.bereitstellen(anfrage).tools().keySet()).extracting(s -> s.name())
                .containsExactlyInAnyOrder("wissen_index_oeffentlich", "wissen_seite_zeigen_oeffentlich");
        register.festhalten(id, new OeffentlicherKontext(mandant, zugriff));
        assertThat(provider.bereitstellen(anfrage).tools().keySet()).extracting(s -> s.name())
                .containsExactlyInAnyOrder("dokumente_suchen", "dokument_zeigen");
    }
}
