package ch.urinet.archiv.spike.golden;

import ch.urinet.archiv.spike.golden.GoldenPipeline.BelegErgebnis;
import ch.urinet.archiv.spike.golden.GoldenPipeline.Eingabe;
import ch.urinet.archiv.spike.golden.Vergleich.Feld;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Auswertung nach Req 0.2, 0.3, 0.9. Vollbericht nur in ARCHIV_GOLDEN_DIR; kennzahlen.md enthält keine Inhalte. */
public final class Bericht {

    public record Lauf(Modus modus, boolean k1, String zeitpunkt, String promptHash, String modell, double schwelle,
                       int echteBestaetigt, int echteAusgeschlossen, double preisEin, double preisAus) {
    }

    public record Kennzahlen(int belege, Map<Feld, Integer> richtig, int gateRichtig, int fehlerUeberSchwelle,
                             BigDecimal kleinsteSchwelle, int qrGefunden, int nachfragen, int fehlgeschlagen,
                             long tokensEin, long tokensAus, int tokensUnvollstaendig, double kostenChf,
                             long ocrMsSchnitt, long kiMsSchnitt, List<String> fehlschlaege,
                             int belegeBild, long tokensEinBild, long tokensAusBild, double kostenBild, double kostenText,
                             List<String> fotosNichtAlsBild, int artRohRichtig, List<String> umstufungen) {

        public int felderRichtig() {
            return richtig.values().stream().mapToInt(Integer::intValue).sum();
        }

        public int felderGesamt() {
            return belege * Feld.values().length;
        }
    }

    private Bericht() {
    }

    public static Kennzahlen berechne(List<BelegErgebnis> ergebnisse, double schwelle, double preisEin, double preisAus) {
        Map<Feld, Integer> richtig = new EnumMap<>(Feld.class);
        for (Feld f : Feld.values()) {
            richtig.put(f, (int) ergebnisse.stream().filter(r -> r.richtig().get(f)).count());
        }
        List<String> fehlschlaege = new ArrayList<>();
        for (BelegErgebnis r : ergebnisse) {
            List<String> falsch = new ArrayList<>();
            for (Feld f : Feld.values()) {
                if (!r.richtig().get(f)) {
                    falsch.add(f.name());
                }
            }
            if (!falsch.isEmpty()) {
                fehlschlaege.add(r.erwartung().id() + ": " + String.join(", ", falsch)
                        + (r.vorschlag() == null ? " (kein Vorschlag)" : ""));
            }
        }
        long ein = ergebnisse.stream().mapToLong(BelegErgebnis::tokensEin).sum();
        long aus = ergebnisse.stream().mapToLong(BelegErgebnis::tokensAus).sum();
        List<BelegErgebnis> bild = ergebnisse.stream().filter(r -> r.eingabe() == Eingabe.BILD).toList();
        long einBild = bild.stream().mapToLong(BelegErgebnis::tokensEin).sum();
        long ausBild = bild.stream().mapToLong(BelegErgebnis::tokensAus).sum();
        double kostenBild = einBild / 1e6 * preisEin + ausBild / 1e6 * preisAus;
        double kosten = ein / 1e6 * preisEin + aus / 1e6 * preisAus;
        // Kontrolle nur im Bericht: die Pipeline kennt die Kategorie der Erwartung nicht
        List<String> fotosNichtAlsBild = ergebnisse.stream()
                .filter(r -> "HANDY_FOTO".equals(r.erwartung().kategorie()) && r.eingabe() != Eingabe.BILD)
                .map(r -> r.erwartung().id()).toList();
        int artRohRichtig = (int) ergebnisse.stream()
                .filter(r -> r.vorschlag() != null && Vergleich.art(r.erwartung().art(), r.vorschlag().artRoh())).count();
        // Beleg-ID (bei echten Belegen die Kurz-ID), nie der Dateiname
        List<String> umstufungen = ergebnisse.stream().filter(r -> r.vorschlag() != null && r.vorschlag().umgestuft())
                .map(r -> r.erwartung().id() + ": " + r.vorschlag().artRoh() + " → " + r.vorschlag().art()).toList();
        int n = Math.max(1, ergebnisse.size());
        return new Kennzahlen(ergebnisse.size(), richtig,
                (int) ergebnisse.stream().filter(BelegErgebnis::gateRichtig).count(),
                fehlerUeberSchwelle(ergebnisse, schwelle), kleinsteSchwelle(ergebnisse),
                (int) ergebnisse.stream().filter(BelegErgebnis::qrGefunden).count(),
                (int) ergebnisse.stream().filter(r -> r.aufrufe() > 1).count(),
                (int) ergebnisse.stream().filter(r -> r.vorschlag() == null).count(),
                ein, aus, (int) ergebnisse.stream().filter(BelegErgebnis::tokensUnvollstaendig).count(), kosten,
                ergebnisse.stream().mapToLong(BelegErgebnis::ocrMs).sum() / n,
                ergebnisse.stream().mapToLong(BelegErgebnis::kiMs).sum() / n, fehlschlaege,
                bild.size(), einBild, ausBild, kostenBild, kosten - kostenBild, fotosNichtAlsBild, artRohRichtig,
                umstufungen);
    }

