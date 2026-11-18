package ch.urinet.archiv.mandant.adapter.out.seed;

import ch.urinet.archiv.mandant.application.MandantFehler;
import ch.urinet.archiv.mandant.application.WissensquelleSeed;
import ch.urinet.archiv.mandant.domain.DokumentartVorlage;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/** Liest die mandantenunabhängige Dokumentart aus {@code seed/_alle/dokumentarten-wissen.json}. */
@ApplicationScoped
public class KlassenpfadWissensquelleSeed implements WissensquelleSeed {

    private static final String PFAD = "seed/_alle/dokumentarten-wissen.json";
    private final ObjectMapper json;

    public KlassenpfadWissensquelleSeed(ObjectMapper json) {
        this.json = json;
    }

    @Override
    public DokumentartVorlage lesen() {
        try (InputStream inhalt = KlassenpfadWissensquelleSeed.class.getClassLoader().getResourceAsStream(PFAD)) {
            if (inhalt == null) {
                throw new MandantFehler(MandantFehler.Grund.SEED_FEHLT, "Kein mandantenunabhängiger Seed: " + PFAD);
            }
            List<DokumentartVorlage> arten = KlassenpfadDokumentartSeed.vorlagen(json.readTree(inhalt), PFAD);
            if (arten.size() != 1 || !"WISSENSQUELLE".equals(arten.getFirst().code())) {
                throw new MandantFehler(MandantFehler.Grund.SEED_UNGUELTIG,
                        "Seed " + PFAD + ": erwartet genau die Dokumentart WISSENSQUELLE");
            }
            return arten.getFirst();
        } catch (IOException e) {
            throw new MandantFehler(MandantFehler.Grund.SEED_UNGUELTIG, "Seed nicht lesbar: " + PFAD, e);
        }
    }
}
