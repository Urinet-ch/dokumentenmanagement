package ch.urinet.archiv.wissen.adapter.out.ki;

import ch.urinet.archiv.wissen.application.WissenLintSerialisierung;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class JacksonWissenLintSerialisierung implements WissenLintSerialisierung {
    private final ObjectMapper json;

    public JacksonWissenLintSerialisierung(ObjectMapper json) {
        this.json = json;
    }

    @Override
    public String serialisieren(Object wert) {
        try {
            return json.writeValueAsString(wert);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Lint-Anfrage nicht serialisierbar", e);
        }
    }
}
