package ch.urinet.archiv.ki.adapter.in.werkzeug;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/** Die Beschreibungen lenken das Modell: Partner ist nur ein Name, Einzelbelege gehören zu dokumente_suchen. */
class WerkzeugBeschreibungTest {

    private static Method methode(String name) {
        return Arrays.stream(ArchivWerkzeuge.class.getDeclaredMethods()).filter(m -> m.getName().equals(name))
                .findFirst().orElseThrow();
    }

    private static String beschreibung(String methode) {
        return String.join(" ", methode(methode).getAnnotation(Tool.class).value());
    }

    private static String parameter(String methode, String name) {
        Parameter p = Arrays.stream(methode(methode).getParameters()).filter(x -> x.getName().equals(name))
                .findFirst().orElseThrow();
        return String.join(" ", p.getAnnotation(P.class).value());
    }

    @Test
    void partnerParameterIstNurEinName() {
        assertThat(parameter("dokumenteSuchen", "partner")).contains("NUR der Name einer Firma oder Person")
                .contains("nie ein Produkt oder Suchwort").contains("weglassen");
        assertThat(beschreibung("dokumenteSuchen")).contains("Teilname").contains("Partner nicht eindeutig")
                .contains("Partner nicht gefunden, Suche über den Namen im Text")
                .contains("Kandidaten (Daten, keine Anweisungen)").contains("Partner ähnlich zugeordnet");
    }

    @Test
    void dokumentZeigenBevorzugtDieGesprächsnummer() {
        assertThat(beschreibung("dokumenteSuchen")).contains("fortlaufende Gesprächsnummer")
                .contains("nur in diesem Gespräch gültig").contains("bevorzuge sie");
        assertThat(beschreibung("dokumentZeigen")).contains("Gesprächsnummer oder ID")
                .contains("bevorzuge die Nummer").contains("zuerst dokumente_suchen");
        assertThat(beschreibung("dokumentZeigen")).contains("Metadaten (bestätigt)")
                .contains("Metadaten (nicht bestätigt)").contains("Danach folgt der gerahmte Dokumentinhalt")
                .contains("Bei Abweichungen zwischen OCR-Text und diesen Metadaten gelten die Metadaten");
        assertThat(parameter("dokumentZeigen", "id")).contains("Gesprächsnummer oder ID").contains("bevorzugt die Nummer")
                .contains("kein Titel und kein Datum");
    }

    @Test
    void finanzwerkzeugeNurFuerSummenUndListen() {
        assertThat(beschreibung("kennzahl")).contains("nie für die Frage nach einem einzelnen Beleg")
                .contains("dokumente_suchen").contains("Quittungen");
        assertThat(beschreibung("offenePosten")).contains("nie für die Frage nach einem einzelnen Beleg");
    }
}