    /** Req 0.9: falsche Felder, deren Sicherheit die Schwelle erreicht (>=). */
    public static int fehlerUeberSchwelle(List<BelegErgebnis> ergebnisse, double schwelle) {
        return (int) falscheSicherheiten(ergebnisse).stream().filter(s -> s >= schwelle).count();
    }

    /** Kleinste Schwelle (Raster 0.01) mit höchstens einem falschen Feld darüber; null, wenn keine bis 1.0 reicht. */
    public static BigDecimal kleinsteSchwelle(List<BelegErgebnis> ergebnisse) {
        List<Double> falsch = falscheSicherheiten(ergebnisse);
        falsch.sort(Comparator.reverseOrder());
        if (falsch.size() <= 1) {
            return BigDecimal.ZERO.setScale(2);
        }
        BigDecimal zweite = BigDecimal.valueOf(falsch.get(1));
        BigDecimal schwelle = zweite.setScale(2, RoundingMode.FLOOR).add(new BigDecimal("0.01"));
        return schwelle.compareTo(BigDecimal.ONE) > 0 ? null : schwelle;
    }

    private static List<Double> falscheSicherheiten(List<BelegErgebnis> ergebnisse) {
        List<Double> werte = new ArrayList<>();
        for (BelegErgebnis r : ergebnisse) {
            if (r.vorschlag() == null) {
                continue;
            }
            for (Feld f : Feld.values()) {
                if (!r.richtig().get(f)) {
                    werte.add(r.vorschlag().sicherheit().get(f));
                }
            }
        }
        return werte;
    }

    static final DateTimeFormatter ZEITPUNKT = DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmmss");

    /** Ordnername aus dem Startzeitpunkt; --limit steht immer im Namen, damit Probeläufe erkennbar bleiben. */
    public static String ordnerName(LocalDateTime start, Modus modus, boolean k1, int limit) {
        return start.format(ZEITPUNKT) + "-" + modus.verzeichnis() + (k1 ? "-k1" : "") + (limit > 0 ? "-limit" + limit : "");
    }

    /** Legt den Laufordner neu an; existiert er schon, bricht der Lauf ab, statt einen Vollbericht zu überschreiben. */
    public static Path legeLaufordnerAn(Path berichte, String name) throws IOException {
        Files.createDirectories(berichte);
        return Files.createDirectory(berichte.resolve(name));
    }

