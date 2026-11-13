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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Smoke-Test der Zeitstempeldienste aus {@code application.properties} (Profil {@code prod}) gegen die echten Dienste
 * (Review PR #87, Copilot S4; Req 15.3, 15.11). Nur von Hand durch Fabian vor dem ersten Deploy mit Manifest, nie in
 * der CI: Der Name endet auf {@code IT} (Surefire führt ihn nicht aus) und der Test läuft nur mit
 * {@code -Darchiv.smoke.tsa=ja}. Anleitung: {@code backend/README.md}, «Smoke-Test Zeitstempeldienste».
 *
 * <p>Gestempelt wird nur der SHA-256 einer Zufallsfolge, nie ein Inhalt. Je Dienst prüft der Test Token, Policy und
 * CA gegen die Allowlist, holt die Prüfdaten (OCSP bzw. CRL), prüft sie zu {@code genTime} mit dem Höchstalter aus
 * {@code archiv.tsa.dienst.<kennung>.pruefdaten-hoechstalter} (sonst {@code archiv.tsa.pruefdaten-hoechstalter}) und
 * prüft den abgelegten Stand wie das Prüfwerkzeug. Ausgabe nur Kennung, {@code genTime}, Art und Grösse der Prüfdaten
 * sowie deren {@code producedAt} (nur OCSP), {@code thisUpdate} und {@code nextUpdate}, damit das Höchstalter je Dienst
 * an den tatsächlichen Werten festgelegt werden kann.
 */
@EnabledIfSystemProperty(named = "archiv.smoke.tsa", matches = "ja")
class TsaSmokeIT {

    @Test
    void zeitstempelDerKonfiguriertenDienste() throws IOException, NoSuchAlgorithmException {
        SmallRyeConfig config = new SmallRyeConfigBuilder().withProfile("prod").addDefaultInterceptors()
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
        for (String dienst : dienste) {
            Stempel stempel = adapter.stempeln(dienst, imprint);
            Pruefdaten pruefdaten = adapter.pruefdaten(dienst, stempel);
            BcZeitstempelAdapter.Zeitraum zeitraum = BcZeitstempelAdapter.zeitraum(pruefdaten);
            System.out.printf("SMOKE %s pruefdaten=%s producedAt=%s thisUpdate=%s nextUpdate=%s%n", dienst,
                    pruefdaten.art(), zeitraum.producedAt(), zeitraum.thisUpdate(), zeitraum.nextUpdate());
            Instant genTime = adapter.pruefenAbgelegt(dienst, stempel.tsr(), imprint, stempel.kettePem(), pruefdaten);
            assertThat(genTime).isEqualTo(stempel.genTime());
            System.out.printf("SMOKE %s genTime=%s pruefdaten=%s (%d Bytes) gültig%n", dienst, genTime,
                    pruefdaten.art(), pruefdaten.inhalt().length);
        }
    }
}
