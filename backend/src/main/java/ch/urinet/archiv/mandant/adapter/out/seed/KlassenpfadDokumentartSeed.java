package ch.urinet.archiv.mandant.adapter.out.seed;

import ch.urinet.archiv.mandant.application.DokumentartSeed;
import ch.urinet.archiv.mandant.application.MandantFehler;
import ch.urinet.archiv.mandant.domain.DokumentartVorlage;
import ch.urinet.archiv.mandant.domain.MandantCode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Liest {@code seed/<code>/dokumentarten.json} vom Klassenpfad. Felder mit {@code _} am Anfang (Entwurf, Fragen) fallen
 * weg; jedes andere unbekannte Feld bricht ab, damit Seed und Tabelle nicht still auseinanderlaufen.
 */
@ApplicationScoped
public class KlassenpfadDokumentartSeed implements DokumentartSeed {

    private static final Set<String> FELDER = Set.of("code", "name", "gruppen", "pflichtfelder", "zusatz_schema",
            "vertraulichkeit", "aufbewahrung_jahre", "aufbewahrung_ab", "gesetzliche_pflicht", "aktiv");

    private final ObjectMapper json;

    public KlassenpfadDokumentartSeed(ObjectMapper json) {
        this.json = json;
    }

    @Override
    public List<DokumentartVorlage> lesen(MandantCode code) {
        String pfad = "seed/" + code.wert() + "/dokumentarten.json";
        try (InputStream inhalt = KlassenpfadDokumentartSeed.class.getClassLoader().getResourceAsStream(pfad)) {
            if (inhalt == null) {
                throw new MandantFehler(MandantFehler.Grund.SEED_FEHLT, "Kein Seed für Mandant " + code.wert());
            }
            return vorlagen(json.readTree(inhalt), pfad);
        } catch (IOException e) {
            throw new MandantFehler(MandantFehler.Grund.SEED_UNGUELTIG, "Seed nicht lesbar: " + pfad, e);
        }
    }

    static List<DokumentartVorlage> vorlagen(JsonNode wurzel, String pfad) {
        for (var feld : wurzel.properties()) {
            if (!feld.getKey().startsWith("_") && !feld.getKey().equals("dokumentarten")) {
                throw ungueltig(pfad, "unbekanntes Feld " + feld.getKey());
            }
        }
        JsonNode arten = wurzel.path("dokumentarten");
        if (!arten.isArray() || arten.isEmpty()) {
            throw ungueltig(pfad, "keine Dokumentarten");
        }
        List<DokumentartVorlage> ergebnis = new ArrayList<>();
        for (JsonNode art : arten) {
            for (var feld : art.properties()) {
                if (!feld.getKey().startsWith("_") && !FELDER.contains(feld.getKey())) {
                    throw ungueltig(pfad, "unbekanntes Feld " + feld.getKey());
                }
            }
            ergebnis.add(new DokumentartVorlage(
                    text(art, "code", pfad), text(art, "name", pfad), liste(art.path("gruppen")),
                    liste(art.path("pflichtfelder")),
                    art.path("zusatz_schema").isMissingNode() ? null : art.path("zusatz_schema").toString(),
                    text(art, "vertraulichkeit", pfad),
                    art.path("aufbewahrung_jahre").isNumber() ? art.path("aufbewahrung_jahre").asInt() : null,
                    art.path("aufbewahrung_ab").isTextual() ? art.path("aufbewahrung_ab").asText() : null,
                    art.path("gesetzliche_pflicht").asBoolean(false),
                    art.path("aktiv").asBoolean(true)));
        }
        return ergebnis;
    }

    private static String text(JsonNode art, String feld, String pfad) {
        if (!art.path(feld).isTextual() || art.path(feld).asText().isBlank()) {
            throw ungueltig(pfad, "Feld " + feld + " fehlt");
        }
        return art.path(feld).asText();
    }

    private static List<String> liste(JsonNode knoten) {
        List<String> werte = new ArrayList<>();
        knoten.forEach(w -> werte.add(w.asText()));
        return werte;
    }

    private static MandantFehler ungueltig(String pfad, String grund) {
        return new MandantFehler(MandantFehler.Grund.SEED_UNGUELTIG, "Seed " + pfad + ": " + grund);
    }
}
