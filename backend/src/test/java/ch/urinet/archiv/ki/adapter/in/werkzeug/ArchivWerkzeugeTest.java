package ch.urinet.archiv.ki.adapter.in.werkzeug;

import ch.urinet.archiv.ki.application.chat.DokumentZeigen;
import ch.urinet.archiv.ki.application.chat.DokumenteSuchen;
import ch.urinet.archiv.ki.application.chat.FristenAbfrage;
import ch.urinet.archiv.ki.application.chat.WerkzeugAuswahl;
import ch.urinet.archiv.ki.application.chat.WerkzeugKontext;
import ch.urinet.archiv.ki.application.chat.WerkzeugKontextRegister;
import ch.urinet.archiv.ki.application.chat.Werkzeuglauf;
import ch.urinet.archiv.mandant.application.MandantKontext;
import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.sicherheit.domain.Benutzer;
import ch.urinet.archiv.sicherheit.domain.Rolle;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolMemoryId;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spec 02, Req 5.1: kein Werkzeug-Parameter für Mandant, Benutzer, Rolle oder Sicht; ohne Kontext ein Fehler ohne
 * Datenbankzugriff; ungültige Parameter als Fehler ohne Wert. Die Dienste haben hier keine Datenbank (ihre Ports sind
 * {@code null}): Jede Antwort entsteht also vor dem ersten Zugriff.
 */
class ArchivWerkzeugeTest {

    private static final Pattern VERBOTEN = Pattern.compile(
            "mandant|tenant|benutzer|user|rolle|role|sicht|vertraulich|kontext|identit|principal");
    private static final MandantCode URINET = new MandantCode("urinet");

    private final WerkzeugKontextRegister register = new WerkzeugKontextRegister();

    @Test
    void keinWerkzeugParameterFuerMandantBenutzerRolleOderSicht() {
        List<Method> werkzeuge = Arrays.stream(ArchivWerkzeuge.class.getDeclaredMethods())
                .filter(m -> m.isAnnotationPresent(Tool.class)).toList();
        assertThat(werkzeuge).extracting(m -> m.getAnnotation(Tool.class).name())
                .containsExactlyInAnyOrder("dokumente_suchen", "dokument_zeigen", "fristen");
        for (Method methode : werkzeuge) {
            int memoryIds = 0;
            for (Parameter parameter : methode.getParameters()) {
                if (parameter.isAnnotationPresent(ToolMemoryId.class)) {
                    memoryIds++;
                    continue;
                }
                String fall = methode.getName() + "." + parameter.getName();
                assertThat(parameter.isAnnotationPresent(P.class)).as(fall + " hat @P").isTrue();
                assertThat(parameter.getName().toLowerCase(Locale.ROOT)).as(fall).doesNotContainPattern(VERBOTEN);
                assertThat(parameter.getAnnotation(P.class).value().toLowerCase(Locale.ROOT)).as(fall)
                        .doesNotContainPattern(VERBOTEN);
                assertThat(parameter.getType().getPackageName()).as(fall).startsWith("java.lang");
            }
            assertThat(memoryIds).as(methode.getName() + ": genau eine @ToolMemoryId").isEqualTo(1);
            assertThat(methode.getAnnotation(Tool.class).value()).as(methode.getName()).isNotEmpty();
            assertThat(methode.getReturnType()).isEqualTo(String.class);
        }
    }

    @Test
    void ohneKontextFehlerOhneDatenbankzugriff() {
        ArchivWerkzeuge werkzeuge = werkzeuge();
        assertThat(werkzeuge.dokumenteSuchen("lauf", "Rechnung", null, null, null, null, null, null))
                .isEqualTo("{\"fehler\":\"kein Kontext\"}");
        assertThat(werkzeuge.dokumentZeigen("lauf", "00000000-0000-4000-8000-000000000001"))
                .isEqualTo("{\"fehler\":\"kein Kontext\"}");
        assertThat(werkzeuge.fristen("lauf", "2026-01-01", "2026-12-31")).isEqualTo("{\"fehler\":\"kein Kontext\"}");
        assertThat(werkzeuge.fristen(null, "2026-01-01", "2026-12-31")).isEqualTo("{\"fehler\":\"kein Kontext\"}");
        register.festhalten("lauf", kontext());
        register.entfernen("lauf");
        assertThat(werkzeuge.fristen("lauf", "2026-01-01", "2026-12-31")).isEqualTo("{\"fehler\":\"kein Kontext\"}");
    }