    public static Path schreibe(Path ordner, Lauf lauf, List<BelegErgebnis> ergebnisse, Kennzahlen k) throws IOException {
        ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).enable(SerializationFeature.INDENT_OUTPUT);
        Map<String, Object> voll = new LinkedHashMap<>();
        voll.put("lauf", lauf);
        voll.put("kennzahlen", k);
        voll.put("belege", ergebnisse.stream().map(Bericht::eintrag).toList());
        try (OutputStream aus = Files.newOutputStream(ordner.resolve("ergebnisse.json"), StandardOpenOption.CREATE_NEW)) {
            json.writeValue(aus, voll);
        }
        Files.writeString(ordner.resolve("kennzahlen.md"), kennzahlenMarkdown(lauf, k), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW);
        return ordner;
    }

    private static Map<String, Object> eintrag(BelegErgebnis r) {
        Map<String, Object> m = new LinkedHashMap<>();
        Erwartung e = r.erwartung();
        m.put("id", e.id());
        m.put("teil", e.teil());
        m.put("kategorie", e.kategorie());
        m.put("randfaelle", e.randfaelle());
        m.put("erwartung", Map.of("art", String.valueOf(e.art()), "datum", String.valueOf(e.datum()),
                "partner", String.valueOf(e.partner()), "betrag", String.valueOf(e.betrag()),
                "belegnummer", String.valueOf(e.belegnummer())));
        m.put("vorschlag", r.vorschlag());
        // Art des Modells vor der Nachbearbeitung, um Prompt- und Nachbearbeitungswirkung zu trennen
        m.put("artRoh", r.vorschlag() == null ? null : r.vorschlag().artRoh());
        m.put("artRohSicherheit", r.vorschlag() == null ? null : r.vorschlag().artRohSicherheit());
        m.put("richtig", r.richtig());
        m.put("qrGefunden", r.qrGefunden());
        m.put("aufrufe", r.aufrufe());
        m.put("tokensEin", r.tokensEin());
        m.put("tokensAus", r.tokensAus());
        m.put("tokensUnvollstaendig", r.tokensUnvollstaendig());
        m.put("eingabe", r.eingabe());
        m.put("ocrMs", r.ocrMs());
        m.put("kiMs", r.kiMs());
        m.put("fehler", r.fehler());
        return m;
    }

    public static String kennzahlenMarkdown(Lauf lauf, Kennzahlen k) {
        StringBuilder sb = new StringBuilder();
        sb.append("### Lauf ").append(lauf.modus().verzeichnis()).append(lauf.k1() ? ", Variante K1 (Bild statt OCR-Text)" : "")
                .append(" (").append(lauf.zeitpunkt()).append(")\n\n");
        sb.append("- Modell `").append(lauf.modell()).append("`, Prompt `").append(lauf.promptHash())
                .append("`, Schwelle ").append(lauf.schwelle()).append(" (≥)\n");
        sb.append("- Belege: ").append(k.belege()).append(", davon ohne Vorschlag: ").append(k.fehlgeschlagen())
                .append(", Nachfragen (Req 7.3): ").append(k.nachfragen()).append(", Swiss QR gelesen: ")
                .append(k.qrGefunden()).append("\n\n");
        sb.append("| Feld | richtig | Quote |\n| --- | --- | --- |\n");
        for (Feld f : Feld.values()) {
            sb.append("| ").append(f.name().toLowerCase(Locale.ROOT)).append(" | ").append(k.richtig().get(f))
                    .append("/").append(k.belege()).append(" | ").append(prozent(k.richtig().get(f), k.belege())).append(" |\n");
        }
        sb.append("| **gesamt (Felder)** | ").append(k.felderRichtig()).append("/").append(k.felderGesamt()).append(" | ")
                .append(prozent(k.felderRichtig(), k.felderGesamt())).append(" |\n");
        sb.append("| **Belege mit Art, Datum, Partner, Betrag richtig** (Req 0.3) | ").append(k.gateRichtig()).append("/")
                .append(k.belege()).append(" | ").append(prozent(k.gateRichtig(), k.belege())).append(" |\n\n");
        sb.append("- Falsche Felder mit Sicherheit ≥ ").append(lauf.schwelle()).append(": ").append(k.fehlerUeberSchwelle())
                .append("; kleinste Schwelle mit höchstens 1 Fehler: ")
                .append(k.kleinsteSchwelle() == null ? "keine ≤ 1.0" : k.kleinsteSchwelle().toPlainString()).append("\n");
        sb.append("- Art roh (Modell) richtig: ").append(k.artRohRichtig()).append("/").append(k.belege())
                .append(", nach Nachbearbeitung: ").append(k.richtig().get(Feld.ART)).append("/").append(k.belege())
                .append("; Umstufungen: ").append(k.umstufungen().size())
                .append(k.umstufungen().isEmpty() ? "" : " (" + String.join("; ", k.umstufungen()) + ")").append("\n");
        sb.append("- Eingabe: Bild ").append(k.belegeBild()).append(", Text ").append(k.belege() - k.belegeBild())
                .append("; Kontrolle HANDY_FOTO als Bild: ")
                .append(k.fotosNichtAlsBild().isEmpty() ? "ok" : "Abweichung bei " + String.join(", ", k.fotosNichtAlsBild()))
                .append("\n");
        sb.append(String.format(Locale.ROOT, "- Tokens: %s ein / %s aus; Kosten ca. CHF %.4f (CHF %.2f / %.2f je 1 Mio., exkl. MWST)%n",
                tausender(k.tokensEin()), tausender(k.tokensAus()), k.kostenChf(), lauf.preisEin(), lauf.preisAus()));
        sb.append(String.format(Locale.ROOT, "  - davon Bild-Belege: %s ein / %s aus, CHF %.4f; Text-Belege: %s ein / %s aus, CHF %.4f%n",
                tausender(k.tokensEinBild()), tausender(k.tokensAusBild()), k.kostenBild(),
                tausender(k.tokensEin() - k.tokensEinBild()), tausender(k.tokensAus() - k.tokensAusBild()), k.kostenText()));
        if (k.tokensUnvollstaendig() > 0) {
            sb.append("- Tokens unvollständig bei ").append(k.tokensUnvollstaendig())
                    .append(" Belegen (Aufruf ohne Modellantwort, z. B. Timeout oder HTTP-Fehler; Tokens nicht bekannt)\n");
        }
        sb.append("- Laufzeit je Beleg (Mittel): OCR ").append(k.ocrMsSchnitt()).append(" ms (inkl. Cache), KI ")
                .append(k.kiMsSchnitt()).append(" ms\n");
        sb.append("- Realitätsprüfung (Req 0.10): ").append(lauf.echteBestaetigt()).append(" bestätigte echte Belege, ")
                .append(lauf.echteAusgeschlossen()).append(" noch nicht bestätigt oder mit Prüfmarke\n\n");
        sb.append("Fehlschläge (Beleg-ID: Feld):\n\n");
        if (k.fehlschlaege().isEmpty()) {
            sb.append("- keine\n");
        }
        k.fehlschlaege().forEach(f -> sb.append("- ").append(f).append("\n"));
        return sb.toString();
    }

    private static String tausender(long n) {
        return String.format(Locale.ROOT, "%,d", n).replace(',', '\'');
    }

    private static String prozent(int teil, int gesamt) {
        return gesamt == 0 ? "–" : String.format(Locale.ROOT, "%.0f %%", 100.0 * teil / gesamt);
    }
}
