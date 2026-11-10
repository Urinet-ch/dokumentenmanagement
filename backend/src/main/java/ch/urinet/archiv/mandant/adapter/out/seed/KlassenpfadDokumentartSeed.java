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

    private static final Set<String> FELDER = Set.of("code", "name", "beschreibung", "gruppen", "pflichtfelder", "zusatz_schema",
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
                    text(art, "code", pfad), text(art, "name", pfad), beschreibung(art, pfad), liste(art.path("gruppen")),
                    liste(art.path("pflichtfelder")),
                    art.path("zusatz_schema").isMissingNode() ? null : art.path("zusatz_schema").toString(),
                    text(art, "vertraulichkeit", pfad),
                    jahre(art, pfad),
                    art.path("aufbewahrung_ab").isTextual() ? art.path("aufbewahrung_ab").asText() : null,
                    pflicht(art, pfad),
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

    /** Optional; wenn angegeben, ein nicht leerer Text. */
    private static String beschreibung(JsonNode art, String pfad) {
        return art.path("beschreibung").isMissingNode() ? null : text(art, "beschreibung", pfad);
    }

    // ohne ausdrückliche Angabe gäbe es für die Art keine Sperre (GeBüV)
    private static boolean pflicht(JsonNode art, String pfad) {
        if (!art.path("gesetzliche_pflicht").isBoolean()) {
            throw ungueltig(pfad, "Feld gesetzliche_pflicht fehlt oder ist nicht boolesch");
        }
        return art.path("gesetzliche_pflicht").booleanValue();
    }

    /** Zahl oder ausdrücklich {@code null} (unbefristet). */
    private static Integer jahre(JsonNode art, String pfad) {
        JsonNode jahre = art.path("aufbewahrung_jahre");
        if (jahre.isNull()) {
            return null;
        }
        if (!jahre.isIntegralNumber() || !jahre.canConvertToInt()) {
            throw ungueltig(pfad, "Feld aufbewahrung_jahre fehlt oder ist keine Zahl");
        }
        return jahre.intValue();
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