    @Test
    void ungueltigeParameterSindFehlerOhneWert() {
        ArchivWerkzeuge werkzeuge = werkzeuge();
        register.festhalten("lauf", kontext());
        String geheim = "Geheimwort" + "x".repeat(200);
        assertThat(werkzeuge.dokumenteSuchen("lauf", "  ", null, null, null, null, null, null))
                .isEqualTo(fehler("frage"));
        assertThat(werkzeuge.dokumenteSuchen("lauf", geheim, null, null, null, null, null, null))
                .isEqualTo(fehler("frage")).doesNotContain("Geheimwort");
        assertThat(werkzeuge.dokumenteSuchen("lauf", "Rech\u0007nung", null, null, null, null, null, null))
                .isEqualTo(fehler("frage"));
        assertThat(werkzeuge.dokumenteSuchen("lauf", "Rechnung", null, null, null, null, null, 0))
                .isEqualTo(fehler("anzahl"));
        assertThat(werkzeuge.dokumenteSuchen("lauf", "Rechnung", null, null, null, null, null, 11))
                .isEqualTo(fehler("anzahl"));
        assertThat(werkzeuge.dokumenteSuchen("lauf", "Rechnung", null, null, "2026-13-01", null, null, null))
                .isEqualTo(fehler("von"));
        assertThat(werkzeuge.dokumenteSuchen("lauf", "Rechnung", null, null, "2026-05-01", "2026-04-30", null,
                null)).isEqualTo(fehler("von"));
        assertThat(werkzeuge.dokumenteSuchen("lauf", "Rechnung", null, "x".repeat(201), null, null, null, null))
                .isEqualTo(fehler("partner"));
        assertThat(werkzeuge.fristen("lauf", null, "2026-12-31")).isEqualTo(fehler("von"));
        assertThat(werkzeuge.fristen("lauf", "2026-01-01", "31.02.2026")).isEqualTo(fehler("bis"));
        assertThat(werkzeuge.fristen("lauf", "2026-12-31", "2026-01-01")).isEqualTo(fehler("von"));
        assertThat(werkzeuge.fristen("lauf", "2026-01-01", "2029-01-02")).as("mehr als 3 Jahre")
                .isEqualTo(fehler("bis"));
    }

    @Test
    void unerwarteterFehlerKommtAlsFehlerOhneAusnahme() {
        ArchivWerkzeuge werkzeuge = werkzeuge();
        register.festhalten("lauf", kontext());
        // gültige Parameter: der fehlende Port führt zu einer Ausnahme, die nicht bis zum Modell geht
        assertThat(werkzeuge.fristen("lauf", "2026-01-01", "2029-01-01"))
                .isEqualTo("{\"fehler\":\"Werkzeug nicht verfügbar\"}");
    }

    private static String fehler(String parameter) {
        return "{\"fehler\":\"ungültiger Parameter: " + parameter + "\"}";
    }

    private static WerkzeugKontext kontext() {
        return WerkzeugKontext.fuer(URINET, new Benutzer("fabian", Rolle.INHABER), null);
    }

    private ArchivWerkzeuge werkzeuge() {
        MandantKontext mandanten = new MandantKontext(null, null, null) {
            @Override
            public <T> T mit(MandantCode code, Supplier<T> arbeit) {
                return arbeit.get();
            }
        };
        Werkzeuglauf lauf = new Werkzeuglauf(register, new WerkzeugAuswahl(), mandanten);
        return new ArchivWerkzeuge(lauf, new DokumenteSuchen(null, null, null, null, null, new BigDecimal("0.6")),
                new DokumentZeigen(null, null, null, null), new FristenAbfrage(null, null, null), new ObjectMapper());
    }
}
