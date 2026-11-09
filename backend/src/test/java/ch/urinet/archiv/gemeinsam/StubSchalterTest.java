package ch.urinet.archiv.gemeinsam;

import ch.urinet.archiv.ablage.adapter.out.AblageAuswahl;
import ch.urinet.archiv.ablage.adapter.out.stub.AblageStub;
import ch.urinet.archiv.integration.adapter.out.IntegrationAuswahl;
import ch.urinet.archiv.integration.adapter.out.stub.IntegrationStub;
import ch.urinet.archiv.ki.adapter.out.KiAuswahl;
import ch.urinet.archiv.ki.adapter.out.stub.KiStub;
import ch.urinet.archiv.verarbeitung.adapter.out.OcrAuswahl;
import ch.urinet.archiv.verarbeitung.adapter.out.stub.OcrStub;
import io.smallrye.config.PropertiesConfigSource;
import io.smallrye.config.SmallRyeConfig;
import io.smallrye.config.SmallRyeConfigBuilder;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

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
    }

    @Test
    void schalterWaehltDenStub() {
        AblageStub ablage = new AblageStub();
        KiStub ki = new KiStub();
        OcrStub ocr = new OcrStub();
        IntegrationStub integration = new IntegrationStub();
        assertThat(new AblageAuswahl().ablage(true, ablage)).isSameAs(ablage);
        assertThat(new KiAuswahl().ki(true, ki)).isSameAs(ki);
        assertThat(new OcrAuswahl().ocr(true, ocr)).isSameAs(ocr);
        assertThat(new IntegrationAuswahl().integration(true, integration)).isSameAs(integration);
    }

    @Test
    void ohneStubUndOhneAdapterBrichtDieAuswahlAb() {
        assertThatThrownBy(() -> new AblageAuswahl().ablage(false, new AblageStub()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("2.3");
        assertThatThrownBy(() -> new KiAuswahl().ki(false, new KiStub()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("3.2");
        assertThatThrownBy(() -> new OcrAuswahl().ocr(false, new OcrStub()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("2.6");
        assertThatThrownBy(() -> new IntegrationAuswahl().integration(false, new IntegrationStub()))
                .isInstanceOf(IllegalStateException.class);
    }
}
