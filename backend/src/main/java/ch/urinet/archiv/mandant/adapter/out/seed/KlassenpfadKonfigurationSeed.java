package ch.urinet.archiv.mandant.adapter.out.seed;

import ch.urinet.archiv.dokumentart.application.ZusatzSchemaPruefer;
import ch.urinet.archiv.konfiguration.application.KonfigurationsdateiCodec;
import ch.urinet.archiv.konfiguration.domain.ArtKlassifikation;
import ch.urinet.archiv.konfiguration.domain.Konfiguration;
import ch.urinet.archiv.konfiguration.domain.Konfiguration.Art;
import ch.urinet.archiv.konfiguration.domain.Konfiguration.Aufbewahrung;
import ch.urinet.archiv.mandant.application.KonfigurationSeed;
import ch.urinet.archiv.mandant.application.MandantFehler;
import ch.urinet.archiv.mandant.application.WissensquelleSeed;
import ch.urinet.archiv.mandant.domain.DokumentartVorlage;
import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.gemeinsam.domain.FachFehler;
import jakarta.enterprise.context.ApplicationScoped;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/** Liest und ergänzt die vollständige Konfiguration eines Mandanten. */
@ApplicationScoped
public class KlassenpfadKonfigurationSeed implements KonfigurationSeed {

    private final KonfigurationsdateiCodec codec;
    private final WissensquelleSeed wissensquelleSeed;
    private final ZusatzSchemaPruefer zusatzSchema;

    public KlassenpfadKonfigurationSeed(KonfigurationsdateiCodec codec, WissensquelleSeed wissensquelleSeed,
                                        ZusatzSchemaPruefer zusatzSchema) {
        this.codec = codec;
        this.wissensquelleSeed = wissensquelleSeed;
        this.zusatzSchema = zusatzSchema;
    }

    @Override
    public Konfiguration lesen(MandantCode code) {
        String pfad = "seed/" + code.wert() + "/konfiguration.json";
        try (InputStream inhalt = KlassenpfadKonfigurationSeed.class.getClassLoader().getResourceAsStream(pfad)) {
            if (inhalt == null) {
                throw new MandantFehler(MandantFehler.Grund.SEED_FEHLT, "Kein Seed für Mandant " + code.wert());
            }
            Konfiguration konfiguration = codec.seedLesen(new String(inhalt.readAllBytes(), StandardCharsets.UTF_8))
                    .inhalt();
            return mitWissensquelleWennFehlt(konfiguration, wissensquelleSeed, zusatzSchema);
        } catch (IOException e) {
            throw new MandantFehler(MandantFehler.Grund.SEED_UNGUELTIG, "Seed nicht lesbar: " + pfad, e);
        } catch (FachFehler e) {
            throw new MandantFehler(MandantFehler.Grund.SEED_UNGUELTIG, "Seed ungültig für Mandant " + code.wert(), e);
        }
    }

    static Konfiguration mitWissensquelleWennFehlt(Konfiguration konfiguration,
                                                    WissensquelleSeed wissensquelleSeed,
                                                    ZusatzSchemaPruefer zusatzSchema) {
        if (konfiguration.arten().stream().anyMatch(art -> "WISSENSQUELLE".equals(art.code()))) {
            return konfiguration;
        }
        List<Art> arten = new java.util.ArrayList<>(konfiguration.arten());
        arten.add(art(wissensquelleSeed.lesen(), zusatzSchema));
        return new Konfiguration(konfiguration.version(), konfiguration.einstellungen(), arten,
                konfiguration.klassifikationen(), konfiguration.regeln(), konfiguration.kiTexte());
    }

    private static Art art(DokumentartVorlage vorlage, ZusatzSchemaPruefer zusatzSchema) {
        return new Art(vorlage.code(), vorlage.name(), vorlage.beschreibung(), vorlage.gruppen(),
                vorlage.pflichtfelder(), vorlage.zusatzSchemaJson(), vorlage.vertraulichkeit(),
                new Aufbewahrung(vorlage.aufbewahrungJahre(), vorlage.aufbewahrungAb(), vorlage.aufbewahrungFeld()),
                vorlage.gesetzlichePflicht(), vorlage.aktiv(), vorlage.selbstAusgestellt(),
                vorlage.belegnummerMuster(), vorlage.fremdbelegArt(), vorlage.ohneZahlungsdatenArt(),
                vorlage.kiVorschlag(), vorlage.kiFelder(), vorlage.kiHinweise(), vorlage.reihenfolge(),
                List.<ArtKlassifikation>of(), zusatzSchema.feldTypen(vorlage.zusatzSchemaJson()));
    }
}
