package ch.urinet.archiv.protokoll.adapter.out.schluessel;

import ch.urinet.archiv.protokoll.TsaTestPki;
import ch.urinet.archiv.protokoll.domain.ManifestSignatur;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Ed25519-Schlüssel des Manifests aus der Secret-Datei (Req 15.2, 16.9); nur synthetische Schlüssel. */
class DateiManifestSchluesselTest {

    @Test
    void derOeffentlicheSchluesselWirdAusDemPrivatenAbgeleitet() {
        String[] paar = TsaTestPki.ed25519Pem();
        DateiManifestSchluessel.Paar geladen = DateiManifestSchluessel.laden(Optional.of(paar[0]), Optional.empty());
        byte[] inhalt = "manifest".getBytes(StandardCharsets.UTF_8);

        assertThat(ManifestSignatur.pruefen(geladen.oeffentlich(), inhalt,
                ManifestSignatur.signieren(geladen.privat(), inhalt))).isTrue();
        assertThat(DateiManifestSchluessel.laden(Optional.of(paar[0]), Optional.of(paar[1])).oeffentlich())
                .isEqualTo(geladen.oeffentlich());
    }

    @Test
    void einFremderOeffentlicherSchluesselBrichtAb() {
        String privat = TsaTestPki.ed25519Pem()[0];
        String fremd = TsaTestPki.ed25519Pem()[1];

        assertThatThrownBy(() -> DateiManifestSchluessel.laden(Optional.of(privat), Optional.of(fremd)))
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
}
