package ch.urinet.archiv.mandant.adapter.out.seed;

import ch.urinet.archiv.konfiguration.application.KonfigurationsdateiCodec;
import ch.urinet.archiv.konfiguration.domain.Konfiguration;
import ch.urinet.archiv.mandant.application.KonfigurationSeed;
import ch.urinet.archiv.mandant.application.MandantFehler;
import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.gemeinsam.domain.FachFehler;
import jakarta.enterprise.context.ApplicationScoped;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
/** Liest die vollständige Konfiguration eines Mandanten. */
@ApplicationScoped
public class KlassenpfadKonfigurationSeed implements KonfigurationSeed {

    private final KonfigurationsdateiCodec codec;
    public KlassenpfadKonfigurationSeed(KonfigurationsdateiCodec codec) {
        this.codec = codec;
    }

    @Override
    public Konfiguration lesen(MandantCode code) {
        String pfad = "seed/" + code.wert() + "/konfiguration.json";
        try (InputStream inhalt = KlassenpfadKonfigurationSeed.class.getClassLoader().getResourceAsStream(pfad)) {
            if (inhalt == null) {
                throw new MandantFehler(MandantFehler.Grund.SEED_FEHLT, "Kein Seed für Mandant " + code.wert());
            }
            return codec.seedLesen(new String(inhalt.readAllBytes(), StandardCharsets.UTF_8)).inhalt();
        } catch (IOException e) {
            throw new MandantFehler(MandantFehler.Grund.SEED_UNGUELTIG, "Seed nicht lesbar: " + pfad, e);
        } catch (FachFehler e) {
            throw new MandantFehler(MandantFehler.Grund.SEED_UNGUELTIG, "Seed ungültig für Mandant " + code.wert(), e);
        }
    }

}
