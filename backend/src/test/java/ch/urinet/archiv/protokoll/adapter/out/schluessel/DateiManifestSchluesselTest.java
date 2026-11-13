package ch.urinet.archiv.protokoll.adapter.out.schluessel;

import ch.urinet.archiv.protokoll.TsaTestPki;
import ch.urinet.archiv.protokoll.domain.ManifestSignatur;
import io.smallrye.config.PropertiesConfigSource;
import io.smallrye.config.SmallRyeConfigBuilder;
import org.eclipse.microprofile.config.Config;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.PublicKey;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Ed25519-Schlüssel des Manifests aus der Secret-Datei (Req 15.2, 16.9); nur synthetische Schlüssel. */
class DateiManifestSchluesselTest {

    @Test
    void derOeffentlicheSchluesselKommtOhneDenPrivatenAusDerKonfigurationOderDerDatei(@TempDir Path ordner)
            throws IOException {
        String[] paar = TsaTestPki.ed25519Pem();
        byte[] inhalt = "manifest".getBytes(StandardCharsets.UTF_8);
        byte[] sig = ManifestSignatur.signieren(DateiManifestSchluessel.laden(Optional.of(paar[0]), Optional.of(paar[1])),
                inhalt);

        PublicKey ausWert = new DateiManifestSchluessel(config(Map.of(DateiManifestSchluessel.OEFFENTLICH, paar[1])))
                .oeffentlich();
        Path datei = Files.writeString(ordner.resolve("pub.pem"), paar[1]);
        PublicKey ausDatei = new DateiManifestSchluessel(config(Map.of(DateiManifestSchluessel.OEFFENTLICH_DATEI,
                datei.toString()))).oeffentlich();

        assertThat(ManifestSignatur.pruefen(ausWert, inhalt, sig)).isTrue();
        assertThat(ausDatei).isEqualTo(ausWert);
    }

    @Test
    void ohneOeffentlichenSchluesselWirdNichtsAusDemPrivatenAbgeleitet(@TempDir Path ordner) {
        String privat = TsaTestPki.ed25519Pem()[0];
        DateiManifestSchluessel schluessel = new DateiManifestSchluessel(config(Map.of(
                DateiManifestSchluessel.PRIVAT, privat)), "/manifest/fehlt.pem");
        DateiManifestSchluessel falscheDatei = new DateiManifestSchluessel(config(Map.of(
                DateiManifestSchluessel.OEFFENTLICH_DATEI, ordner.resolve("fehlt.pem").toString())));

        assertThat(schluessel.privat()).isNotNull();
        assertThatThrownBy(schluessel::oeffentlich).isInstanceOf(IllegalStateException.class)
                .hasMessageStartingWith("Öffentlicher Manifest-Schlüssel fehlt");
        assertThatThrownBy(falscheDatei::oeffentlich).isInstanceOf(IllegalStateException.class)
                .hasMessageStartingWith("Öffentlicher Manifest-Schlüssel nicht lesbar");
    }

    @Test
    void einFremderOeffentlicherSchluesselBrichtAb() {
        String privat = TsaTestPki.ed25519Pem()[0];
        String fremd = TsaTestPki.ed25519Pem()[1];

        assertThatThrownBy(() -> new DateiManifestSchluessel(config(Map.of(DateiManifestSchluessel.PRIVAT, privat,
                DateiManifestSchluessel.OEFFENTLICH, fremd))).privat())
                .isInstanceOf(IllegalStateException.class).hasMessage("Öffentlicher Manifest-Schlüssel passt nicht zum privaten");
    }

    @Test
    void einFehlenderOderUngueltigerSchluesselBrichtAbOhneIhnZuNennen() {
        assertThatThrownBy(() -> DateiManifestSchluessel.laden(Optional.of(" "), Optional.empty()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("Manifest-Schlüssel fehlt");
        assertThatThrownBy(() -> DateiManifestSchluessel.laden(Optional.of("attrappe-manifest-schluessel"),
                Optional.empty()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Manifest-Schlüssel ist ungültig (erwartet PKCS#8-PEM, Ed25519)");
    }

    private static Config config(Map<String, String> werte) {
        return new SmallRyeConfigBuilder().withSources(new PropertiesConfigSource(werte, "test")).build();
    }
}
