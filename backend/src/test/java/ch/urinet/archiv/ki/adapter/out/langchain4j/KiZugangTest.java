package ch.urinet.archiv.ki.adapter.out.langchain4j;

import ch.urinet.archiv.ki.application.KiPort;
import ch.urinet.archiv.ki.domain.ArtEintrag;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Token und strikte Schemaprüfung (Review PR #93): kein Aufruf ohne Token an Infomaniak, Antwort nur nach Schema. */
class KiZugangTest {

    private static final String INFOMANIAK = "https://api.infomaniak.com/1/ai/123/openai/v1";

    @Test
    void tokenAusDerSecretDateiGehtVor() {
        assertThat(KiModell.zugang(Optional.of(" geheim\n"), INFOMANIAK)).contains("geheim");
    }

    @Test
    void ohneTokenNurFuerEinLokalesZielEinPlatzhalter() {
        assertThat(KiModell.zugang(Optional.empty(), "http://localhost:1234/v1")).contains(KiModell.LOKAL_OHNE_TOKEN);
        assertThat(KiModell.zugang(Optional.of("  "), "http://127.0.0.1:1234/v1")).contains(KiModell.LOKAL_OHNE_TOKEN);
        assertThat(KiModell.zugang(Optional.empty(), INFOMANIAK)).isEmpty();
        assertThat(KiModell.zugang(Optional.of(""), "https://localhost.example.ch/v1")).isEmpty();
        assertThat(KiModell.zugang(Optional.empty(), "kein url")).isEmpty();
    }

    @Test
    void ohneZugangKeinRequest() {
        KiModell modell = new KiModell();
        modell.basisUrl = INFOMANIAK;
        modell.modell = "google/gemma-4-31B-it";
        modell.token = Optional.empty();
        LangChain4jKiAdapter adapter = new LangChain4jKiAdapter(new TokenZaehler(), modell,
                ch.urinet.archiv.wissen.FesteWissensfreischaltung.ein());

        KiPort.Antwort antwort = adapter.metadaten(new KiPort.Anfrage("System", "", "Text", Optional.empty(),
                Optional.empty()));

        assertThat(antwort).isInstanceOf(KiPort.OhneZugang.class);
        assertThat(antwort.verbrauch().tokensEin()).isZero();
        assertThatThrownBy(modell::get).isInstanceOf(IllegalStateException.class);
    }

    /** Spec 04, Req 22.9: Ingest, Neuerzeugung und Lint rufen das Modell nicht mehr auf, sobald Wissen aus ist. */
    @Test
    @org.junit.jupiter.api.Tag("req-22.9")
    void ohneFreischaltungKeinWissensaufruf() {
        java.util.concurrent.atomic.AtomicInteger aufrufe = new java.util.concurrent.atomic.AtomicInteger();
        KiModell modell = new KiModell() {
            @Override
            public boolean bereit() {
                return true;
            }

            @Override
            public dev.langchain4j.model.chat.ChatModel get() {
                return new dev.langchain4j.model.chat.ChatModel() {
                    @Override
                    public dev.langchain4j.model.chat.response.ChatResponse doChat(
                            dev.langchain4j.model.chat.request.ChatRequest anfrage) {
                        aufrufe.incrementAndGet();
                        throw new AssertionError("kein Modellaufruf ohne Freischaltung");
                    }
                };
            }
        };
        LangChain4jKiAdapter adapter = new LangChain4jKiAdapter(new TokenZaehler(), modell,
                ch.urinet.archiv.wissen.FesteWissensfreischaltung.aus());

        assertThatThrownBy(() -> adapter.wissensIngest(new KiPort.WissensAnfrage(KiPort.WissensSchritt.LINT_PAAR,
                "modell", "System", "Nachricht", 100)))
                .isInstanceOf(ch.urinet.archiv.wissen.application.WissenAusgeschaltet.class);
        assertThat(aufrufe).hasValue(0);
    }

    @Test
    void antwortNurNachDemStriktenSchema() {
        String gueltig = """
                {"art":"QUITTUNG","titel":"T","datum":"2026-03-10","partnerName":null,"partnerUid":null,
                "partnerIban":null,"partnerEmail":null,"belegnummer":null,"richtung":"AUSGABE","betrag":4.5,
                "waehrung":"CHF","faelligkeit":null,"qrReferenz":null,"vertraulichkeit":"INTERN","tags":[],
                "sicherheit":{"art":0.9,"titel":0.9,"datum":0.9,"partnerName":0,"belegnummer":0,"richtung":0.9,
                "betrag":0.9,"waehrung":0.9,"faelligkeit":0,"vertraulichkeit":0.9}}""";

        String schema = AntwortSchema.json(List.of(new ArtEintrag("QUITTUNG", "Quittung", null, "INTERN",
                List.of("finanz"), false, true, List.of(), null, null, List.of(), null, 0,
                "{\"type\":\"object\",\"properties\":{},\"additionalProperties\":false}", java.util.Map.of(),
                List.of())), List.of());

        assertThat(AntwortSchema.gueltig(gueltig, schema)).isTrue();
        assertThat(AntwortSchema.gueltig("{}", schema)).isFalse();
        assertThat(AntwortSchema.gueltig(gueltig.replace("\"qrReferenz\":null,", ""), schema)).isFalse();
        assertThat(AntwortSchema.gueltig(gueltig.replace("{\"art\"", "{\"extra\":1,\"art\""), schema)).isFalse();
        assertThat(AntwortSchema.gueltig(gueltig.replace("\"QUITTUNG\"", "\"RECHNUNG\""), schema)).isFalse();
        assertThat(AntwortSchema.gueltig("kein JSON", schema)).isFalse();
        assertThat(AntwortSchema.gueltig(null, schema)).isFalse();
        assertThat(AntwortSchema.gueltig(" ", schema)).isFalse();
    }
}
