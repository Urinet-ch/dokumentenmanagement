package ch.urinet.archiv.gemeinsam;

import ch.urinet.archiv.ablage.adapter.out.AblageAuswahl;
import ch.urinet.archiv.ablage.adapter.out.stub.AblageStub;
import ch.urinet.archiv.integration.adapter.out.IntegrationAuswahl;
import ch.urinet.archiv.integration.adapter.out.stub.IntegrationStub;
import ch.urinet.archiv.ki.adapter.out.ChatAuswahl;
import ch.urinet.archiv.ki.adapter.out.EmbeddingAuswahl;
import ch.urinet.archiv.ki.adapter.out.chat.ChatNichtVerfuegbar;
import ch.urinet.archiv.ki.adapter.out.KiAuswahl;
import ch.urinet.archiv.ki.adapter.out.langchain4j.LangChain4jEmbeddingAdapter;
import ch.urinet.archiv.ki.adapter.out.stub.ChatAgentStub;
import ch.urinet.archiv.ki.adapter.out.stub.EmbeddingStub;
import ch.urinet.archiv.ki.adapter.out.stub.KiStub;
import ch.urinet.archiv.verarbeitung.adapter.out.OcrAuswahl;
import ch.urinet.archiv.verarbeitung.adapter.out.stub.OcrStub;
import ch.urinet.archiv.gemeinsam.domain.PortAuswahl;
import io.smallrye.config.PropertiesConfigSource;
import io.smallrye.config.SmallRyeConfig;
import io.smallrye.config.SmallRyeConfigBuilder;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Dev-Stubs der Driven Ports (Req 16.13, design.md «KI»): Schalter archiv.&lt;port&gt;.stub, nur im Profil dev an. */
class StubSchalterTest {

    private static final List<String> PORTS = List.of("ablage", "ki", "ocr", "integration");

    @ParameterizedTest
    @CsvSource({"dev,true", "test,false", "prod,false"})
    void stubsNurImProfilDev(String profil, boolean erwartet) throws IOException {
        SmallRyeConfig config = new SmallRyeConfigBuilder()
                .withProfile(profil)
                .addDefaultInterceptors()
                .withSources(new PropertiesConfigSource(
                        getClass().getClassLoader().getResource("application.properties")))
                .build();
        for (String port : PORTS) {
            assertThat(config.getValue("archiv." + port + ".stub", Boolean.class)).as(port).isEqualTo(erwartet);
        }
        assertThat(config.getValue("archiv.ki.embedding-stub", Boolean.class)).as("embedding").isEqualTo(erwartet);
        assertThat(config.getValue("archiv.chat.stub", Boolean.class)).as("chat").isEqualTo(erwartet);
    }

    /** Bis Aufgabe 7.2 gibt es keinen Chat-Agenten: im Profil lokal darum der Demo-Stub (Spec 02, Aufgabe 7.3). */
    @Test
    void chatStubAuchImProfilLokal() throws IOException {
        SmallRyeConfig config = new SmallRyeConfigBuilder()
                .withProfile("lokal")
                .addDefaultInterceptors()
                .withSources(new PropertiesConfigSource(
                        getClass().getClassLoader().getResource("application.properties")))
                .build();
        assertThat(config.getValue("archiv.chat.stub", Boolean.class)).isTrue();
        assertThat(config.getValue("archiv.ki.stub", Boolean.class)).isFalse();
    }

    /**
     * Die echte Auswahl von {@link ChatAuswahl} (in den Quarkus-Tests ersetzt sie {@code TestChatAgent}): mit Schalter
     * der Demo-Stub, sonst {@link ChatNichtVerfuegbar}; in prod nur eine Warnung, bis Aufgabe 7.2 den Agenten bringt.
     */
    @Test
    void chatSchalterWaehltStubOderNichtVerfuegbar() {
        ChatAgentStub stub = new ChatAgentStub();
        ChatNichtVerfuegbar ohneAgent = new ChatNichtVerfuegbar();
        assertThat(new ChatAuswahl().chat(true, stub, ohneAgent)).isSameAs(stub);
        assertThat(new ChatAuswahl().chat(false, stub, ohneAgent)).isSameAs(ohneAgent);
        PortAuswahl.Befund befund = PortAuswahl.pruefe(List.of(new ChatAuswahl().auswahl(false)));
        assertThat(befund.abbrueche()).isEmpty();
        assertThat(befund.warnungen()).containsExactly("ChatAgentPort: kein Adapter, folgt mit Spec 02, Aufgabe 7.2");
        assertThat(PortAuswahl.pruefe(List.of(new ChatAuswahl().auswahl(true))).abbrueche())
                .containsExactly("ChatAgentPort: Dev-Stub ist in prod nicht zulässig");
    }

