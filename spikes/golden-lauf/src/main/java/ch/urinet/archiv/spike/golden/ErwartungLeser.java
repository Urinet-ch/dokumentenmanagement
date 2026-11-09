package ch.urinet.archiv.spike.golden;

import ch.urinet.archiv.spike.golden.Erwartung.Datensatz;
import ch.urinet.archiv.spike.golden.Erwartung.Mandant;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/** Liest beide Formate von erwartet.json: synthetisch (tools/golden_synth) und echt (geschwärzt, mit Prüfmarken). */
public final class ErwartungLeser {

    private static final ObjectMapper JSON = new ObjectMapper();

    private ErwartungLeser() {
    }

    /** synthetisch/erwartet.json; Mandant aus dem Kopfeintrag «mandant». */
    public static Datensatz synthetisch(Path erwartetJson) throws IOException {
        JsonNode wurzel = JSON.readTree(erwartetJson.toFile());
        JsonNode m = wurzel.path("mandant");
        if (m.isMissingNode()) {
            throw new IllegalStateException("synthetisch/erwartet.json ohne Kopfeintrag mandant (golden_synth aktualisieren)");
        }
        Mandant mandant = new Mandant(m.path("name").asText(), text(m.path("uid")), texte(m.path("ibans")));
        Path basis = erwartetJson.getParent();
        List<Erwartung> belege = new ArrayList<>();
        for (JsonNode e : wurzel.path("belege")) {
            String datei = e.path("datei").asText();
            belege.add(new Erwartung(Path.of(datei).getFileName().toString(), basis.resolve(datei), false,
                    text(e.path("teil")), text(e.path("kategorie")), text(e.path("art")), datum(e.path("datum")),
                    text(e.path("partner")), betrag(e.path("betrag")), text(e.path("belegnummer")),
                    texte(e.path("randfaelle"))));
        }
        return new Datensatz(mandant, belege, 0);
    }

    /**
     * $ARCHIV_GOLDEN_DIR/erwartet.json: nur bestätigte Einträge ohne offene Prüfmarke ({"_pruefen": true, …});
     * sie bilden die Realitätsprüfung (Req 0.10) und tragen keinen Teil.
     */
    public static Datensatz echt(Path erwartetJson, Path kennungenJson, String mandantName) throws IOException {
        JsonNode k = JSON.readTree(kennungenJson.toFile());
        Mandant mandant = new Mandant(mandantName, text(k.path("uid")), texte(k.path("ibans")));
        Path basis = erwartetJson.getParent();
        List<Erwartung> belege = new ArrayList<>();
        int ausgeschlossen = 0;
        for (JsonNode e : JSON.readTree(erwartetJson.toFile()).path("belege")) {
            if (!e.path("bestaetigt").asBoolean(false) || hatPruefmarke(e)) {
                ausgeschlossen++;
                continue;
            }
            String datei = e.path("datei").asText();
            Path pfad = datei.contains("/") ? basis.resolve(datei) : basis.resolve("belege").resolve(datei);
            belege.add(new Erwartung(kurzId(Path.of(datei).getFileName().toString()), pfad, true, null,
                    text(e.path("kategorie")), text(e.path("art")), datum(e.path("datum")), text(e.path("partner")),
                    betrag(e.path("betrag")), text(e.path("belegnummer")), List.of()));
        }
        return new Datensatz(mandant, belege, ausgeschlossen);
    }

    static boolean hatPruefmarke(JsonNode e) {
        for (Map.Entry<String, JsonNode> feld : e.properties()) {
            if (feld.getValue().isObject() && feld.getValue().path("_pruefen").asBoolean(false)) {
                return true;
            }
        }
        return false;
    }

    /** Kurz-ID echter Belege: erste 8 Zeichen des SHA-256 des Dateinamens (nie den Namen ausgeben). */
    public static String kurzId(String dateiname) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(dateiname.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(h).substring(0, 8);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String text(JsonNode n) {
        return n.isMissingNode() || n.isNull() ? null : n.asText();
    }

    private static List<String> texte(JsonNode n) {
        List<String> liste = new ArrayList<>();
        n.forEach(x -> liste.add(x.asText()));
        return liste;
    }

    private static LocalDate datum(JsonNode n) {
        String t = text(n);
        return t == null ? null : LocalDate.parse(t);
    }

    private static BigDecimal betrag(JsonNode n) {
        String t = text(n);
        return t == null ? null : new BigDecimal(t);
    }
}
