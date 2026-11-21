package ch.urinet.archiv.ki.adapter.out.langchain4j;

import ch.urinet.archiv.ki.application.KiPort;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WissenLintSchemaTest {
    @Test
    void lintAntwortHatFestesSchemaUndLaesstKeineZusatzfelderZu() {
        String gueltig = """
                {"widersprueche":[{"seite":"a","schluessel":"q1","gegenseite":"b",
                  "gegenschluessel":"q2","text":"Hinweis"}],
                 "veraltet":[{"seite":"a","schluessel":"q1","neuere_quelle":"id","text":"Hinweis"}],
                 "links":[{"seite":"a","ziel":"b","text":"Hinweis"}]}
                """;
        Class<?> typ = WissenIngestSchemas.antworttyp(KiPort.WissensSchritt.LINT_PAAR);
        assertThat(AntwortSchema.gueltig(gueltig, typ)).isTrue();
        assertThat(AntwortSchema.gueltig("{}", typ)).isFalse();
        assertThat(AntwortSchema.gueltig(gueltig.replace("\"ziel\":\"b\"", "\"ziel\":\"b\",\"extra\":1"), typ))
                .isFalse();
        assertThat(WissenIngestSchemas.lesen(gueltig, KiPort.WissensSchritt.LINT_PAAR))
                .isEqualTo(new KiPort.LintPaar(
                        java.util.List.of(new KiPort.LintWiderspruch("a", "q1", "b", "q2", "Hinweis")),
                        java.util.List.of(new KiPort.LintVeraltet("a", "q1", "id", "Hinweis")),
                        java.util.List.of(new KiPort.LintLink("a", "b", "Hinweis"))));
    }
}
