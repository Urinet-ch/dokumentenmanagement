package ch.urinet.archiv.sicherheit.adapter.out.krypto;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** AES-256-GCM des Datenschlüssels (Spec 02, design.md «Verschluesselung»). */
class DatenschluesselTest {

    private static final String BEZUG = "integration_mail.passwort:urinet";

    @Test
    void verschluesseltMitZufaelligemIvUndEntschluesseltMitDemselbenBezug() {
        Datenschluessel schluessel = Datenschluessel.aus(zufall(32));
        byte[] klartext = "geheim-123".getBytes(StandardCharsets.UTF_8);
        byte[] a = schluessel.verschluesseln(klartext, BEZUG);
        byte[] b = schluessel.verschluesseln(klartext, BEZUG);
        assertThat(a).isNotEqualTo(b).hasSize(Datenschluessel.IV_BYTES + klartext.length + 16);
        assertThat(new String(a, StandardCharsets.ISO_8859_1)).doesNotContain("geheim");
        assertThat(schluessel.entschluesseln(a, BEZUG)).isEqualTo(klartext);
    }

    @Test
    void andererBezugOderSchluesselOderVeraendertesChiffratScheitern() {
        Datenschluessel schluessel = Datenschluessel.aus(zufall(32));
        byte[] chiffrat = schluessel.verschluesseln("x".getBytes(StandardCharsets.UTF_8), BEZUG);
        assertThatThrownBy(() -> schluessel.entschluesseln(chiffrat, "integration_mail.passwort:vhf-gsk"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Datenschluessel.aus(zufall(32)).entschluesseln(chiffrat, BEZUG))
                .isInstanceOf(IllegalArgumentException.class);
        chiffrat[chiffrat.length - 1] ^= 1;
        assertThatThrownBy(() -> schluessel.entschluesseln(chiffrat, BEZUG))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> schluessel.entschluesseln(new byte[10], BEZUG))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nurBase64Von32ByteUndDieMeldungNenntDenInhaltNie() {
        String kurz = zufall(16);
        assertThatThrownBy(() -> Datenschluessel.aus(kurz)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining(kurz);
        assertThatThrownBy(() -> Datenschluessel.aus("kein base64 !")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining("kein base64");
        String gut = zufall(32);
        assertThat(Datenschluessel.aus(gut + "\n").toString()).doesNotContain(gut);
    }

    private static String zufall(int laenge) {
        byte[] bytes = new byte[laenge];
        new SecureRandom().nextBytes(bytes);
        return Base64.getEncoder().encodeToString(bytes);
    }
}
