package ch.urinet.archiv.ki.adapter.in.werkzeug;

import ch.urinet.archiv.ki.application.chat.DokumentZeigen;
import ch.urinet.archiv.ki.application.chat.DokumenteSuchen;
import ch.urinet.archiv.ki.application.chat.FristenAbfrage;
import ch.urinet.archiv.ki.application.chat.KennzahlAbfrage;
import ch.urinet.archiv.ki.application.chat.OffenePostenAbfrage;
import ch.urinet.archiv.ki.application.chat.VollstaendigkeitAbfrage;
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
                .containsExactlyInAnyOrder("dokumente_suchen", "dokument_zeigen", "fristen", "kennzahl", "offene_posten",
                        "vollstaendigkeit");
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
        assertThat(werkzeuge.vollstaendigkeit("lauf", 2026)).isEqualTo("{\"fehler\":\"kein Kontext\"}");
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
        assertThat(werkzeuge.vollstaendigkeit("lauf", null)).isEqualTo(fehler("geschaeftsjahr"));
        assertThat(werkzeuge.vollstaendigkeit("lauf", 1999)).isEqualTo(fehler("geschaeftsjahr"));
        assertThat(werkzeuge.vollstaendigkeit("lauf", 20260)).isEqualTo(fehler("geschaeftsjahr"));
        assertThat(werkzeuge.vollstaendigkeit("lauf", java.time.Year.now().getValue() + 2))
                .isEqualTo(fehler("geschaeftsjahr"));
    }

    @Test
    void ungueltigeFinanzparameterSindFehlerOhneWert() {
        ArchivWerkzeuge werkzeuge = werkzeuge();
        register.festhalten("lauf", kontext());
        assertThat(werkzeuge.kennzahl("lauf", null, "2026-01-01", "2026-12-31", null, null))
                .isEqualTo(fehler("messgroesse"));
        assertThat(werkzeuge.kennzahl("lauf", "GEWINN", "2026-01-01", "2026-12-31", null, null))
                .isEqualTo(fehler("messgroesse"));
        assertThat(werkzeuge.kennzahl("lauf", "EINNAHMEN", null, "2026-12-31", null, null)).isEqualTo(fehler("von"));
        assertThat(werkzeuge.kennzahl("lauf", "EINNAHMEN", "2026-12-31", "2026-01-01", null, null))
                .isEqualTo(fehler("von"));
        assertThat(werkzeuge.kennzahl("lauf", "EINNAHMEN", "2026-01-01", "2036-01-02", null, null))
                .as("mehr als 10 Jahre").isEqualTo(fehler("bis"));
        assertThat(werkzeuge.kennzahl("lauf", "EINNAHMEN", "2026-01-01", "2026-12-31", "WOCHE", null))
                .isEqualTo(fehler("gruppierung"));
        assertThat(werkzeuge.kennzahl("lauf", "EINNAHMEN", "2026-01-01", "2026-12-31", null, "ZAHLUNG"))
                .isEqualTo(fehler("basis"));
        assertThat(werkzeuge.offenePosten("lauf", "31.02.2026", null)).isEqualTo(fehler("stichtag"));
        assertThat(werkzeuge.offenePosten("lauf", null, "BEIDE")).isEqualTo(fehler("richtung"));
    }

    @Test
    void unerwarteterFehlerKommtAlsFehlerOhneAusnahme() {
        ArchivWerkzeuge werkzeuge = werkzeuge();
        register.festhalten("lauf", kontext());
        // gültige Parameter: der fehlende Port führt zu einer Ausnahme, die nicht bis zum Modell geht
        assertThat(werkzeuge.fristen("lauf", "2026-01-01", "2029-01-01"))
                .isEqualTo("{\"fehler\":\"Werkzeug nicht verfügbar\"}");
    }

    /** Verdrahtung von {@code GeseheneIds}: Suche, Fristen, offene Posten und Kennzahl merken IDs; zeigen ordnet zu. */
    @Test
    void falschAbgeschriebeneIdWirdEinemGeseheneTrefferZugeordnet() {
        java.util.UUID id = java.util.UUID.fromString("4f0c2a7e-1b3d-4c5e-8f60-123456789abc");
        java.util.UUID ausFristen = java.util.UUID.fromString("a1b2c3d4-5e6f-4a7b-8c9d-0e1f2a3b4c5d");
        java.util.UUID ausPosten = java.util.UUID.fromString("7d8e9f0a-1b2c-4d3e-9f4a-5b6c7d8e9f01");
        java.util.UUID ausKennzahl = java.util.UUID.fromString("e5f60718-293a-4b4c-a5d6-e7f8091a2b3c");
        java.util.UUID fremd = java.util.UUID.fromString("99999999-8888-4777-8666-555555555555");
        java.util.List<String> gezeigt = new java.util.ArrayList<>();
        ArchivWerkzeuge werkzeuge = werkzeuge(
                new DokumenteSuchen(null, null, null, null, null, null, new BigDecimal("0.6")) {
                    @Override
                    public Antwort suchen(WerkzeugKontext kontext, Anfrage a) {
                        return new Antwort(List.of(new Treffer(id.toString(), "T", "A", null, null, null, null, true,
                                "/dokument/" + id, null)), 1, null);
                    }
                },
                new DokumentZeigen(null, null, null, null) {
                    @Override
                    public Ansicht zeigen(WerkzeugKontext kontext, String idText) {
                        gezeigt.add(idText);
                        return null;
                    }
                },
                new FristenAbfrage(null, null, null) {
                    @Override
                    public Antwort fristen(WerkzeugKontext kontext, String von, String bis) {
                        return new Antwort(List.of(new Frist("2026-12-01", "Kündigung", "VERTRAG",
                                new Dokument(ausFristen.toString(), "V", "/dokument/" + ausFristen), null)), 1, null, null);
                    }
                },
                new OffenePostenAbfrage(null, null, null, null) {
                    @Override
                    public Antwort offenePosten(WerkzeugKontext kontext, String stichtag, String richtung) {
                        return new Antwort("2026-11-15", "EINNAHME", null, List.of(new Posten(ausPosten.toString(), "R1",
                                "P", "CHF 1.00", "2026-11-20", false, "UNBEKANNT", "/dokument/" + ausPosten)), 1, null,
                                null);
                    }
                },
                new KennzahlAbfrage(null, null, null, null) {
                    @Override
                    public Antwort kennzahl(WerkzeugKontext kontext, String m, String von, String bis, String g,
                                            String b) {
                        return new Antwort("EINNAHMEN", von, bis, "KEINE", "BELEGDATUM", List.of(), null,
                                null, List.of(ausKennzahl.toString()), 0L, null);
                    }
                });
        register.festhalten("lauf", kontext());
        werkzeuge.dokumenteSuchen("lauf", "Rechnung", null, null, null, null, null, null);
        werkzeuge.fristen("lauf", "2026-01-01", "2026-12-31");
        werkzeuge.offenePosten("lauf", null, null);
        werkzeuge.kennzahl("lauf", "EINNAHMEN", "2026-01-01", "2026-12-31", null, null);

        werkzeuge.dokumentZeigen("lauf", verfaelscht(id));
        werkzeuge.dokumentZeigen("lauf", verfaelscht(ausFristen));
        werkzeuge.dokumentZeigen("lauf", verfaelscht(ausPosten));
        werkzeuge.dokumentZeigen("lauf", verfaelscht(ausKennzahl));
        werkzeuge.dokumentZeigen("lauf", fremd.toString());
        werkzeuge.dokumentZeigen("lauf", verfaelscht(fremd));

        assertThat(gezeigt).containsExactly(id.toString(), ausFristen.toString(), ausPosten.toString(),
                ausKennzahl.toString(), fremd.toString(), verfaelscht(fremd));
    }

    /** Ändert das letzte Zeichen. */
    private static String verfaelscht(java.util.UUID id) {
        String text = id.toString();
        return text.substring(0, 35) + (text.charAt(35) == '0' ? '1' : '0');
    }

    private static String fehler(String parameter) {
        return "{\"fehler\":\"ungültiger Parameter: " + parameter + "\"}";
    }

    private static WerkzeugKontext kontext() {
        return WerkzeugKontext.fuer(URINET, new Benutzer("fabian", Rolle.INHABER), null);
    }

    private ArchivWerkzeuge werkzeuge() {
        return werkzeuge(new DokumenteSuchen(null, null, null, null, null, null, new BigDecimal("0.6")),
                new DokumentZeigen(null, null, null, null), new FristenAbfrage(null, null, null),
                new OffenePostenAbfrage(null, null, null, null), new KennzahlAbfrage(null, null, null, null));
    }

    private ArchivWerkzeuge werkzeuge(DokumenteSuchen suchen, DokumentZeigen zeigen, FristenAbfrage fristen,
                                      OffenePostenAbfrage offenePosten, KennzahlAbfrage kennzahl) {
        MandantKontext mandanten = new MandantKontext(null, null, null) {
            @Override
            public <T> T mit(MandantCode code, Supplier<T> arbeit) {
                return arbeit.get();
            }
        };
        Werkzeuglauf lauf = new Werkzeuglauf(register, new WerkzeugAuswahl(m -> true), mandanten);
        return new ArchivWerkzeuge(lauf, suchen, zeigen, fristen, kennzahl, offenePosten,
                new VollstaendigkeitAbfrage(null, null, null), new ObjectMapper());
    }
}