    /**
     * Die echte Auswahl von {@link EmbeddingAuswahl} (in den Quarkus-Tests ersetzt sie {@code TestEmbeddingPort}): mit
     * Schalter der Dev-Stub, ohne Schalter der LangChain4j-Adapter.
     */
    @Test
    void embeddingSchalterWaehltStubOderAdapter() {
        EmbeddingStub stub = new EmbeddingStub();
        LangChain4jEmbeddingAdapter adapter = new LangChain4jEmbeddingAdapter(null);
        assertThat(new EmbeddingAuswahl().embedding(true, stub, instanz(adapter))).isSameAs(stub);
        assertThat(new EmbeddingAuswahl().embedding(false, stub, instanz(adapter))).isSameAs(adapter);
        assertThat(PortAuswahl.pruefe(List.of(new EmbeddingAuswahl().auswahl(false))).abbrueche()).isEmpty();
        assertThat(PortAuswahl.pruefe(List.of(new EmbeddingAuswahl().auswahl(false))).warnungen()).isEmpty();
        assertThat(PortAuswahl.pruefe(List.of(new EmbeddingAuswahl().auswahl(true))).abbrueche())
                .containsExactly("EmbeddingPort: Dev-Stub ist in prod nicht zulässig");
    }

    /** {@link Instance} mit genau diesem Wert; andere Methoden werden nicht gebraucht. */
    @SuppressWarnings("unchecked")
    private static <T> Instance<T> instanz(T wert) {
        return (Instance<T>) Proxy.newProxyInstance(StubSchalterTest.class.getClassLoader(),
                new Class<?>[]{Instance.class}, (proxy, methode, argumente) -> {
                    if (methode.getName().equals("get") && methode.getParameterCount() == 0) {
                        return wert;
                    }
                    throw new UnsupportedOperationException(methode.getName());
                });
    }

    @Test
    void schalterWaehltDenStub() {
        AblageStub ablage = new AblageStub();
        KiStub ki = new KiStub();
        OcrStub ocr = new OcrStub();
        IntegrationStub integration = new IntegrationStub();
        assertThat(new AblageAuswahl().ablage(true, ablage, null)).isSameAs(ablage);
        assertThat(new KiAuswahl().ki(true, ki, null)).isSameAs(ki);
        assertThat(new OcrAuswahl().ocr(true, ocr, null)).isSameAs(ocr);
        assertThat(new IntegrationAuswahl().integration(true, integration)).isSameAs(integration);
    }

    @Test
    void inProdBrichtNurEinStubOderEinFehlenderAdapterOhnePlanAb() {
        var ports = List.of(new AblageAuswahl().auswahl(false), new KiAuswahl().auswahl(false),
                new OcrAuswahl().auswahl(false), new IntegrationAuswahl().auswahl(false));
        var befund = PortAuswahl.pruefe(ports);
        assertThat(befund.abbrueche()).isEmpty();
        // nur noch IntegrationPort (Spec 02/03) ohne echten Adapter; KiPort hat ihn seit Aufgabe 3.2
        assertThat(befund.warnungen()).hasSize(1).allMatch(w -> w.startsWith("IntegrationPort"));

        assertThat(PortAuswahl.pruefe(List.of(new AblageAuswahl().auswahl(true))).abbrueche())
                .containsExactly("AblagePort: Dev-Stub ist in prod nicht zulässig");
        assertThat(PortAuswahl.pruefe(List.of(new PortAuswahl("XPort", false, false, Optional.empty()))).abbrueche())
                .containsExactly("XPort: kein Adapter");
    }

    @Test
    void ohneStubUndOhneAdapterBrichtDieAuswahlAb() {
        assertThatThrownBy(() -> new IntegrationAuswahl().integration(false, new IntegrationStub()))
                .isInstanceOf(IllegalStateException.class);
    }
}
