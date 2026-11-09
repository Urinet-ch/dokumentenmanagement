package ch.urinet.archiv.spike.golden;

import dev.langchain4j.service.output.OutputParsingException;
import org.junit.jupiter.api.Test;

import java.io.UncheckedIOException;
import java.net.SocketTimeoutException;

import static org.assertj.core.api.Assertions.assertThat;

/** Req 7.3: Nachfrage nur bei Parse- oder Schemafehlern. */
class KiFehlerTest {

    @Test
    void parseFehlerAuchVerschachteltSindSchemafehler() {
        assertThat(KiFehler.istSchemafehler(new OutputParsingException("x", null))).isTrue();
        assertThat(KiFehler.istSchemafehler(new RuntimeException(new OutputParsingException("x", null)))).isTrue();
        assertThat(KiFehler.istSchemafehler(new RuntimeException(
                new com.fasterxml.jackson.core.JsonParseException(null, "kaputt")))).isTrue();
    }

    @Test
    void timeoutUndHttpFehlerSindKeineSchemafehler() {
        assertThat(KiFehler.istSchemafehler(new UncheckedIOException(new SocketTimeoutException("t")))).isFalse();
        assertThat(KiFehler.istSchemafehler(new IllegalStateException("HTTP 500"))).isFalse();
    }

    @Test
    void grundNenntNurKlassen() {
        assertThat(KiFehler.grund(new UncheckedIOException(new SocketTimeoutException("Belegtext"))))
                .isEqualTo("UncheckedIOException (SocketTimeoutException)").doesNotContain("Belegtext");
        assertThat(KiFehler.grund(new IllegalStateException("geheim"))).isEqualTo("IllegalStateException");
    }
}
