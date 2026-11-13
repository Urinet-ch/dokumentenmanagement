package ch.urinet.archiv.protokoll;

import ch.urinet.archiv.protokoll.adapter.out.tsa.BcZeitstempelAdapter;
import ch.urinet.archiv.protokoll.adapter.out.tsa.TsaStartpruefung;
import ch.urinet.archiv.protokoll.domain.Pruefdaten;
import ch.urinet.archiv.protokoll.domain.Stempel;
import io.smallrye.config.PropertiesConfigSource;
import io.smallrye.config.SmallRyeConfig;
import io.smallrye.config.SmallRyeConfigBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Smoke-Test der Zeitstempeldienste aus {@code application.properties} (Profil {@code prod}) gegen die echten Dienste
 * (Review PR #87, Copilot S4; Req 15.3, 15.11). Nur von Hand durch Fabian vor dem ersten Deploy mit Manifest, nie in
 * der CI: Der Name endet auf {@code IT} (Surefire führt ihn nicht aus) und der Test läuft nur mit
 * {@code -Darchiv.smoke.tsa=ja}. Anleitung: {@code backend/README.md}, «Smoke-Test Zeitstempeldienste».
 *
 * <p>Gestempelt wird nur der SHA-256 einer Zufallsfolge, nie ein Inhalt. Je Dienst holt der Test Token und Prüfdaten
 * (OCSP bzw. CRL) und gibt zuerst die Rohwerte aus: Kennung, {@code genTime}, Art und Grösse der Prüfdaten sowie deren
 * {@code producedAt} (nur OCSP), {@code thisUpdate} und {@code nextUpdate}, damit das Höchstalter je Dienst an den
 * tatsächlichen Werten festgelegt werden kann, auch wenn die Prüfung danach scheitert. Erst dann prüft er wie das
 * Prüfwerkzeug Token, Policy und CA gegen die Allowlist und die Prüfdaten zu {@code genTime} mit dem Höchstalter aus
 * {@code archiv.tsa.dienst.<kennung>.pruefdaten-hoechstalter} (sonst {@code archiv.tsa.pruefdaten-hoechstalter}).
 * Scheitert ein Dienst, prüft der Test die übrigen weiter und schlägt am Ende fehl. Ausgegeben werden nur Kennungen,
 * Zeiten, Grössen und Fehlermeldungen, keine Schlüssel. Einstellungen aus {@code application.properties} lassen sich
 * mit {@code -D} übersteuern (z. B. das Höchstalter eines Diensts).
 */
@EnabledIfSystemProperty(named = "archiv.smoke.tsa", matches = "ja")
class TsaSmokeIT {

    @Test
    void zeitstempelDerKonfiguriertenDienste() throws IOException, NoSuchAlgorithmException {
        SmallRyeConfig config = new SmallRyeConfigBuilder().withProfile("prod").addDefaultInterceptors()
                .addSystemSources()
                .withSources(new PropertiesConfigSource(TsaSmokeIT.class.getClassLoader()
                        .getResource("application.properties")))
                .build();
        List<String> dienste = TsaStartpruefung.pruefen(config);
        assertThat(dienste).as("Req 15.11: zwei Dienste").hasSize(2);
        BcZeitstempelAdapter adapter = new BcZeitstempelAdapter(config,
                config.getValue("archiv.tsa.zeitgrenze", Duration.class),
                config.getValue("archiv.tsa.pruefdaten-hoechstalter", Duration.class));
        byte[] zufall = new byte[32];
        new SecureRandom().nextBytes(zufall);
        byte[] imprint = MessageDigest.getInstance("SHA-256").digest(zufall);
        List<String> gescheitert = new ArrayList<>();
        for (String dienst : dienste) {
            try {
                Stempel stempel = adapter.stempeln(dienst, imprint);
                // Rohwerte zuerst, damit sie auch bei «… ist zu alt» sichtbar sind
                Pruefdaten pruefdaten = adapter.pruefdatenUngeprueft(dienst, stempel);
                System.out.printf("SMOKE %s genTime=%s pruefdaten=%s (%d Bytes)%n", dienst, stempel.genTime(),
                        pruefdaten.art(), pruefdaten.inhalt().length);
                BcZeitstempelAdapter.Zeitraum zeitraum = BcZeitstempelAdapter.zeitraum(pruefdaten);
                System.out.printf("SMOKE %s producedAt=%s thisUpdate=%s nextUpdate=%s%n", dienst,
                        zeitraum.producedAt(), zeitraum.thisUpdate(), zeitraum.nextUpdate());
                Instant genTime = adapter.pruefenAbgelegt(dienst, stempel.tsr(), imprint, stempel.kettePem(),
                        pruefdaten);
                assertThat(genTime).isEqualTo(stempel.genTime());
                System.out.printf("SMOKE %s gültig%n", dienst);
            } catch (RuntimeException | AssertionError e) {
                System.out.printf("SMOKE %s FEHLER %s: %s%n", dienst, e.getClass().getSimpleName(), e.getMessage());
                gescheitert.add(dienst);
            }
        }
        assertThat(gescheitert).as("gescheiterte Dienste, siehe SMOKE-Zeilen").isEmpty();
    }
}
